package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.model.Component;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import static io.github.m4gshm.connections.client.Utils.getBootstrapMethods;

@FunctionalInterface
public interface EvalContextFactory {
    default Eval getEvalContext(Component component, JavaClass javaClass, Method method) {
        return getEvalContext(component, method, getBootstrapMethods(javaClass));
    }

    Eval getEvalContext(Component component, Method method, BootstrapMethods bootstrapMethods);
}
