package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode;
import io.github.m4gshm.connections.model.CallPoint;
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

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class WebsocketClientUtils {
    public static List<String> extractWebsocketClientUris(Component component,
                                                          Map<Component, List<Component>> dependentOnMap,
                                                          Map<Component, List<CallPoint>> callPointsCache) {
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
                        var uri = getDoHandshakeUri(component, dependentOnMap, instructionHandle,
                                constantPoolGen, bootstrapMethods, method,
                                callPointsCache);
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
                                                  Map<Component, List<Component>> dependentOnMap,
                                                  InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
                                                  BootstrapMethods bootstrapMethods, Method method,
                                                  Map<Component, List<CallPoint>> callPointsCache) throws ClassNotFoundException,
            InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        log.trace("getDoHandshakeUri componentName {}", component.getName());
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        if (argumentTypes.length != 3) {
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes.length mismatch, " + argumentTypes.length);
        }
        var evalEngine = new EvalBytecode(component, dependentOnMap, constantPoolGen,
                bootstrapMethods, method, callPointsCache);
        if (URI.class.getName().equals(argumentTypes[2].getClassName())) {
            var value = evalEngine.eval(evalEngine.getPrev(instructionHandle));
            return evalEngine.resolve(value, String.class, StringifyEvalResultUtils::stringifyUnresolved).stream()
                    .map(result -> result.getValue(String.class, StringifyEvalResultUtils::stringifyUnresolved)).map(o -> {
                        if (o instanceof URI) {
                            var uri = (URI) o;
                            return uri.toString();
                        } else {
                            return o != null ? o.toString() : null;
                        }
                    }).collect(toList());
        } else if (String.class.getName().equals(argumentTypes[1].getClassName())) {
            var uriTemplates = evalEngine.eval(evalEngine.getPrev(instructionHandle));
            var utiTemplate = evalEngine.eval(evalEngine.getPrev(uriTemplates.getLastInstruction()));
            return evalEngine.resolve(utiTemplate, String.class, StringifyEvalResultUtils::stringifyUnresolved).stream()
                    .map(result -> result.getValue(String.class, StringifyEvalResultUtils::stringifyUnresolved))
                    .map(String::valueOf).collect(toList());
        } else {
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes without URI, " +
                    Arrays.toString(argumentTypes));
        }
    }
}
