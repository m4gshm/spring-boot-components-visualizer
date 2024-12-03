package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.model.Component;
import io.github.m4gshm.components.visualizer.model.Component.ComponentKey;
import org.apache.bcel.classfile.Method;

import static io.github.m4gshm.components.visualizer.model.Component.ComponentKey.newComponentKey;

public interface ContextAware {

    Eval getEval();

    default Method getMethod() {
        return getEval().getMethod();
    }

    default Component getComponent() {
        return getEval().getComponent();
    }

    default ComponentKey getComponentKey() {
        return newComponentKey(getComponent());
    }

    default Class<?> getComponentType() {
        var component = getComponent();
        return component != null ? component.getType() : null;
    }
}
