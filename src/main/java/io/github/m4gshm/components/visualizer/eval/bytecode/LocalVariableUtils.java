package io.github.m4gshm.components.visualizer.eval.bytecode;
import lombok.var;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;

@Slf4j
@UtilityClass
public class LocalVariableUtils {

    public static LocalVariable getLocalVariable(Method method, int varIndex, InstructionHandle instructionHandle) {
        return findLocalVariable(method, getLocalVariables(method, varIndex), instructionHandle);
    }

    static List<LocalVariable> getLocalVariables(Method method, int index) {
        var localVariableTable = Stream.of(method.getLocalVariableTable().getLocalVariableTable())
                .collect(groupingBy(LocalVariable::getIndex));
        return localVariableTable.getOrDefault(index, Collections.emptyList());
    }

    static LocalVariable findLocalVariable(Method method, List<LocalVariable> localVariables, InstructionHandle instructionHandle) {
        if (localVariables.isEmpty()) {
            log.debug("no matched local variables for instruction {}, method {}", instructionHandle,
                    EvalUtils.toString(method));
            return null;
        }
        var position = instructionHandle.getPosition();
        return localVariables.stream().filter(variable -> {
            int startPC = variable.getStartPC();
            var endPC = startPC + variable.getLength();
            return startPC <= position && position <= endPC;
        }).findFirst().orElseGet(() -> {
            return localVariables.get(0);
        });
    }

}
