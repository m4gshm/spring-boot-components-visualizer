package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable;
import lombok.Getter;

@Getter
public class UnevaluatedVariableException extends UnevaluatedResultException {

    public UnevaluatedVariableException(String message, Variable variable) {
        super(message, variable);
    }

    public Variable getVariable() {
        return (Variable) getResult();
    }
}
