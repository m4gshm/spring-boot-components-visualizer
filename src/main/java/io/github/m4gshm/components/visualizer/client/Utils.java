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

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.eval.bytecode.Eval.toParameters;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
public class Utils {
    static Collection<List<Result>> resolveInvokeParameters(Eval eval, DelayInvoke invoke, Component component,
                                                            String methodName, Resolver resolver) {

        var parameters = toParameters(invoke.getObject(), invoke.getArguments());
        var resolvedVariant = eval.withArgumentsStream().map(eval2 -> {
            try {
                return eval2.resolveInvokeParameters(invoke, parameters, resolver);
            } catch (NotInvokedException e) {
                log.info("no call variants for {} inside {}", eval.getMethod().getName(), component.getName());
                return List.<List<Result>>of();
            }
        }).flatMap(Collection::stream).collect(toList());
        return resolvedVariant;
    }

    public static BootstrapMethods getBootstrapMethods(JavaClass javaClass) {
        return javaClass.getAttribute(ATTR_BOOTSTRAP_METHODS);
    }
}
