package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.eval.bytecode.*;
import io.github.m4gshm.connections.eval.result.Result;
import io.github.m4gshm.connections.eval.result.DelayInvoke;
import io.github.m4gshm.connections.model.Component;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.INVOKEINTERFACE;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InvokeInstruction;
import org.springframework.web.socket.client.WebSocketClient;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.*;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static io.github.m4gshm.connections.client.Utils.resolveInvokeParameters;
import static io.github.m4gshm.connections.eval.bytecode.StringifyUtils.stringifyUnresolved;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class WebsocketClientUtils {
    public static List<String> extractWebsocketClientUris(Component component,
                                                          Map<CallCacheKey, Result> callCache,
                                                          EvalContextFactory evalContextFactory) {
        var javaClasses = getClassHierarchy(component.getType());
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
                        var uri = getDoHandshakeUri(component, instructionHandle, constantPoolGen,
                                bootstrapMethods, method, callCache, evalContextFactory);
                        return uri;
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

    private static List<String> getDoHandshakeUri(Component component,
                                                  InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
                                                  BootstrapMethods bootstrapMethods, Method method,
                                                  Map<CallCacheKey, Result> callCache, EvalContextFactory evalContextFactory
    ) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        log.trace("getDoHandshakeUri componentName {}", component.getName());
        var methodName = method.getName();
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        if (argumentTypes.length != 3) {
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes.length mismatch, " + argumentTypes.length);
        }
        var eval = evalContextFactory.getEvalContext(component, method, bootstrapMethods);
        var result = (DelayInvoke) eval.eval(instructionHandle, callCache);
        var variants = resolveInvokeParameters(eval, result, component, methodName, callCache);

        if (URI.class.getName().equals(argumentTypes[2].getClassName())) {
            return getUrls(variants, 3, callCache);
        } else if (String.class.getName().equals(argumentTypes[1].getClassName())) {
            return getUrls(variants, 2, callCache);
        } else {
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes without URI, " + Arrays.toString(argumentTypes));
        }
    }

    private static List<String> getUrls(List<List<Result>> variants, int paramIndex, Map<CallCacheKey, Result> callCache) {
        var results = variants.stream().flatMap(paramVariant -> {
            try {
                var url = paramVariant.get(paramIndex);
                return url.getValue((current, ex) -> stringifyUnresolved(current, ex, callCache)).stream();
            } catch (NotInvokedException e) {
                //log
                return Stream.empty();
            }
        }).map(o -> {
            if (o instanceof URI) {
                var uri = (URI) o;
                return uri.toString();
            } else {
                return o != null ? o.toString() : null;
            }
        }).collect(toList());
        return results;
    }
}
