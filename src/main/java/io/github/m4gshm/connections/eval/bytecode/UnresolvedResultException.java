package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Result;
import lombok.Getter;

@Getter
public class UnresolvedResultException extends EvalBytecodeException {
    private final Result result;

    public UnresolvedResultException(String message, Exception cause, Result result) {
        super(message, cause);
        this.result = result;
    }

    public UnresolvedResultException(Exception cause, Result result) {
        super(cause);
        this.result = result;
    }

    public UnresolvedResultException(String message, Result result) {
        super(message + " " + result);
        this.result = result;
    }
}
