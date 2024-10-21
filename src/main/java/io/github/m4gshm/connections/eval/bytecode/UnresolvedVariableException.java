package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Variable;

public class UnresolvedVariableException extends UnresolvedResultException {

    public UnresolvedVariableException(String message, Variable result) {
        super(message, result);
    }

    @Override
    public Variable getResult() {
        return (Variable) super.getResult();
    }
}
