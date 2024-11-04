package io.github.m4gshm.components.visualizer.eval.bytecode;
import lombok.var;

import io.github.m4gshm.components.visualizer.model.Component;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import static io.github.m4gshm.components.visualizer.client.Utils.getBootstrapMethods;

@FunctionalInterface
public interface EvalContextFactory {
    default Eval getEvalContext(Component component, JavaClass javaClass, Method method) {
        return getEvalContext(component, method, getBootstrapMethods(javaClass));
    }

    Eval getEvalContext(Component component, Method method, BootstrapMethods bootstrapMethods);
}
