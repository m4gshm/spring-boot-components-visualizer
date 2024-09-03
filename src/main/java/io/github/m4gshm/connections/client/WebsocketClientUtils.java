package io.github.m4gshm.connections.client;

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

import static io.github.m4gshm.connections.bytecode.EvalUtils.*;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class WebsocketClientUtils {
    public static List<String> extractWebsocketClientUris(String componentName, Class<?> componentType,
                                                          ConfigurableApplicationContext context, Collection<Component> components) {
        var javaClass = lookupClass(componentType);
        var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
        var methods = javaClass.getMethods();
        var bootstrapMethods = javaClass.<BootstrapMethods>getAttribute(ATTR_BOOTSTRAP_METHODS);
        return stream(methods).flatMap(method -> {
            var code = method.getCode();
            var values = instructionHandleStream(new InstructionList(code.getCode())).map(instructionHandle -> {
                var instruction = instructionHandle.getInstruction();
                if (instruction instanceof INVOKEINTERFACE) {
                    var invoke = (InvokeInstruction) instruction;

                    var referenceType = invoke.getReferenceType(constantPoolGen);
                    var methodName = invoke.getMethodName(constantPoolGen);
                    var className = referenceType.getClassName();

                    if (isMethodOfClass(WebSocketClient.class, "doHandshake", className, methodName)) try {
                        return getDoHandshakeUri(componentName, context.getBean(componentName), instructionHandle,
                                constantPoolGen, bootstrapMethods, components, method, context);
                    } catch (ClassNotFoundException | InvocationTargetException | NoSuchMethodException |
                             IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
                return null;
            }).filter(Objects::nonNull).collect(toList());

            return values.stream();
        }).filter(Objects::nonNull).collect(toList());
    }

    private static boolean isMethodOfClass(Class<?> expectedClass, String expectedMethodName, String className, String methodName) {
        return expectedClass.getName().equals(className) && expectedMethodName.equals(methodName);
    }

    private static String getDoHandshakeUri(
            String componentName, Object object, InstructionHandle instructionHandle,
            ConstantPoolGen constantPoolGen,
            BootstrapMethods bootstrapMethods, Collection<Component> components,
            Method method, ConfigurableApplicationContext context) throws ClassNotFoundException,
            InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        log.trace("getDoHandshakeUri componentName {}", componentName);
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        if (argumentTypes.length != 3) {
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes.length mismatch, " + argumentTypes.length);
        }
        if (URI.class.getName().equals(argumentTypes[2].getClassName())) {
            var value = eval(object, componentName, instructionHandle.getPrev(), constantPoolGen,
                    bootstrapMethods, method, components, context);
            var result = value.getFirstValue().getValue();
            if (result instanceof URI) {
                var uri = (URI) result;
                return uri.toString();
            } else {
                return result != null ? result.toString() : null;
            }
        } else if (String.class.getName().equals(argumentTypes[1].getClassName())) {
            var uriTemplates = eval(object, componentName, instructionHandle.getPrev(), constantPoolGen,
                    bootstrapMethods, method, components, context);
            var utiTemplate = eval(object, componentName, uriTemplates.getLastInstruction().getPrev(), constantPoolGen,
                    bootstrapMethods, method, components, context);
            return String.valueOf(utiTemplate.getFirstValue().getValue());
        } else {
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes without URI, " + Arrays.toString(argumentTypes));
        }
    }
}
