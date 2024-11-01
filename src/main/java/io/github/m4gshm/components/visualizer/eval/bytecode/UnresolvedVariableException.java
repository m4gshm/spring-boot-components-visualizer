package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.result.Variable;

public class UnresolvedVariableException extends UnresolvedResultException {

    public UnresolvedVariableException(String message, Variable result) {
        super(message, result);
    }

    @Override
    public Variable getResult() {
        return (Variable) super.getResult();
    }
}
