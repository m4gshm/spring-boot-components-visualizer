package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Multiple;
import lombok.Getter;

@Getter
public class IllegalMultipleResultsInvocationException extends EvalBytecodeException {
    private final Multiple variable;

    public IllegalMultipleResultsInvocationException(Multiple multiple) {
        super("illegal getValue invocation " + multiple);
        this.variable = multiple;
    }
}
