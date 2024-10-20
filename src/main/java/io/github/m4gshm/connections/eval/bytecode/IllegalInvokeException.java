package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Result;
import lombok.Getter;
import org.apache.bcel.generic.InstructionHandle;

@Getter
public class IllegalInvokeException extends UnresolvedResultException {

    private final Object target;

    public IllegalInvokeException(Exception cause, Object target, InstructionHandle instruction, Result invoke) {
        super(getMessage(invoke, instruction), cause, invoke);
        this.target = target;
    }

    public IllegalInvokeException(Result source, InstructionHandle instruction, Object target) {
        super(getMessage(source, instruction), source);
        this.target = target;
    }

    private static String getMessage(Result source, InstructionHandle instruction) {
        return "source=" + source + ", instruction=" + instruction;
    }
}
