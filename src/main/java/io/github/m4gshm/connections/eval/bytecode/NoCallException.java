package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Delay;
import lombok.Getter;

@Getter
public class NoCallException extends EvalBytecodeException {
    private final Delay delay;

    public NoCallException(Delay delay) {
        super("no call " + delay);
        this.delay = delay;
    }

}
