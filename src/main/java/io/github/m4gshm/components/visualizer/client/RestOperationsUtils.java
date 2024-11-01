package io.github.m4gshm.components.visualizer.client;

import io.github.m4gshm.components.visualizer.eval.bytecode.CallCacheKey;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalContextFactory;
import io.github.m4gshm.components.visualizer.eval.bytecode.NotInvokedException;
import io.github.m4gshm.components.visualizer.eval.result.DelayInvoke;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.model.Component;
import io.github.m4gshm.components.visualizer.model.HttpMethod;
import lombok.Data;
import lombok.experimental.FieldDefaults;
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

import static io.github.m4gshm.components.visualizer.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.components.visualizer.client.Utils.resolveInvokeParameters;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.instructionHandleStream;
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
                return match ? extractHttpMethods(component, instructionHandle, constantPoolGen, bootstrapMethods,
                        method, callCache, evalContextFactory, resolver) : null;
            }).filter(Objects::nonNull).flatMap(Collection::stream)).filter(Objects::nonNull);
        }).collect(toList());
    }

    static boolean isClass(Class<?> expectedClass, InvokeInstruction instruction, ConstantPoolGen constantPoolGen) {
        var className = instruction.getClassName(constantPoolGen);
        return expectedClass.getName().equals(className);
    }

    private static List<HttpMethod> extractHttpMethods(Component component, InstructionHandle instructionHandle,
                                                       ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods,
                                                       Method method, Map<CallCacheKey, Result> callCache,
                                                       EvalContextFactory evalContextFactory, Resolver resolver) {
        var instructionText = instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
        log.info("extractHttpMethod component {}, method {}, invoke {}", component.getName(), method.toString(),
                instructionText);
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var methodName = instruction.getMethodName(constantPoolGen);
        var eval = evalContextFactory.getEvalContext(component, method, bootstrapMethods);
        var result = (DelayInvoke) eval.eval(instructionHandle, callCache);
        var variants = resolveInvokeParameters(eval, result, component, methodName, resolver);

        @Data
        @FieldDefaults(makeFinal = true)
        class ArgVariant {
            Result pathArg;
            Result httpMethodArg;
        }

        @Data
        @FieldDefaults(makeFinal = true)
        class PathVariant {
            Result pathArg;
            String httpMethod;
            String path;
        }

        var methods = variants.stream().map(variant -> {
            var pathArg = variant.get(1);
            var httpMethodArg = "exchange".equals(methodName) ? variant.get(2) : null;
            return new ArgVariant(pathArg, httpMethodArg);
        }).distinct().flatMap(variant -> {
            var pathArg = variant.pathArg;
            var resolvedHttpMethodArg = "exchange".equals(methodName) ? variant.httpMethodArg : null;

            List<String> httpMethods;
            List<String> paths;
            try {
                httpMethods = resolvedHttpMethodArg != null
                        ? getStrings(resolvedHttpMethodArg.getValue(resolver))
                        : List.of(getHttpMethod(methodName));
                paths = getStrings(pathArg.getValue(resolver));
            } catch (NotInvokedException e) {
                //log
                httpMethods = List.of();
                paths = List.of();
            }
            var httpMethods1 = httpMethods;

            return paths.stream().flatMap(path -> httpMethods1.stream().map(httpMethod -> new PathVariant(pathArg, httpMethod, path)));
        }).distinct().map(variant -> {
            return HttpMethod.builder().method(variant.httpMethod).path(variant.path).evalSource(variant.pathArg).build();
        }).collect(toList());
        return methods;
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
