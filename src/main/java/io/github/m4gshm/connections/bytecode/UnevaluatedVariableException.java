package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable;
import lombok.Getter;

@Getter
public class UnevaluatedVariableException extends EvalBytecodeException {
    private final Variable variable;

    public UnevaluatedVariableException(Variable variable) {
        super("unresolved " + variable);
        this.variable = variable;
    }
}
