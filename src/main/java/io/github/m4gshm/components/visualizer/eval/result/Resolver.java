package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalException;

public interface Resolver {
    Result resolve(Result unresolved, EvalException cause, Eval eval);
    default Result resolve(Result unresolved, EvalException cause) {
        return resolve(unresolved, cause, unresolved.getEval());
    }
}
