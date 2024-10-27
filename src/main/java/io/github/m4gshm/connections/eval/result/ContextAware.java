package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.Component.ComponentKey;
import org.apache.bcel.classfile.Method;

import static io.github.m4gshm.connections.model.Component.ComponentKey.newComponentKey;

public interface ContextAware {

    Method getMethod();

    Component getComponent();

    default ComponentKey getComponentKey() {
        return newComponentKey(getComponent());
    }

    default Class<?> getComponentType() {
        return getComponent().getType();
    }
}
