package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
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

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class RestOperationsUtils {
    public static List<HttpMethod> extractRestOperationsUris(Component component,
                                                             Map<Component, List<Component>> dependencyToDependentMap,
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
                        constantPoolGen, bootstrapMethods, method, callPointsCache)
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
                                                       Map<Component, List<CallPoint>> callPointsCache) {
        var instructionText = instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
        log.info("extractHttpMethod component {}, method {}, invoke {}", component.getName(), method.toString(),
                instructionText);
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);

        var eval = new EvalBytecode(component, dependencyToDependentMap, constantPoolGen,
                bootstrapMethods, method, callPointsCache);

        var argumentTypes = instruction.getArgumentTypes(eval.getConstantPoolGen());
        var evalArguments = eval.evalArguments(instructionHandle, argumentTypes.length, null);
        var argumentsArguments = evalArguments.getArguments();

        var path = argumentsArguments.get(0);
        var resolvedPaths = eval.resolve(path, StringifyEvalResultUtils::stringifyUnresolved);
        var paths = resolveVariableStrings(eval, resolvedPaths);

        final List<String> httpMethods;
        if ("exchange".equals(methodName)) {
            var httpMethodArg = argumentsArguments.get(1);
            var resolvedHttpMethodResults = eval.resolve(httpMethodArg, null);
            httpMethods = resolveVariableStrings(eval, resolvedHttpMethodResults);
        } else {
            httpMethods = List.of(getHttpMethod(methodName));
        }

        return httpMethods.stream().flatMap(m -> paths.stream().map(p -> HttpMethod.builder().method(m).path(p).build()))
                .collect(toList());
    }

    private static List<String> resolveVariableStrings(EvalBytecode eval, Collection<Result> results) {
        return results.stream()
                .flatMap(r -> eval.resolve(r, StringifyEvalResultUtils::stringifyUnresolved).stream())
                .map(result -> String.valueOf(result.getValue(String.class, StringifyEvalResultUtils::stringifyUnresolved)))
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
