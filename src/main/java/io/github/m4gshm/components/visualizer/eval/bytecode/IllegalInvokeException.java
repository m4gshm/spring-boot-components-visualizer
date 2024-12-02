package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.result.Result;
import lombok.Getter;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;

@Getter
public class IllegalInvokeException extends UnresolvedResultException {

    private final Object target;

    public IllegalInvokeException(Throwable cause, Object target, InstructionHandle instruction, Result invoke) {
        super(getMessage(invoke, List.of(instruction)), cause, invoke);
        this.target = target;
    }

    public IllegalInvokeException(Result source, List<InstructionHandle> instruction, Object target) {
        super(getMessage(source, instruction), source);
        this.target = target;
    }

    private static String getMessage(Result source, List<InstructionHandle> instruction) {
        return "source=" + source + ", instruction=" + instruction;
    }
}
