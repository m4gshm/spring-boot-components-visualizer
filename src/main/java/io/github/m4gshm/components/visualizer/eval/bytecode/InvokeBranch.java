package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.generic.BranchInstruction;
import org.apache.bcel.generic.GotoInstruction;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;

import java.util.*;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.instructionHandleStream;
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
        return newTree(cursor, null);
    }

    private static InvokeBranch newTree(InstructionHandle start, InvokeBranch sibling) {
        var branch = new InvokeBranch();

        var cursor = start;
        while (cursor != null) {
            var instruction = cursor.getInstruction();
            int cursorPosition = cursor.getPosition();
            if (sibling != null) for (var targeter : cursor.getTargeters()) {
                if (targeter instanceof BranchInstruction) {
                    var target = ((BranchInstruction) targeter).getTarget();
                    var tail = foundStartedFrom(target, List.of(sibling));
                    if (tail != null) {
                        branch.addNext(tail);
                        return branch;
                    }
                }
            }
            branch.add(cursor);
            var next = cursor.getNext();
            if (instruction instanceof BranchInstruction) {
                var jumpTo = ((BranchInstruction) instruction).getTarget();
                var jumpToPosition = jumpTo.getPosition();
                var isGoTo = instruction instanceof GotoInstruction;
                if (isGoTo) {
                    var goBack = jumpToPosition < cursorPosition;
                    if (!goBack) {
                        //no loop
                        InvokeBranch tail;
                        //split sibling
                        var jumpToSibling = sibling != null ? sibling.ops.get(jumpToPosition) : null;
                        if (jumpToSibling != null) {
                            //todo the jumpToSibling must be same as the jumpTo
                            var remindedHead = new TreeMap<>(sibling.ops.headMap(jumpToPosition));
                            var tailOps = new TreeMap<>(sibling.ops.tailMap(jumpToPosition));
                            tail = new InvokeBranch(tailOps, newOpsGroups(tailOps), new ArrayList<>(List.of(sibling)), sibling.next);
                            sibling.next = new ArrayList<>(List.of(tail));
                            sibling.ops = remindedHead;
                            sibling.opsGroups = newOpsGroups(remindedHead);
                        } else {
                            tail = newTree(jumpTo, null);
                        }
                        branch.addNext(tail);
                    }
                } else {
                    fork(branch, next, jumpTo);
                }
                return branch;
            }
            cursor = next;
        }
        return branch;
    }

    private static InvokeBranch foundStartedFrom(InstructionHandle target, Collection<InvokeBranch> siblings) {
        return siblings.stream().map(s -> {
            var handle = s.ops.firstEntry().getValue();
            var instruction = handle.getInstruction();
            int position = handle.getPosition();
            var found = position == target.getPosition() && instruction.equals(target.getInstruction());
            return found ? s : foundStartedFrom(target, s.getNext());
        }).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static HashMap<Class<?>, List<InstructionHandle>> newOpsGroups(Map<Integer, InstructionHandle> tailOps) {
        var tailOpsGroups = new HashMap<Class<?>, List<InstructionHandle>>();
        for (var value : tailOps.values()) {
            addToOpsGroups(value, tailOpsGroups);
        }
        return tailOpsGroups;
    }

    private static void fork(InvokeBranch branch, InstructionHandle left, InstructionHandle right) {
        var leftBranch = newTree(left, null);
        var rightBranch = newTree(right, leftBranch);
        branch.addNext(leftBranch, rightBranch);
    }

    private static void addToOpsGroups(InstructionHandle instructionHandle, Map<Class<?>, List<InstructionHandle>> opsGroups) {
        Class<?> aClass = instructionHandle.getInstruction().getClass();
        while (!(aClass == null || aClass.equals(Instruction.class) || aClass.equals(Objects.class))) {
            opsGroups.computeIfAbsent(aClass, k -> new ArrayList<>()).add(instructionHandle);
            aClass = aClass.getSuperclass();
        }
    }

    private void remove(InstructionHandle instructionHandle) {
        var position = instructionHandle.getPosition();
        ops.remove(position);
        var instruction = instructionHandle.getInstruction();
        Class<?> aClass = instruction.getClass();
        while (!(aClass == null || aClass.equals(Instruction.class) || aClass.equals(Objects.class))) {
            var instructionHandles = opsGroups.get(aClass);
            if (instructionHandles != null) {
                instructionHandles.remove(instructionHandle);
                if (instructionHandles.isEmpty()) {
                    opsGroups.remove(aClass);
                }
            }
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
            var prevInstructions = prev.stream().map(p -> p.ops.lastEntry().getValue())
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
        addNext(Arrays.asList(invokeBranches));
    }

    private void addNext(Collection<InvokeBranch> invokeBranches) {
        for (var branch : invokeBranches) {
            next.add(branch);
            branch.prev.add(this);
        }
    }

}
