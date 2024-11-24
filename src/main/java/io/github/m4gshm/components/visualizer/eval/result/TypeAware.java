package io.github.m4gshm.components.visualizer.eval.result;

import org.apache.bcel.generic.Type;

public interface TypeAware {
    static Type getType(Result result) {
        if (result instanceof TypeAware) {
            return ((TypeAware) result).getType();
        } else if (result instanceof Duplicate) {
            var dup = (Duplicate) result;
            return getType(dup.getOnDuplicate());
        }
        return null;
    }

    Type getType();
}
