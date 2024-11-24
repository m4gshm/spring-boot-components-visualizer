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
import java.util.stream.Collectors;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.instructionHandleStream;
import static lombok.AccessLevel.PRIVATE;

@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = PRIVATE)
@ToString(onlyExplicitlyIncluded = true)
public class InvokeBranch {
    @Getter
    InvokeBranch prev;
    @ToString.Include
    NavigableMap<Integer, InstructionHandle> ops = new TreeMap<>();
    Map<Class<?>, List<InstructionHandle>> opsGroups = new HashMap<>();
    @Getter
    @ToString.Include
    List<InvokeBranch> next = List.of();
    boolean loop;

    public static InvokeBranch newTree(Code methodCode) {
        var instructionHandleStream = instructionHandleStream(methodCode);
        var cursor = instructionHandleStream.findFirst().orElse(null);
        return newTree(cursor);
    }

    private static InvokeBranch newTree(InstructionHandle start) {
        var branch = new InvokeBranch();

        var cursor = start;
        while (cursor != null) {
            var instruction = cursor.getInstruction();
            var next = cursor.getNext();
            if (instruction instanceof BranchInstruction) {
                var jumpTo = ((BranchInstruction) instruction).getTarget();
                var jumpToPosition = jumpTo.getPosition();
                var isGoTo = instruction instanceof GotoInstruction;
                if (isGoTo) {
                    branch.add(cursor);
                    var goBack = jumpToPosition < cursor.getPosition();
                    if (goBack) {
                        //end branch
                        return branch;
                    } else {
                        next = jumpTo;
                    }
                } else {
                    var leftBranch = newTree(next);
//                    //check if it is a loop
//                    var loop = false;
//                    var lastPoint = leftBranch.ops.lastEntry().getValue();
//                    var lastInstruction = lastPoint.getInstruction();
//                    if (lastInstruction instanceof GotoInstruction) {
//                        var gotoI = (GotoInstruction) lastInstruction;
//                        var target = gotoI.getTarget();
//                        loop = target.getPosition() <= cursor.getPosition();
//                        if (loop) {
//                            //prepend
//                            var cursor1 = target;
//                            while (cursor1 != null && cursor1.getPosition() != cursor.getPosition()) {
//                                leftBranch.add(cursor1);
//                                branch.remove(cursor1);
//                                cursor1 = cursor1.getNext();
//                            }
//                            leftBranch.loop = true;
//                        }
//                    }
                    branch.addNext(leftBranch);
//                    if (!loop) {
//                        //fork
                    var rightBranch = newTree(jumpTo);
                    branch.addNext(rightBranch);
//                    }
                    return branch;
                }
            } else {
                branch.add(cursor);
            }
            cursor = next;
        }
        return branch;
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
        Class<?> aClass = instructionHandle.getInstruction().getClass();
        while (!(aClass == null || aClass.equals(Instruction.class) || aClass.equals(Objects.class))) {
            this.opsGroups.computeIfAbsent(aClass, k -> new ArrayList<>()).add(instructionHandle);
            aClass = aClass.getSuperclass();
        }
    }

    public InstructionHandle getPrevInstruction(InstructionHandle instructionHandle) {
        var position = instructionHandle.getPosition();
        var upperOps = ops.headMap(position);
        if (upperOps.isEmpty()) {
            var prev = this.prev;
            return prev != null ? prev.getPrevInstruction(instructionHandle) : null;
        }
        var prev = upperOps.get(upperOps.lastKey());
        return prev;
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
            }).filter(Objects::nonNull).collect(Collectors.toList());
        }
    }

    private void addNext(InvokeBranch... invokeBranches) {
        var exists = this.next;
        var newNext = List.of(invokeBranches);
        newNext.forEach(n -> n.prev = this);
        if (exists == null || exists.isEmpty())
            this.next = newNext;
        else {
            var next = new ArrayList<InvokeBranch>(exists.size() + newNext.size());
            next.addAll(exists);
            next.addAll(newNext);
            this.next = next;
        }
    }

    public <T extends Instruction> List<InstructionHandle> findInstructions(Class<? super T> aClass) {
        return this.opsGroups.getOrDefault(aClass, List.of());
    }
}
