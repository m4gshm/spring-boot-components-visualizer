package io.github.m4gshm.connections.client;

import lombok.experimental.UtilityClass;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.INVOKEINTERFACE;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.InvokeInstruction;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.client.WebSocketClient;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.StreamSupport;

import static io.github.m4gshm.connections.bytecode.EvalUtils.eval;
import static io.github.m4gshm.connections.bytecode.EvalUtils.lookupClass;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@UtilityClass
public class WebsocketClientUtils {
    public static List<String> extractWebsocketClientUris(String componentName, Class<?> componentType,
                                                          ConfigurableApplicationContext context)  {
        var javaClass = lookupClass(componentType);
        var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
        var methods = javaClass.getMethods();
        var bootstrapMethods = javaClass.<BootstrapMethods>getAttribute(ATTR_BOOTSTRAP_METHODS);
        return stream(methods).flatMap(method -> {
            var code = method.getCode();
            var instructionList = new InstructionList(code.getCode());

            var values = StreamSupport.stream(instructionList.spliterator(), false).map(instructionHandle -> {
                var instruction = instructionHandle.getInstruction();
                if (instruction instanceof INVOKEINTERFACE) {
                    var invoke = (InvokeInstruction) instruction;

                    var referenceType = invoke.getReferenceType(constantPoolGen);
                    var methodName = invoke.getMethodName(constantPoolGen);
                    var className = referenceType.getClassName();

                    if (isMethodOfClass(WebSocketClient.class, "doHandshake", className, methodName)) try {
                        return getDoHandshakeUri(context.getBean(componentName), instructionHandle,
                                constantPoolGen, bootstrapMethods);
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
            Object object, InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
            BootstrapMethods bootstrapMethods
    ) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        if (argumentTypes.length != 3) {
            //log
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes.length mismatch, " + argumentTypes.length);
        }
        if (URI.class.getName().equals(argumentTypes[2].getClassName())) {
            var value = eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
            var result = value.getResult();
            if (result instanceof URI) {
                var uri = (URI) result;
                return uri.toString();
            } else {
                //log
                return result.toString();
            }
        } else if (String.class.getName().equals(argumentTypes[1].getClassName())) {
            var uriTemplates = eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
            var utiTemplate = eval(object, uriTemplates.getLastInstruction().getPrev(), constantPoolGen, bootstrapMethods);
            return String.valueOf(utiTemplate.getResult());
        } else {
            //log
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes without URI, " + Arrays.toString(argumentTypes));
        }
    }
}
