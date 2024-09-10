package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.MethodArgument;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Multiple;
import lombok.Getter;

@Getter
public class IncorrectMultipleResultsInvocationException extends EvalBytecodeException {
    private final Multiple variable;

    public IncorrectMultipleResultsInvocationException(Multiple multiple) {
        super("illegal getValue invocation " + multiple);
        this.variable = multiple;
    }
}
