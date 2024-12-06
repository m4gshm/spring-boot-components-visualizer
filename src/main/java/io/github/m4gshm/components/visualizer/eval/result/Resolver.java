package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalException;

public interface Resolver {
    default Result resolve(Result unresolved, EvalException cause, Eval eval) {
        return withEval(eval).resolve(unresolved, cause);
    }

    Resolver withEval(Eval eval);

    Result resolve(Result unresolved, EvalException cause);
}
