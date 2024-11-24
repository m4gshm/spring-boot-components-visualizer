package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.experimental.UtilityClass;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.generic.BranchInstruction;
import org.apache.bcel.generic.InstructionHandle;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.instructionHandleStream;
import static java.util.Map.entry;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.ofNullable;

@UtilityClass
public class InstructionHandleUtils {
    public static InstructionHandle getPrev(InstructionHandle instructionHandle, Map<Integer, List<InstructionHandle>> jumpTo) {
        //log
        //todo multibranch????
        var firstJump = getFirstJumpedAbove(instructionHandle, jumpTo);
        if (firstJump != null) {
            while (true) {
                var nextJump = getFirstJumpedAbove(firstJump, jumpTo);
                if (nextJump != null) {
                    firstJump = nextJump;
                } else {
                    break;
                }
            }
            //todo need call eval(firstJump)
            instructionHandle = firstJump.getPrev();
        } else {
            instructionHandle = instructionHandle.getPrev();
        }
        return instructionHandle;
    }

    private static InstructionHandle getFirstJumpedAbove(InstructionHandle instructionHandle,
                                                         Map<Integer, List<InstructionHandle>> jumpTo) {
        var jumpsFrom = jumpTo.get(instructionHandle.getPosition());
        return ofNullable(jumpsFrom).flatMap(Collection::stream)
                .filter(j -> j.getPosition() < instructionHandle.getPosition())
                .findFirst().orElse(null);
    }

    public static Map<Integer, List<InstructionHandle>> getJumpTo(Code methodCode) {
        return instructionHandleStream(methodCode).map(instructionHandle -> {
            var instruction = instructionHandle.getInstruction();
            if (instruction instanceof BranchInstruction) {
                var jumpToIndex = ((BranchInstruction) instruction).getTarget().getPosition();
                return entry(jumpToIndex, instructionHandle);
            } else {
                return null;
            }
        }).filter(Objects::nonNull).collect(groupingBy(Map.Entry::getKey, mapping(Map.Entry::getValue, toList())));
    }
}
