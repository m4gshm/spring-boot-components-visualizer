package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Result;
import lombok.Getter;

@Getter
public class UnresolvedResultException extends EvalBytecodeException {
    private final Result result;

    public UnresolvedResultException(String message, Result result, Exception cause) {
        super(message + " " + result, cause);
        this.result = result;
    }

    public UnresolvedResultException(Result result, Exception cause) {
        super(cause);
        this.result = result;
    }

    public UnresolvedResultException(String message, Result result) {
        super(message + " " + result);
        this.result = result;
    }

    public UnresolvedResultException(UnresolvedResultException e) {
        this(e.getResult(), e);
    }
}
