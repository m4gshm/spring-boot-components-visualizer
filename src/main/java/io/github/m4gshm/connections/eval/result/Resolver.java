package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.EvalBytecodeException;

public interface Resolver {
    Result resolve(Result unresolved, EvalBytecodeException cause);
}
