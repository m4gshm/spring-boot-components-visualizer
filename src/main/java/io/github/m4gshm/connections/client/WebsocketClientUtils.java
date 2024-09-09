package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode;
import io.github.m4gshm.connections.bytecode.EvalBytecode.MethodArgumentResolver;
import io.github.m4gshm.connections.model.Component;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.client.WebSocketClient;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.MethodReturnResolver;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class WebsocketClientUtils {
    public static List<String> extractWebsocketClientUris(String componentName, Class<?> componentType,
                                                          ConfigurableApplicationContext context,
                                                          Collection<Component> components,
                                                          MethodArgumentResolver methodArgumentResolver,
                                                          MethodReturnResolver methodReturnResolver, Function<Result, Result> unevaluatedHandler) {
        var javaClasses = getClassHierarchy(componentType);
        return javaClasses.stream().flatMap(javaClass -> {
            var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
            var methods = javaClass.getMethods();
            var bootstrapMethods = javaClass.<BootstrapMethods>getAttribute(ATTR_BOOTSTRAP_METHODS);
            return stream(methods).flatMap(method -> instructionHandleStream(method.getCode()).map(instructionHandle -> {
                var instruction = instructionHandle.getInstruction();
                if (instruction instanceof INVOKEINTERFACE) {
                    var invoke = (InvokeInstruction) instruction;

                    var referenceType = invoke.getReferenceType(constantPoolGen);
                    var methodName = invoke.getMethodName(constantPoolGen);
                    var className = referenceType.getClassName();

                    if (isMethodOfClass(WebSocketClient.class, "doHandshake", className, methodName)) try {
                        return getDoHandshakeUri(componentName, context.getBean(componentName), instructionHandle,
                                constantPoolGen, bootstrapMethods, components, method, context,
                                methodArgumentResolver, methodReturnResolver, unevaluatedHandler);
                    } catch (ClassNotFoundException | InvocationTargetException | NoSuchMethodException |
                             IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
                return null;
            }).filter(Objects::nonNull).flatMap(Collection::stream)).filter(Objects::nonNull);
        }).collect(toList());
    }

    private static boolean isMethodOfClass(Class<?> expectedClass, String expectedMethodName, String className, String methodName) {
        return expectedClass.getName().equals(className) && expectedMethodName.equals(methodName);
    }

    private static List<String> getDoHandshakeUri(
            String componentName, Object object, InstructionHandle instructionHandle,
            ConstantPoolGen constantPoolGen,
            BootstrapMethods bootstrapMethods, Collection<Component> components,
            Method method, ConfigurableApplicationContext context,
            MethodArgumentResolver methodArgumentResolver,
            MethodReturnResolver methodReturnResolver, Function<Result, Result> unevaluatedHandler) throws ClassNotFoundException,
            InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        log.trace("getDoHandshakeUri componentName {}", componentName);
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        if (argumentTypes.length != 3) {
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes.length mismatch, " + argumentTypes.length);
        }
        var evalEngine = new EvalBytecode(context, object, componentName, object.getClass(), constantPoolGen,
                bootstrapMethods, method, components, methodArgumentResolver, methodReturnResolver);
        if (URI.class.getName().equals(argumentTypes[2].getClassName())) {
            //todo use eva.getPrev
            var value = evalEngine.eval(instructionHandle.getPrev(), unevaluatedHandler);
            return evalEngine.resolve(value, unevaluatedHandler).stream().map(result->result.getValue(unevaluatedHandler)).map(o -> {
                if (o instanceof URI) {
                    var uri = (URI) o;
                    return uri.toString();
                } else {
                    return o != null ? o.toString() : null;
                }
            }).collect(toList());
        } else if (String.class.getName().equals(argumentTypes[1].getClassName())) {
            //todo use eva.getPrev
            var uriTemplates = evalEngine.eval(instructionHandle.getPrev(), unevaluatedHandler);
            //todo use eva.getPrev
            var utiTemplate = evalEngine.eval(uriTemplates.getLastInstruction().getPrev(), unevaluatedHandler);
            return evalEngine.resolve(utiTemplate, unevaluatedHandler).stream()
                    .map(result -> result.getValue(unevaluatedHandler)).map(String::valueOf).collect(toList());
        } else {
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes without URI, " + Arrays.toString(argumentTypes));
        }
    }
}
