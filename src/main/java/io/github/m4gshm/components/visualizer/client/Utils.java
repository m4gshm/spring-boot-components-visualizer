package io.github.m4gshm.components.visualizer.client;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.NotInvokedException;
import io.github.m4gshm.components.visualizer.eval.result.DelayInvoke;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;

import java.util.List;

import static io.github.m4gshm.components.visualizer.eval.bytecode.Eval.toParameters;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
public class Utils {
    static List<List<Result>> resolveInvokeParameters(Eval eval, DelayInvoke invoke, Component component,
                                                      String methodName, Resolver resolver) {
        var parameters = toParameters(invoke.getObject(), invoke.getArguments());
        try {
            return eval.resolveInvokeParameters(invoke, parameters, resolver);
        } catch (NotInvokedException e) {
            log.info("no call variants for {} inside {}", eval.getMethod().getName(), component.getName());
            return List.of();
        }
    }

    public static BootstrapMethods getBootstrapMethods(JavaClass javaClass) {
        return javaClass.getAttribute(ATTR_BOOTSTRAP_METHODS);
    }
}
