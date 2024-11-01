package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.result.Multiple;
import lombok.Getter;

@Getter
public class IllegalMultipleResultsInvocationException extends EvalException {
    private final Multiple variable;

    public IllegalMultipleResultsInvocationException(Multiple multiple) {
        super("illegal getValue invocation " + multiple);
        this.variable = multiple;
    }
}
