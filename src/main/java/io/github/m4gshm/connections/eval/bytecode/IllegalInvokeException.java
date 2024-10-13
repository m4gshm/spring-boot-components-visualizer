package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Result;
import lombok.Getter;
import org.apache.bcel.generic.InstructionHandle;

@Getter
public class IllegalInvokeException extends UnresolvedResultException {

    public IllegalInvokeException(Result source, InstructionHandle instruction, Exception e) {
        super(getMessage(source, instruction), source, e);
    }

    public IllegalInvokeException(Result source, InstructionHandle instruction) {
        super(getMessage(source, instruction), source);
    }

    private static String getMessage(Result source, InstructionHandle instruction) {
        return "source=" + source + ", instruction=" + instruction;
    }
}
