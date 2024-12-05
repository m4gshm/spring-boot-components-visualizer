package io.github.m4gshm.components.visualizer.client;

import io.github.m4gshm.components.visualizer.eval.bytecode.EvalContextFactory;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils;
import io.github.m4gshm.components.visualizer.eval.bytecode.NotInvokedException;
import io.github.m4gshm.components.visualizer.eval.result.DelayInvoke;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
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

import static io.github.m4gshm.components.visualizer.client.Utils.resolveInvokeParameters;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.getClassSources;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.instructions;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class WebsocketClientUtils {
    public static List<String> extractWebsocketClientUris(Component component,
                                                          EvalContextFactory evalContextFactory, Resolver resolver) {
        var javaClasses = getClassSources(component.getType());
        return javaClasses.stream().flatMap(javaClass -> {
            var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
            var methods = javaClass.getMethods();
            var bootstrapMethods = javaClass.<BootstrapMethods>getAttribute(ATTR_BOOTSTRAP_METHODS);
            return stream(methods).flatMap(method -> EvalUtils.instructions(method.getCode()).map(instructionHandle -> {
                var instruction = instructionHandle.getInstruction();
                if (instruction instanceof INVOKEINTERFACE) {
                    var invoke = (InvokeInstruction) instruction;

                    var referenceType = invoke.getReferenceType(constantPoolGen);
                    var methodName = invoke.getMethodName(constantPoolGen);
                    var className = referenceType.getClassName();

                    if (isMethodOfClass(WebSocketClient.class, "doHandshake", className, methodName)) try {
                        var uri = getDoHandshakeUri(component, instructionHandle, javaClass, constantPoolGen,
                                bootstrapMethods, method, evalContextFactory, resolver);
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

    private static List<String> getDoHandshakeUri(Component component, InstructionHandle instructionHandle,
                                                  JavaClass javaClass, ConstantPoolGen constantPoolGen,
                                                  BootstrapMethods bootstrapMethods, Method method,
                                                  EvalContextFactory evalContextFactory,
                                                  Resolver resolver
    ) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        log.trace("getDoHandshakeUri componentName {}", component.getName());
        var methodName = method.getName();
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        if (argumentTypes.length != 3) {
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes.length mismatch, " + argumentTypes.length);
        }
        var eval = evalContextFactory.getEvalContext(component, javaClass, method, bootstrapMethods);
        var result = (DelayInvoke) eval.eval(instructionHandle);
        var variants = resolveInvokeParameters(eval, result, component, methodName, resolver);

        if (URI.class.getName().equals(argumentTypes[2].getClassName())) {
            return getUrls(variants, 3, resolver);
        } else if (String.class.getName().equals(argumentTypes[1].getClassName())) {
            return getUrls(variants, 2, resolver);
        } else {
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes without URI, " + Arrays.toString(argumentTypes));
        }
    }

    private static List<String> getUrls(Collection<List<Result>> variants, int paramIndex, Resolver resolver) {
        var results = variants.stream().parallel().flatMap(paramVariant -> {
            try {
                var url = paramVariant.get(paramIndex);
                return url.getValue(resolver).stream().parallel();
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
