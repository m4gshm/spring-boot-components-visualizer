package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.*;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.generic.*;

import java.util.*;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.instructionHandleStream;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;
import static org.springframework.util.Assert.state;

@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = PRIVATE)
@ToString(onlyExplicitlyIncluded = true)
public class InvokeBranch {
    @ToString.Include
    NavigableMap<Integer, InstructionHandle> ops = new TreeMap<>();
    Map<Class<?>, List<InstructionHandle>> opsGroups = new HashMap<>();
    @Getter
    List<InvokeBranch> prev = new ArrayList<>();
    @Getter
    @ToString.Include
    List<InvokeBranch> next = new ArrayList<>();

    public static InvokeBranch newTree(Code methodCode) {
        var instructionHandleStream = instructionHandleStream(methodCode);
        var cursor = instructionHandleStream.findFirst().orElse(null);
        return newTree(null, cursor, List.of());
    }

    private static InvokeBranch newTree(InvokeBranch prev, InstructionHandle start, @NonNull Collection<InvokeBranch> siblings) {
        var branch = new InvokeBranch();
        var cursor = start;
        var isFirst = true;
        while (cursor != null) {
            var instruction = cursor.getInstruction();
            for (var targeter : cursor.getTargeters()) {
                if (targeter instanceof BranchInstruction) {
                    var targeterInstruction = (BranchInstruction) targeter;
                    var target = targeterInstruction.getTarget();
                    var refFromPrev = isRefFromPrev(prev, targeter);

                    if (!isFirst && refFromPrev) {
                        //maybe target from prev branch
                        var tail = newTree(branch, cursor, List.of());
                        return witTail(branch, tail);
                    } else if (!refFromPrev) {
                        var tailOwnedBranch = foundEndedBy(targeterInstruction, siblings);
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
            branch.add(cursor);
            var next = cursor.getNext();
            if (instruction instanceof BranchInstruction) {
                var jumpTo = ((BranchInstruction) instruction).getTarget();
                if (instruction instanceof GotoInstruction) {
                    var jumpToPosition = jumpTo.getPosition();
                    var goBack = jumpToPosition < cursor.getPosition();
                    if (!goBack) {
                        //no loop
                        InvokeBranch tail;
                        //split sibling
                        var jumpToSibling = siblings.stream().filter(sibling -> {
                            return sibling.ops.get(jumpToPosition) != null;
                        }).findFirst().orElse(null);
                        if (jumpToSibling != null) {
                            //todo the jumpToSibling must be same as the jumpTo
                            var remindedHead = new TreeMap<>(jumpToSibling.ops.headMap(jumpToPosition));
                            var tailOps = new TreeMap<>(jumpToSibling.ops.tailMap(jumpToPosition));
                            tail = new InvokeBranch(tailOps, newOpsGroups(tailOps), new ArrayList<>(List.of(jumpToSibling)), jumpToSibling.next);
                            jumpToSibling.next = new ArrayList<>(List.of(tail));
                            jumpToSibling.ops = remindedHead;
                            jumpToSibling.opsGroups = newOpsGroups(remindedHead);
                        } else {
                            tail = newTree(branch, jumpTo, List.of());
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
        var leftBranch = newTree(prev, left, List.of());
        var lefts = new ArrayList<InvokeBranch>();
        lefts.add(leftBranch);
        prev.addNext(leftBranch);
        for (var right : rights) {
            var rightBranch = newTree(prev, right, lefts);
            lefts.add(rightBranch);
            prev.addNext(rightBranch);
        }
    }

    private static void addToOpsGroups(InstructionHandle instructionHandle, Map<Class<?>, List<InstructionHandle>> opsGroups) {
        Class<?> aClass = instructionHandle.getInstruction().getClass();
        while (!(aClass == null || aClass.equals(Instruction.class) || aClass.equals(Objects.class))) {
            opsGroups.computeIfAbsent(aClass, k -> new ArrayList<>()).add(instructionHandle);
            aClass = aClass.getSuperclass();
        }
    }

    private void add(InstructionHandle instructionHandle) {
        this.ops.put(instructionHandle.getPosition(), instructionHandle);
        addToOpsGroups(instructionHandle, this.opsGroups);
    }

    public List<InstructionHandle> getPrevInstructions(InstructionHandle instructionHandle) {
        var position = instructionHandle.getPosition();
        var upperOps = ops.headMap(position);
        if (upperOps.isEmpty()) {
            var prevInstructions = prev.stream().map(InvokeBranch::last).filter(Objects::nonNull)
                    .collect(toList());
            return prevInstructions;
        }
        var prev = upperOps.get(upperOps.lastKey());
        return prev != null ? List.of(prev) : List.of();
    }

    public List<InvokeBranch> findBranches(InstructionHandle instructionHandle) {
        if (instructionHandle == null) {
            return null;
        }
        var exists = ops.get(instructionHandle.getPosition());
        if (exists != null && exists.getInstruction().equals(instructionHandle.getInstruction())) {
            return List.of(this);
        } else {
            var next = this.next;
            return next == null ? List.of() : next.stream().flatMap(b -> {
                return b.findBranches(instructionHandle).stream();
            }).filter(Objects::nonNull).distinct().collect(toList());
        }
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
