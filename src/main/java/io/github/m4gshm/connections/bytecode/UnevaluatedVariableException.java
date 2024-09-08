package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.MethodArgument;
import lombok.Getter;

@Getter
public class UnevaluatedVariableException extends EvalBytecodeException {
    private final MethodArgument variable;

    public UnevaluatedVariableException(MethodArgument variable) {
        super("unresolved " + variable);
        this.variable = variable;
    }
}
