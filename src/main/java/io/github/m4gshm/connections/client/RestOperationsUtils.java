package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.eval.bytecode.EvalBytecode;
import io.github.m4gshm.connections.eval.bytecode.EvalBytecode.CallCacheKey;
import io.github.m4gshm.connections.eval.result.Result;
import io.github.m4gshm.connections.eval.result.DelayInvoke;
import io.github.m4gshm.connections.eval.bytecode.NoCallException;
import io.github.m4gshm.connections.eval.bytecode.StringifyUtils;
import io.github.m4gshm.connections.model.CallPoint;
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
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class RestOperationsUtils {
    public static List<HttpMethod> extractRestOperationsUris(Component component,
                                                             Map<Component, List<Component>> dependencyToDependentMap,
                                                             Map<Component, List<CallPoint>> callPointsCache,
                                                             Map<CallCacheKey, Result> callCache) {
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
                        constantPoolGen, bootstrapMethods, method, callPointsCache, callCache)
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
                                                       Map<Component, List<CallPoint>> callPointsCache,
                                                       Map<CallCacheKey, Result> callCache) {
        var instructionText = instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
        log.info("extractHttpMethod component {}, method {}, invoke {}", component.getName(), method.toString(),
                instructionText);

        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var methodName = instruction.getMethodName(constantPoolGen);
        var eval = new EvalBytecode(component, dependencyToDependentMap, constantPoolGen, bootstrapMethods, method,
                callPointsCache, callCache);
        var result = (DelayInvoke) eval.eval(instructionHandle);
        var variants = resolveInvokeParameters(eval, result, component, methodName);

        var results = variants.stream().flatMap(variant -> {
            var pathArg = variant.get(1);
            return getHttpMethodStream(variant, methodName, pathArg);
        }).collect(toList());
        return results;
    }

    private static Stream<HttpMethod> getHttpMethodStream(List<Result> variant, String methodName, Result pathArg) {
        try {
            final List<String> httpMethods;
            if ("exchange".equals(methodName)) {
                var resolvedHttpMethodArg = variant.get(2);
                httpMethods = getStrings(resolvedHttpMethodArg.getValue(StringifyUtils::stringifyUnresolved));
            } else {
                httpMethods = List.of(getHttpMethod(methodName));
            }
            var paths = getStrings(pathArg.getValue(StringifyUtils::stringifyUnresolved));

            return paths.stream().flatMap(path -> httpMethods.stream()
                    .map(httpMethod -> HttpMethod.builder().method(httpMethod).path(path).build()));
        } catch (NoCallException e) {
            //log
            return Stream.empty();
        }
    }

    static List<List<Result>> resolveInvokeParameters(EvalBytecode eval, DelayInvoke invoke, Component component, String methodName) {
        List<List<Result>> variants;
        try {
            variants = eval.resolveInvokeParameters(invoke, invoke.getObject(), invoke.getArguments(),
                    StringifyUtils::stringifyUnresolved, true, null);
        } catch (NoCallException e) {
            log.info("no call variants for {} inside {}", methodName, component.getName());
            variants = List.of();
        }
        return variants;
    }

    private static List<String> getStrings(List<Object> values) {
        return values.stream()
                .map(value -> value != null ? value.toString() : null)
                .collect(toList());
    }

    private static List<String> resolveVariableStrings(EvalBytecode eval, Collection<Result> results) {
        return results.stream()
                .flatMap(r -> eval.resolveExpand(r, StringifyUtils::stringifyUnresolved).stream())
                .flatMap(result -> result.getValue(StringifyUtils::stringifyUnresolved).stream())
                .map(String::valueOf)
                .distinct()
                .collect(toList());
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
