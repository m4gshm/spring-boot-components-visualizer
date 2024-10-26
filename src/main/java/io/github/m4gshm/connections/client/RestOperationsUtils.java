package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.eval.bytecode.CallCacheKey;
import io.github.m4gshm.connections.eval.bytecode.EvalContextFactory;
import io.github.m4gshm.connections.eval.bytecode.NotInvokedException;
import io.github.m4gshm.connections.eval.result.DelayInvoke;
import io.github.m4gshm.connections.eval.result.Resolver;
import io.github.m4gshm.connections.eval.result.Result;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
import io.github.m4gshm.connections.model.MethodId;
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
import static io.github.m4gshm.connections.client.Utils.resolveInvokeParameters;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static io.github.m4gshm.connections.model.MethodId.newMethodId;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class RestOperationsUtils {
    public static List<HttpMethod> extractRestOperationsUris(Component component, Map<CallCacheKey, Result> callCache,
                                                             EvalContextFactory evalContextFactory, Resolver resolver) {
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
                        constantPoolGen, bootstrapMethods, method, callCache, evalContextFactory, resolver)
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
                                                       Map<CallCacheKey, Result> callCache, EvalContextFactory evalContextFactory, Resolver resolver) {
        var instructionText = instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
        log.info("extractHttpMethod component {}, method {}, invoke {}", component.getName(), method.toString(),
                instructionText);

        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var methodName = instruction.getMethodName(constantPoolGen);
        var eval = evalContextFactory.getEvalContext(component, method, bootstrapMethods);
        var result = (DelayInvoke) eval.eval(instructionHandle, callCache);
        var variants = resolveInvokeParameters(eval, result, component, methodName, resolver);

        return variants.stream().flatMap(variant -> {
            var pathArg = variant.get(1);
            var methodId = newMethodId(pathArg.getMethod());
            return getHttpMethodStream(variant, methodName, pathArg, resolver, methodId);
        }).collect(toList());
    }

    private static Stream<HttpMethod> getHttpMethodStream(List<Result> variant, String methodName, Result pathArg,
                                                          Resolver resolver, MethodId ref) {
        try {
            final List<String> httpMethods;
            if ("exchange".equals(methodName)) {
                var resolvedHttpMethodArg = variant.get(2);
                httpMethods = getStrings(resolvedHttpMethodArg.getValue(resolver));
            } else {
                httpMethods = List.of(getHttpMethod(methodName));
            }
            var paths = getStrings(pathArg.getValue(resolver));

            return paths.stream().flatMap(path -> httpMethods.stream()
                    .map(httpMethod -> HttpMethod.builder().method(httpMethod).path(path).ref(ref).build()));
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
