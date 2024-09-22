package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable;
import io.github.m4gshm.connections.bytecode.InvokeDynamicUtils;
import io.github.m4gshm.connections.bytecode.MethodInfo;
import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.constant;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.getClassByName;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethodAndArguments;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getInvokeDynamicUsedMethodInfo;
import static io.github.m4gshm.connections.client.JmsOperationsUtils.withExpected;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class RestOperationsUtils {
    public static List<HttpMethod> extractRestOperationsUris(Component component,
                                                             Map<Component, List<Component>> dependencyToDependentMap,
                                                             BiFunction<Result, Type, Result> unevaluatedHandler,
                                                             Map<Component, List<CallPoint>> callPointsCache) {
        var javaClasses = getClassHierarchy(component.getType());
        return javaClasses.stream().flatMap(javaClass -> {
            var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
            var methods = javaClass.getMethods();
            var bootstrapMethods = javaClass.<BootstrapMethods>getAttribute(ATTR_BOOTSTRAP_METHODS);
            return stream(methods).flatMap(method -> instructionHandleStream(method.getCode()).map(instructionHandle -> {
                var instruction = instructionHandle.getInstruction();
                var expectedType = instruction instanceof INVOKEVIRTUAL ? RestTemplate.class :
                        instruction instanceof INVOKEINTERFACE ? RestOperations.class : null;
                var match = expectedType != null && isClass(expectedType, ((InvokeInstruction) instruction), constantPoolGen);
                return match
                        ? extractHttpMethods(component, dependencyToDependentMap, instructionHandle,
                        constantPoolGen, bootstrapMethods, method,
                        unevaluatedHandler, callPointsCache)
                        : null;
            }).filter(Objects::nonNull).flatMap(Collection::stream)).filter(Objects::nonNull);
        }).collect(toList());
    }

    static boolean isClass(Class<?> expectedClass, InvokeInstruction instruction, ConstantPoolGen constantPoolGen) {
        var className = instruction.getClassName(constantPoolGen);
        return expectedClass.getName().equals(className);
    }

    private static List<HttpMethod> extractHttpMethods(Component component,
                                                       Map<Component, List<Component>> dependencyToDependentMap,
                                                       InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
                                                       BootstrapMethods bootstrapMethods, Method method,
                                                       BiFunction<Result, Type, Result> unevaluatedHandler,
                                                       Map<Component, List<CallPoint>> callPointsCache) {
        var instructionText = instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
        log.info("extractHttpMethod component {}, method {}, invoke {}", component.getName(), method.toString(),
                instructionText);
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);

        var eval = new EvalBytecode(component, dependencyToDependentMap, constantPoolGen,
                bootstrapMethods, method, callPointsCache);

        var argumentTypes = instruction.getArgumentTypes(eval.getConstantPoolGen());
        var evalArguments = eval.evalArguments(instructionHandle, argumentTypes, withExpected(unevaluatedHandler, String.class));
        var argumentsArguments = evalArguments.getArguments();

        var path = argumentsArguments.get(0);
        var resolvedPaths = eval.resolve(path, withExpected(unevaluatedHandler, String.class));
        var paths = resolveVariableStrings(resolvedPaths, unevaluatedHandler);

        final List<String> httpMethods;
        if ("exchange".equals(methodName)) {
            var httpMethodArg = argumentsArguments.get(1);
            var resolvedHttpMethodResults = eval.resolve(
                    httpMethodArg, withExpected(unevaluatedHandler, org.springframework.http.HttpMethod.class)
            );
            httpMethods = resolveVariableStrings(resolvedHttpMethodResults, unevaluatedHandler);
        } else {
            httpMethods = List.of(getHttpMethod(methodName));
        }

        return httpMethods.stream().flatMap(m -> paths.stream().map(p -> HttpMethod.builder().method(m).path(p).build()))
                .collect(toList());
    }

    public static Result stringifyVariable(Result result) {
        if (result instanceof Result.Stub) {
            var s = (Result.Stub) result;
            var stubbed = s.getStubbed();
            if (stubbed != s) {
                return stringifyVariable(stubbed);
            }
        } else if (result instanceof Variable) {
            var variable = (Variable) result;
            var name = variable.getName();
            var classByName = getClassByName(variable.getType().getClassName());
            if (CharSequence.class.isAssignableFrom(classByName)) {
                return constant("{" + name + "}", variable.getLastInstruction(), variable.getEvalContext(), result);
            }
        } else if (result instanceof Result.Delay) {
            var delay = (Result.Delay)result;
            var instructionHandle = delay.getFirstInstruction();
            var instruction = instructionHandle.getInstruction();
            if (instruction instanceof INVOKEDYNAMIC) {
                var constantPoolGen = delay.getEvalContext().getConstantPoolGen();
                var invokedynamic = (INVOKEDYNAMIC) instruction;
                var methodName = invokedynamic.getMethodName(constantPoolGen);
                var evalContext = ((Result.Delay) result).getEvalContext();

                var methodInfo = getInvokeDynamicUsedMethodInfo(invokedynamic, evalContext.getBootstrapMethods(),
                        evalContext.getConstantPoolGen());

                var bootstrapMethodAndArguments = getBootstrapMethodAndArguments(invokedynamic, evalContext.getBootstrapMethods(), evalContext.getConstantPoolGen());

                if ("makeConcatWithConstants".equals(methodName)) {

                }

            }
        }
        return result;
    }

    private static List<String> resolveVariableStrings(Collection<Result> results, BiFunction<Result, Type, Result> unevaluatedHandler) {
        return results.stream().flatMap(result -> resolveVariableStrings(result, unevaluatedHandler).stream())
                .distinct().collect(toList());
    }

    private static List<String> resolveVariableStrings(Result result, BiFunction<Result, Type, Result> unevaluatedHandler) {
        return List.of(String.valueOf(result.getValue(withExpected(unevaluatedHandler, String.class))));
    }

    private static String getHttpMethod(String methodName) {
        return methodName.startsWith("get") ? "GET"
                : methodName.startsWith("post") ? "POST"
                : methodName.startsWith("put") ? "PUT"
                : methodName.startsWith("delete") ? "DELETE"
                : methodName.startsWith("head") ? "HEAD"
                : methodName.startsWith("patch") ? "PATCH"
                : methodName.startsWith("exchange") ? "EXCHANGE"
                : methodName.startsWith("execute") ? "EXECUTE"
                : "UNDEFINED";
    }
}
