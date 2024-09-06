package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Illegal.Status;
import lombok.Getter;
import org.apache.bcel.generic.InstructionHandle;

import java.util.Collection;

@Getter
public class IllegalInvokeException extends EvalBytecodeException {
    public IllegalInvokeException(Collection<Status> status, Object source, InstructionHandle instruction) {
        super(status + ", source=" + source + ", instruction=" + instruction);
    }
}
