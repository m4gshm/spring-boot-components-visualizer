package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.model.Component;
import org.apache.bcel.classfile.Method;

import java.util.Objects;

public interface ContextAware {

    static boolean equals(ContextAware contextAware1, ContextAware contextAware2) {
        return Objects.equals(contextAware1.getComponent(), contextAware2.getComponent())
                && Objects.equals(contextAware1.getMethod(), contextAware2.getMethod());
    }

    static int hashCode(ContextAware contextAware) {
        return Objects.hash(contextAware.getComponent(), contextAware.getMethod());
    }

    Method getMethod();

    Component getComponent();

    default Class<?> getComponentType() {
        return getComponent().getType();
    }
}
