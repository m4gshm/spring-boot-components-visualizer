package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Delay;
import lombok.Getter;

@Getter
public class NoCallException extends EvalBytecodeException {
    private final Delay delay;

    public NoCallException(Delay delay) {
        super("no call " + delay);
        this.delay = delay;
    }

}
