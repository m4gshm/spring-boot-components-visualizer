package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.CallPointsHelper;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@FieldDefaults(makeFinal = true)
public class EvalContextFactoryCacheImpl extends EvalContextFactoryImpl {
    ConcurrentMap<Key, Eval> emptyCache = new ConcurrentHashMap<>();
    ConcurrentMap<Key, Eval> argVariantsCache = new ConcurrentHashMap<>();

    public EvalContextFactoryCacheImpl(Eval.CallCache callCache, DependentProvider dependentProvider,
                                       CallPointsHelper.CallPointsProvider callPointsProvider, Resolver resolver) {
        super(callCache, dependentProvider, callPointsProvider, resolver);
    }

    @Override
    public Eval getEvalContext(Component component, JavaClass javaClass, Method method, BootstrapMethods bootstrapMethods) {
        var key = new Key(component, method);
        var empty = emptyCache.get(key);

        var fullInit = argVariantsCache.get(key);
        if (fullInit != null) {
            return fullInit;
        } else if (empty != null) {
            return empty;
        }

        return super.getEvalContext(component, javaClass, method, bootstrapMethods);
    }

    @Override
    protected Eval newEmptyEvalContext(Component component, JavaClass javaClass, Method method, BootstrapMethods bootstrapMethods) {
        var eval = super.newEmptyEvalContext(component, javaClass, method, bootstrapMethods);
        emptyCache.put(new Key(component, method), eval);
        return eval;
    }

    @Override
    protected Eval withArgumentsVariants(Component component, Method method, Eval emptyEval) {
        var eval = super.withArgumentsVariants(component, method, emptyEval);
        argVariantsCache.put(new Key(component, method), eval);
        return eval;
    }

    @Data
    @FieldDefaults(makeFinal = true)
    public static class Key {
        Component component;
        Method method;
    }
}
