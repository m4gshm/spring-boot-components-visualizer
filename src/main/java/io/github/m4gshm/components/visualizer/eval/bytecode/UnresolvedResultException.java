package io.github.m4gshm.components.visualizer.eval.bytecode;
import lombok.var;

import io.github.m4gshm.components.visualizer.eval.result.Result;
import lombok.Getter;

@Getter
public class UnresolvedResultException extends EvalException {
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
