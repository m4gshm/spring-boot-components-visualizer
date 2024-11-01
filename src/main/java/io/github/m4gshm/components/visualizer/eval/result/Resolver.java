package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.EvalException;

public interface Resolver {
    Result resolve(Result unresolved, EvalException cause);
}
