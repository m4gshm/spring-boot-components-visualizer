package io.github.m4gshm.components.visualizer.client;

import io.github.m4gshm.components.visualizer.eval.bytecode.EvalContextFactoryImpl;
import io.github.m4gshm.components.visualizer.eval.bytecode.StringifyResolver;
import io.github.m4gshm.components.visualizer.eval.result.DelayInvoke;
import io.github.m4gshm.components.visualizer.eval.result.Multiple;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.model.Component;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.INVOKEINTERFACE;
import org.apache.bcel.generic.InstructionHandle;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.github.m4gshm.components.visualizer.eval.bytecode.Eval.CallCache.noCallCache;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.byName;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.getClassAndMethodSources;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InstructionUtils.Filter.byType;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InstructionUtils.instructions;
import static io.github.m4gshm.components.visualizer.eval.bytecode.StringifyResolver.Level.full;
import static io.github.m4gshm.components.visualizer.eval.bytecode.StringifyResolver.Level.varOnly;
import static io.github.m4gshm.components.visualizer.eval.bytecode.StringifyResolver.newStringify;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SwitchCaseArgumentsExtractingTest {

    public static final String SWITCH_CASE_METHOD = "switchCaseMethod";
    public static final String SERVICE_METHOD = "method";
    public static final String TERNARY_OPERATOR_METHOD = "ternaryOperatorMethod";
    public final Service service = (arg1, arg2, arg3) -> "";
    public final Service2 service2 = (arg1) -> arg1;
    private final EvalContextFactoryImpl evalContextFactory = new EvalContextFactoryImpl(noCallCache(),
            component -> List.of(), aClass -> List.of(), null);
    private final String url = "https://localhost";

    private static List<List<Object>> getValueVariants(Collection<List<Result>> parameterVariants) {
        return parameterVariants.stream().map(parameters -> {
            return parameters.stream().map(param -> param.getValue()).collect(toList());
        }).collect(toList());
    }

    private static Predicate<InstructionHandle> serviceMethod(ConstantPoolGen constantPoolGen,
                                                              Class<?> serviceClass, String methodName) {
        return byType(INVOKEINTERFACE.class, (h, instruction) -> {
            var name = instruction.getName(constantPoolGen);
            var className = instruction.getClassName(constantPoolGen);
            return methodName.equals(name) && className.equals(serviceClass.getName());
        });
    }

    @Test
    public void switchCaseArgumentsExtractingTest() {
        var classMethod = getClassAndMethodSources(SwitchCaseArgumentsExtractingTest.class, byName(SWITCH_CASE_METHOD))
                .findFirst().get();
        var method = classMethod.getValue();

        var evalContext = evalContextFactory.getEvalContext(Component.builder().bean(this).build(), classMethod.getKey(), method);
        var constantPoolGen = new ConstantPoolGen(method.getConstantPool());

        var handle = instructions(method).filter(serviceMethod(constantPoolGen, Service.class, SERVICE_METHOD)).findFirst().get();

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

    @Test
    public void ternaryOperatorMethodArgumentsExtractingTest() {
        var classMethod = getClassAndMethodSources(SwitchCaseArgumentsExtractingTest.class, byName(TERNARY_OPERATOR_METHOD))
                .findFirst().get();
        var method = classMethod.getValue();

        var evalContext = evalContextFactory.getEvalContext(Component.builder().bean(this).build(), classMethod.getKey(), method);
        var constantPoolGen = new ConstantPoolGen(method.getConstantPool());

        var handle = instructions(method).filter(serviceMethod(constantPoolGen, Service2.class, SERVICE_METHOD)).findFirst().get();
        var eval = evalContext.eval(handle);

        var resolver = newStringify(varOnly, true);

        var arguments = ((DelayInvoke) eval).getArguments();
        var resolved = evalContext.resolve(eval, resolver);
        var results = ((Multiple) resolved).getResults();

        assertEquals("https://localhost/v2/{path}:443", results.get(0).getValue());
        assertEquals("https://localhost/v1:443", results.get(1).getValue());
        assertEquals("https://localhost/v2/{path}:80", results.get(2).getValue());
        assertEquals("https://localhost/v1:80", results.get(3).getValue());
    }

    private void assertExpectedVariant(List<Object> expected, int i, List<List<Object>> valueVariants) {
        var actual = valueVariants.get(i);
        assertEquals(expected, actual, "unexpected result of variant " + i);
    }

    private List<Object> expected(Object... args) {
        return asList(args);
    }

    public void switchCaseMethod(String condition1, String condition2) {
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
        service.method(arg1, condition2 != null ? 2 : 22, arg3);
    }

    public void ternaryOperatorMethod(boolean secure, String path) {
        var fullPath = this.url + (path == null ? "/v1" : "/v2/" + path) + ":" + (secure ? 443 : 80);
        service2.method(fullPath);
    }

    public interface Service {
        String method(String arg1, int arg2, Long arg3);
    }

    public interface Service2 {
        String method(String arg1);
    }
}
