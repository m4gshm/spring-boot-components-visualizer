package io.github.m4gshm.components.visualizer.client;

import io.github.m4gshm.components.visualizer.eval.bytecode.EvalContextFactoryImpl;
import io.github.m4gshm.components.visualizer.eval.result.DelayInvoke;
import io.github.m4gshm.components.visualizer.eval.result.Multiple;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.model.Component;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.INVOKEINTERFACE;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static io.github.m4gshm.components.visualizer.eval.bytecode.Eval.CallCache.noCallCache;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.*;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SwitchCaseArgumentsExtractingTest {

    private final EvalContextFactoryImpl evalContextFactory = new EvalContextFactoryImpl(noCallCache(),
            component -> List.of(), aClass -> List.of(), null);
    public Service service = (arg1, arg2, arg3) -> "";

    private static List<List<Object>> getValueVariants(Collection<List<Result>> parameterVariants) {
        return parameterVariants.stream().map(parameters -> {
            return parameters.stream().map(param -> param.getValue()).collect(Collectors.toList());
        }).collect(Collectors.toList());
    }

    @Test
    public void test() {
        var classMethod = getClassAndMethodSources(SwitchCaseArgumentsExtractingTest.class, byName("method"))
                .findFirst().get();

        var method = classMethod.getValue();

        var evalContext = evalContextFactory.getEvalContext(Component.builder().bean(this).build(), classMethod.getKey(), method);
        var constantPoolGen = new ConstantPoolGen(method.getConstantPool());

        var handle = instructions(method).filter(h -> {
            var instruction = h.getInstruction();
            if (instruction instanceof INVOKEINTERFACE) {
                var invokeVirtual = (INVOKEINTERFACE) instruction;
                var name = invokeVirtual.getName(constantPoolGen);
                var className = invokeVirtual.getClassName(constantPoolGen);
                return "anyMethod".equals(name) && className.equals(Service.class.getName());
            }
            return false;
        }).findFirst().get();

        var eval = (Multiple) evalContext.eval(handle);
        var delayInvoke = (DelayInvoke) eval.getResults().get(0);
        var delayInvoke2 = (DelayInvoke) eval.getResults().get(1);

        var parameterVariants = evalContext.resolveInvokeParameters(delayInvoke, null);
        var valueVariants = getValueVariants(parameterVariants);

        var parameterVariants2 = evalContext.resolveInvokeParameters(delayInvoke2, null);
        var valueVariants2 = getValueVariants(parameterVariants2);

        assertExpectedVariant(expected(service, "arg13", 22, 3L), 0, valueVariants);
        assertExpectedVariant(expected(service, "arg11", 22, 3L), 1, valueVariants);
        assertExpectedVariant(expected(service, "arg12", 22, 32L), 2, valueVariants);

        assertExpectedVariant(expected(service, "arg13", 2, 3L), 0, valueVariants2);
        assertExpectedVariant(expected(service, "arg11", 2, 3L), 1, valueVariants2);
        assertExpectedVariant(expected(service, "arg12", 2, 32L), 2, valueVariants2);
    }

    private void assertExpectedVariant(List<Object> expected, int i, List<List<Object>> valueVariants) {
        var actual = valueVariants.get(i);
        assertEquals(expected, actual, "unexpected result of variant " + i);
    }

    private List<Object> expected(Object... args) {
        return asList(args);
    }

    public void method(String condition1, String condition2) {
        String arg1;
        long arg3 = 3L;
        switch (condition1) {
            case "1":
                arg1 = "arg11";
                break;
            case "2":
                arg1 = "arg12";
                arg3 = 32L;
                break;
            default:
                arg1 = "arg13";
        }
        service.anyMethod(arg1, condition2 != null ? 2 : 22, arg3);
    }


    interface Service {
        String anyMethod(String arg1, int arg2, Long arg3);
    }
}
