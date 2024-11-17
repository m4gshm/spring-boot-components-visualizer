package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.result.Constant;
import io.github.m4gshm.components.visualizer.eval.result.Delay;
import io.github.m4gshm.components.visualizer.eval.result.DelayInvoke;
import io.github.m4gshm.components.visualizer.eval.result.Variable;

public interface EvalVisitor {
    EvalVisitor NOOP = new EvalVisitor() {
    };

    default void visit(DelayInvoke delay) {

    }

    default void visit(Delay delay) {

    }

    default void visit(Constant constant) {

    }

    default void visit(Variable variable) {

    }
}
