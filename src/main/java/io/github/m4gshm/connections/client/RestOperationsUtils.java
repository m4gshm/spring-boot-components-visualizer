package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.eval.bytecode.*;
import io.github.m4gshm.connections.eval.result.Result;
import io.github.m4gshm.connections.eval.result.DelayInvoke;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.client.Utils.resolveInvokeParameters;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static io.github.m4gshm.connections.eval.bytecode.StringifyUtils.stringifyUnresolved;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class RestOperationsUtils {
    public static List<HttpMethod> extractRestOperationsUris(Component component, Map<CallCacheKey, Result> callCache,
                                                             EvalContextFactory evalContextFactory) {
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
                        ? extractHttpMethods(component, instructionHandle,
                        constantPoolGen, bootstrapMethods, method, callCache, evalContextFactory)
                        : null;
            }).filter(Objects::nonNull).flatMap(Collection::stream)).filter(Objects::nonNull);
        }).collect(toList());
    }

    static boolean isClass(Class<?> expectedClass, InvokeInstruction instruction, ConstantPoolGen constantPoolGen) {
        var className = instruction.getClassName(constantPoolGen);
        return expectedClass.getName().equals(className);
    }

    private static List<HttpMethod> extractHttpMethods(Component component,
                                                       InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
                                                       BootstrapMethods bootstrapMethods, Method method,
                                                       Map<CallCacheKey, Result> callCache, EvalContextFactory evalContextFactory) {
        var instructionText = instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
        log.info("extractHttpMethod component {}, method {}, invoke {}", component.getName(), method.toString(),
                instructionText);

        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var methodName = instruction.getMethodName(constantPoolGen);
        var eval = evalContextFactory.getEvalContext(component, method, bootstrapMethods);
        var result = (DelayInvoke) eval.eval(instructionHandle, callCache);
        var variants = resolveInvokeParameters(eval, result, component, methodName, callCache);

        return variants.stream().flatMap(variant -> {
            var pathArg = variant.get(1);
            return getHttpMethodStream(variant, methodName, pathArg, callCache);
        }).collect(toList());
    }

    private static Stream<HttpMethod> getHttpMethodStream(List<Result> variant, String methodName, Result pathArg,
                                                          Map<CallCacheKey, Result> callCache) {
        try {
            final List<String> httpMethods;
            if ("exchange".equals(methodName)) {
                var resolvedHttpMethodArg = variant.get(2);
                httpMethods = getStrings(resolvedHttpMethodArg.getValue((current, ex) -> stringifyUnresolved(current, ex, callCache)));
            } else {
                httpMethods = List.of(getHttpMethod(methodName));
            }
            var paths = getStrings(pathArg.getValue((current, ex) -> stringifyUnresolved(current, ex, callCache)));

            return paths.stream().flatMap(path -> httpMethods.stream()
                    .map(httpMethod -> HttpMethod.builder().method(httpMethod).path(path).build()));
        } catch (NotInvokedException e) {
            //log
            return Stream.empty();
        }
    }

    private static List<String> getStrings(List<Object> values) {
        return values.stream()
                .map(value -> value != null ? value.toString() : null)
                .collect(toList());
    }

    private static List<String> resolveVariableStrings(Eval eval, Collection<Result> results, Map<CallCacheKey, Result> callCache) {
        return results.stream()
                .flatMap(r -> eval.resolveExpand(r, (current, ex) -> stringifyUnresolved(current, ex, callCache)).stream())
                .flatMap(result -> result.getValue((current1, ex1) -> stringifyUnresolved(current1, ex1, callCache)).stream())
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
