package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.*;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.instructionHandleStream;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;
import static org.springframework.util.Assert.state;

@AllArgsConstructor
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE)
@ToString(onlyExplicitlyIncluded = true)
public class InvokeBranch {
    final Class<?> aClass;
    final Method method;
    @ToString.Include
    NavigableMap<Integer, InstructionHandle> ops = new TreeMap<>();
    Map<Class<?>, List<InstructionHandle>> opsGroups = new HashMap<>();
    @Getter
    List<InvokeBranch> prev = new ArrayList<>();
    @Getter
    @ToString.Include
    List<InvokeBranch> next = new ArrayList<>();

    public static InvokeBranch newTree(Class<?> aClass, Method method) {
        var instructionHandleStream = instructionHandleStream(method.getCode());
        var cursor = instructionHandleStream.findFirst().orElse(null);
        return newTree(aClass, method, null, cursor, List.of());
    }

    private static InvokeBranch newTree(Class<?> aClass, Method method, InvokeBranch prev, InstructionHandle start,
                                        @NonNull Collection<InvokeBranch> siblings) {
        var branch = new InvokeBranch(aClass, method);
        var cursor = start;
        var isFirst = true;
        while (cursor != null) {
            var instruction = cursor.getInstruction();
            var targeters = cursor.getTargeters();
            for (var targeter : targeters) {
                if (targeter instanceof BranchInstruction) {
                    var targeterInstruction = (BranchInstruction) targeter;
                    var index = targeterInstruction.getIndex();
                    var isGoto = targeterInstruction instanceof GotoInstruction;
                    var isLoop = index < 0 && isGoto;
                    if (!isFirst && isLoop) {
                        var tail = newTree(aClass, method, branch, cursor, List.of());
                        return witTail(branch, tail);
                    } else {
                        var refFromPrev = isRefFromPrev(prev, targeter);
                        if (!isFirst && refFromPrev) {
                            //maybe target from prev branch
                            var tail = newTree(aClass, method, branch, cursor, List.of());
                            return witTail(branch, tail);
                        } else if (!refFromPrev) {
                            var tailOwnedBranch = foundEndedBy(targeterInstruction, siblings);
                            var target = targeterInstruction.getTarget();
                            if (tailOwnedBranch != null) {
                                var tail = foundStartedFrom(target, List.of(tailOwnedBranch));
                                state(tail != null, "no tail for branch");
                                return witTail(branch, tail);
                            } else if (!siblings.isEmpty()) {
                                var tail = foundStartedFrom(target, siblings);
                                if (tail != null) {
                                    return witTail(branch, tail);
                                }
                            }
                        }
                    }

                }
            }
            branch.add(cursor);
            var next = cursor.getNext();
            if (instruction instanceof BranchInstruction) {
                var jumpTo = ((BranchInstruction) instruction).getTarget();
                if (instruction instanceof GotoInstruction) {
                    var jumpToPosition = jumpTo.getPosition();
                    var goForward = jumpToPosition > cursor.getPosition();
                    if (goForward) {
                        //no loop
                        //split sibling
                        var jumpToSibling = getJumpToSibling(jumpToPosition, siblings);
                        InvokeBranch tail;
                        if (jumpToSibling != null) {
                            //todo the jumpToSibling must be same as the jumpTo
                            tail = jumpToSibling.splitBranch(jumpToPosition);
                        } else {
                            tail = newTree(aClass, method, branch, jumpTo, List.of());
                        }
                        branch.addNext(tail);
                    }
                } else if (instruction instanceof Select) {
                    var select = (Select) instruction;
                    var targets = asList(select.getTargets());
                    fork(branch, jumpTo, targets);
                } else {
                    fork(branch, next, List.of(jumpTo));
                }
                return branch;
            }
            cursor = next;
            isFirst = false;
        }
        return branch;
    }

    private static InvokeBranch getJumpToSibling(int jumpToPosition, Collection<InvokeBranch> siblings) {
        return siblings.stream().filter(sibling -> {
            return sibling.ops.get(jumpToPosition) != null;
        }).findFirst().orElse(null);
    }

    private static InvokeBranch witTail(InvokeBranch branch, InvokeBranch tail) {
        if (branch.ops.isEmpty()) {
            return tail;
        }
        branch.addNext(tail);
        return branch;
    }

    private static boolean isRefFromPrev(InvokeBranch prev, InstructionTargeter targeter) {
        if (prev == null) {
            return false;
        }
        var prevLast = prev.last();
        var refFromPrev = prevLast.getInstruction() == targeter;
        if (refFromPrev) {
            return true;
        }
        var superPrevs = prev.getPrev();
        if (superPrevs == null) {
            return false;
        }
        return superPrevs.stream().anyMatch(p -> isRefFromPrev(p, targeter));
    }

