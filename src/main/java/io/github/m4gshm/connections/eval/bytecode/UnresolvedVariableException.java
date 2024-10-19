package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Result;

public class UnresolvedVariableException extends UnresolvedResultException {

    public UnresolvedVariableException(String message, Result result) {
        super(message, result);
    }
}
