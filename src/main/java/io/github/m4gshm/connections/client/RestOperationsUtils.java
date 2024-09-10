package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode;
import io.github.m4gshm.connections.bytecode.EvalBytecode.MethodArgumentResolver;
import io.github.m4gshm.connections.bytecode.EvalBytecode.MethodReturnResolver;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.MethodArgument;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.constant;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class RestOperationsUtils {
    public static List<HttpMethod> extractRestOperationsUris(Component component,
                                                             Map<Component, List<Component>> dependencyToDependentMap,
                                                             MethodArgumentResolver methodArgumentResolver,
                                                             MethodReturnResolver methodReturnResolver,
                                                             Function<Result, Result> unevaluatedHandler) {
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
                        constantPoolGen, bootstrapMethods, method, methodArgumentResolver,
                        methodReturnResolver, unevaluatedHandler)
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
                                                       MethodArgumentResolver methodArgumentResolver,
                                                       MethodReturnResolver methodReturnResolver,
                                                       Function<Result, Result> unevaluatedHandler) {
        log.trace("extractHttpMethod componentName {}", component.getName());
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);

        var eval = new EvalBytecode(component, dependencyToDependentMap, constantPoolGen,
                bootstrapMethods, method, methodArgumentResolver, methodReturnResolver);

        var evalArguments = eval.evalArguments(instructionHandle, instruction, null);
        var argumentsArguments = evalArguments.getArguments();

        var path = argumentsArguments.get(0);
        var resolvedPaths = eval.resolve(path, unevaluatedHandler);
        var paths = resolveVariableStrings(resolvedPaths, null);

        final List<String> httpMethods;
        if ("exchange".equals(methodName)) {
            var httpMethodArg = argumentsArguments.get(1);
            var resolvedHttpMethodResults = eval.resolve(httpMethodArg, unevaluatedHandler);
            httpMethods = resolveVariableStrings(resolvedHttpMethodResults, unevaluatedHandler);
        } else {
            httpMethods = List.of(getHttpMethod(methodName));
        }

        return httpMethods.stream().flatMap(m -> paths.stream().map(p -> HttpMethod.builder().method(m).path(p).build()))
                .collect(toList());
    }

    public static Result stringifyVariable(Result result) {
        if (result instanceof MethodArgument) {
            var methodArgument = (MethodArgument) result;
            var localVariable = methodArgument.getLocalVariable();
            var type = Type.getType(localVariable.getSignature());
            if (String.class.getName().equals(type.getClassName())) {
                var name = localVariable.getName();
                return constant("{" + name + "}", methodArgument.getLastInstruction());
            }
        }
        return result;
    }

    private static List<String> resolveVariableStrings(Collection<Result> results, Function<Result, Result> unevaluatedHandler) {
        return results.stream().flatMap(result -> resolveVariableStrings(result, unevaluatedHandler).stream()).distinct().collect(toList());
    }

    private static List<String> resolveVariableStrings(Result result, Function<Result, Result> unevaluatedHandler) {
        return List.of(String.valueOf(result.getValue(unevaluatedHandler)));
//        return (result instanceof Multiple
//                ? ((Multiple) result).getResults().stream()
//                : Stream.of(result)).map(result1 -> {
//            if (result1 instanceof MethodArgument) {
//                var methodArgument = (MethodArgument) result1;
//                var localVariable = methodArgument.getLocalVariable();
//                var name = localVariable.getName();
//                return "{" + name + "}";
//            } else if (result1 instanceof Illegal) {
//                var illegal = (Illegal) result1;
//                return "ERROR:" + illegal.getStatus().stream() + "," + illegal.getSource();
//            } else {
//                return String.valueOf(result1.getValue());
//            }
//        }).distinct().collect(toList());
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