    private static InvokeBranch foundEndedBy(BranchInstruction target, Collection<InvokeBranch> branches) {
        return branches.stream().map(sibling -> {
            var handle = sibling.last();
            if (handle == null) {
                return foundEndedBy(target, sibling.getNext());
            }
            var instruction = handle.getInstruction();
            if (instruction instanceof BranchInstruction) {
                var branchInstruction = (BranchInstruction) instruction;
                int targetPosition = target.getTarget().getPosition();
                var found = targetPosition == branchInstruction.getTarget().getPosition();
                return found ? sibling : foundEndedBy(target, sibling.getNext());
            } else {
                return foundEndedBy(target, sibling.getNext());
            }
        }).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static InvokeBranch foundStartedFrom(InstructionHandle target, Collection<InvokeBranch> siblings) {
        return siblings.stream().map(sibling -> {
            var handle = sibling.first();
            if (handle == null) {
                return foundStartedFrom(target, sibling.getNext());
            }
            int position = handle.getPosition();
            var found = position == target.getPosition();
            return found ? sibling : foundStartedFrom(target, sibling.getNext());
        }).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static HashMap<Class<?>, List<InstructionHandle>> newOpsGroups(Map<Integer, InstructionHandle> tailOps) {
        var tailOpsGroups = new HashMap<Class<?>, List<InstructionHandle>>();
        for (var value : tailOps.values()) {
            addToOpsGroups(value, tailOpsGroups);
        }
        return tailOpsGroups;
    }

    private static void fork(InvokeBranch prev, InstructionHandle left, List<InstructionHandle> rights) {
        var leftBranch = newTree(prev.aClass, prev.method, prev, left, List.of());
        prev.addNext(leftBranch);
        var lefts = new ArrayList<InvokeBranch>();
        lefts.add(leftBranch);
        for (var right : rights) {
            var rightBranch = newTree(prev.aClass, prev.method, prev, right, lefts);
            prev.addNext(rightBranch);
            lefts.add(rightBranch);
        }
    }

    private static void addToOpsGroups(InstructionHandle instructionHandle, Map<Class<?>, List<InstructionHandle>> opsGroups) {
        Class<?> aClass = instructionHandle.getInstruction().getClass();
        while (!(aClass == null || aClass.equals(Instruction.class) || aClass.equals(Objects.class))) {
            opsGroups.computeIfAbsent(aClass, k -> new ArrayList<>()).add(instructionHandle);
            aClass = aClass.getSuperclass();
        }
    }

    @Override
    @SneakyThrows
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        var that = (InvokeBranch) object;
        if (ops.size() != that.ops.size()) {
            return false;
        }

        for (var index : ops.keySet()) {
            var thisOp = ops.get(index);
            var thatOp = that.ops.get(index);
            if (thatOp == null) {
                return false;
            }
            if (InstructionUtils.equals(thisOp, thatOp)) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        var hash = 0;
        for (var index : ops.keySet()) {
            var thisOp = ops.get(index);
            var thisInst = thisOp.getInstruction();
            hash += thisInst.hashCode();
        }
        return hash;
    }

    private InvokeBranch splitBranch(int splitPosition) {
        var ops = this.ops;
        var remindedHead = new TreeMap<>(ops.headMap(splitPosition));
        var tailOps = new TreeMap<>(ops.tailMap(splitPosition));
        if (!remindedHead.isEmpty() && !tailOps.isEmpty()) {
            var tail = new InvokeBranch(null, null, tailOps, newOpsGroups(tailOps),
                    new ArrayList<>(List.of(this)), this.next);
            this.next = new ArrayList<>(List.of(tail));
            this.ops = remindedHead;
            this.opsGroups = newOpsGroups(remindedHead);
            return tail;
        }
        return this;
    }

    private void add(InstructionHandle instructionHandle) {
        this.ops.put(instructionHandle.getPosition(), instructionHandle);
        addToOpsGroups(instructionHandle, this.opsGroups);
    }

    public List<InstructionHandle> getPrevInstructions(InstructionHandle instructionHandle) {
        var position = instructionHandle.getPosition();
        var upperOps = ops.headMap(position);
        if (upperOps.isEmpty()) {
            return prev.stream().map(InvokeBranch::last).filter(Objects::nonNull).collect(toList());
        }
        var prev = upperOps.get(upperOps.lastKey());
        return prev != null ? List.of(prev) : List.of();
    }

    public InstructionHandle getPrevInstruction(InstructionHandle instructionHandle) {
        var position = instructionHandle.getPosition();
        return ops.higherEntry(position).getValue();
    }


    public Stream<InvokeBranch> findContains(int position, Function<InvokeBranch, Collection<InvokeBranch>> selector) {
        var exists = ops.get(position);
        if (exists != null) {
            return Stream.of(this);
        } else {
            var prev = selector.apply(this);
            return prev == null ? Stream.of() : prev.stream().parallel().flatMap(b -> {
                return b.findContains(position, selector);
            }).filter(Objects::nonNull).distinct();
        }
    }

    public List<InvokeBranch> findPrevBranchContains(int position) {
        return findContains(position, InvokeBranch::getPrev).collect(toList());
    }

    public List<InvokeBranch> findNextBranchContains(int position) {
        return findContains(position, InvokeBranch::getNext).collect(toList());
    }

    public <T extends Instruction> List<InstructionHandle> findInstructions(Class<? super T> aClass) {
        return this.opsGroups.getOrDefault(aClass, List.of());
    }

    public InstructionHandle first() {
        return ops.isEmpty() ? null : ops.firstEntry().getValue();
    }

    public InstructionHandle last() {
        return ops.isEmpty() ? null : ops.lastEntry().getValue();
    }

    private void addNext(InvokeBranch... invokeBranches) {
        addNext(asList(invokeBranches));
    }

    private void addNext(Collection<InvokeBranch> invokeBranches) {
        for (var branch : invokeBranches) {
            state(!next.contains(branch), () -> "Branch " + branch + " already added");
            next.add(branch);
            branch.prev.add(this);
        }
    }

}
