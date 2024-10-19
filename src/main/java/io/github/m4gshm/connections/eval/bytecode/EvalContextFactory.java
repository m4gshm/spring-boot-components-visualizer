package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.model.Component;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import static io.github.m4gshm.connections.client.JmsOperationsUtils.getBootstrapMethods;

@FunctionalInterface
public interface EvalContextFactory {
    default EvalBytecode getEvalContext(Component component, JavaClass javaClass, Method method) {
        return getEvalContext(component, method, getBootstrapMethods(javaClass));
    }

    EvalBytecode getEvalContext(Component component, Method method, BootstrapMethods bootstrapMethods);
}
