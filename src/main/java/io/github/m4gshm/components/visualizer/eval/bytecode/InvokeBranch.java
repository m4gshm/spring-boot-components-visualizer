package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.*;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.generic.*;

import java.util.*;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.instructionHandleStream;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;

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
        return newTree(cursor, List.of());
    }

    private static InvokeBranch newTree(InstructionHandle start, @NonNull Collection<InvokeBranch> siblings) {
        var branch = new InvokeBranch();
        var hasSiblings = !siblings.isEmpty();

        var cursor = start;
        while (cursor != null) {
            var instruction = cursor.getInstruction();
            int cursorPosition = cursor.getPosition();
            if (hasSiblings) for (var targeter : cursor.getTargeters()) {
                if (targeter instanceof BranchInstruction) {
                    var branchInstruction = (BranchInstruction) targeter;
                    var target = branchInstruction.getTarget();
                    var tailOwnedBranch = foundEndedBy(branchInstruction, siblings);
                    if (tailOwnedBranch != null) {
                        var tail = requireNonNull(foundStartedFrom(target, List.of(tailOwnedBranch)), "no tail for branch");
                        if (tail != null) {
                            branch.addNext(tail);
                            return branch;
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
                    var goBack = jumpToPosition < cursorPosition;
                    if (!goBack) {
                        //no loop
                        InvokeBranch tail;
                        //split sibling
                        var jumpToSibling = siblings.stream().filter(sibling -> sibling.ops.get(jumpToPosition) != null).findFirst().orElse(null);
                        if (jumpToSibling != null) {
                            //todo the jumpToSibling must be same as the jumpTo
                            var remindedHead = new TreeMap<>(jumpToSibling.ops.headMap(jumpToPosition));
                            var tailOps = new TreeMap<>(jumpToSibling.ops.tailMap(jumpToPosition));
                            tail = new InvokeBranch(tailOps, newOpsGroups(tailOps), new ArrayList<>(List.of(jumpToSibling)), jumpToSibling.next);
                            jumpToSibling.next = new ArrayList<>(List.of(tail));
                            jumpToSibling.ops = remindedHead;
                            jumpToSibling.opsGroups = newOpsGroups(remindedHead);
                        } else {
                            tail = newTree(jumpTo, List.of());
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
        }
        return branch;
    }

    private static InvokeBranch foundEndedBy(BranchInstruction target, Collection<InvokeBranch> siblings) {
        return siblings.stream().map(sibling -> {
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

    private static void fork(InvokeBranch branch, InstructionHandle left, List<InstructionHandle> rights) {
        var leftBranch = newTree(left, List.of());
        var lefts = new ArrayList<InvokeBranch>();
        lefts.add(leftBranch);
        branch.addNext(leftBranch);
        for (var right : rights) {
            var rightBranch = newTree(right, lefts);
            lefts.add(rightBranch);
            branch.addNext(rightBranch);
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
            next.add(branch);
            branch.prev.add(this);
        }
    }

}
