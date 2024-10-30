package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.EvalException;

public interface Resolver {
    Result resolve(Result unresolved, EvalException cause);
}
