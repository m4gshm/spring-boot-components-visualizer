package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import lombok.Getter;

@Getter
public class UnresolvedResultException extends EvalBytecodeException {
    private final Result result;

    public UnresolvedResultException(String message, Result result) {
        super(message + " " + result);
        this.result = result;
    }

    public UnresolvedResultException(UnresolvedResultException e) {
        super(e);
        this.result = e.getResult();
    }
}
