package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import lombok.Getter;

@Getter
public class UnevaluatedResultException extends EvalBytecodeException {
    private final Result result;

    public UnevaluatedResultException(String message, Result result) {
        super(message + " " + result);
        this.result = result;
    }
}
