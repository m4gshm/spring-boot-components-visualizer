package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.model.Component;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Method;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@FieldDefaults(makeFinal = true)
public class EvalContextFactoryCacheImpl implements EvalContextFactory {
    EvalContextFactory evalContextFactory;
    Map<Key, EvalBytecode> cache;

    @Override
    public EvalBytecode getEvalContext(Component component, Method method, BootstrapMethods bootstrapMethods) {
        return cache.computeIfAbsent(
                new Key(component, method),
                k -> evalContextFactory.getEvalContext(component, method, bootstrapMethods)
        );
    }

    @Data
    @FieldDefaults(makeFinal = true)
    private static class Key {
        Component component;
        Method method;
    }
}
