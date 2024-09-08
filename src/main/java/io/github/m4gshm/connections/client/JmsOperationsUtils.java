package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.ComponentsExtractor.JmsClient;
import io.github.m4gshm.connections.bytecode.EvalBytecode;
import io.github.m4gshm.connections.bytecode.EvalBytecode.MethodArgumentResolver;
import io.github.m4gshm.connections.bytecode.EvalBytecode.MethodReturnResolver;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.Interface.Direction;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.Topic;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static io.github.m4gshm.connections.client.RestOperationsUtils.isClass;
import static io.github.m4gshm.connections.model.Interface.Direction.*;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class JmsOperationsUtils {

    public static final String UNRECOGNIZED_DESTINATION = "unrecognized";
    public static final String UNDEFINED_DESTINATION = "undefined";
    public static final String DEFAULT_DESTINATION = "default";

    public static List<JmsClient> extractJmsClients(
            String componentName, Class<?> componentType, ConfigurableApplicationContext context,
            Collection<Component> components, MethodArgumentResolver methodArgumentResolver, MethodReturnResolver methodReturnResolver) {
        var javaClasses = getClassHierarchy(componentType);
        return javaClasses.stream().flatMap(javaClass -> {
            var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
            var methods = javaClass.getMethods();
            var bootstrapMethods = getBootstrapMethods(javaClass);
            return stream(methods).flatMap(method -> instructionHandleStream(method.getCode()).flatMap(instructionHandle -> {
                var instruction = instructionHandle.getInstruction();
                var expectedType = instruction instanceof INVOKEVIRTUAL ? JmsTemplate.class :
                        instruction instanceof INVOKEINTERFACE ? JmsOperations.class : null;
                var match = expectedType != null && isClass(expectedType, ((InvokeInstruction) instruction), constantPoolGen);
                return match
                        ? extractJmsClients(componentName, context.getBean(componentName), instructionHandle,
                        constantPoolGen, bootstrapMethods, method, components, context,
                        methodArgumentResolver, methodReturnResolver).stream()
                        : Stream.of();
            }).filter(Objects::nonNull));
        }).collect(toList());
    }

    public static BootstrapMethods getBootstrapMethods(JavaClass javaClass) {
        return javaClass.getAttribute(ATTR_BOOTSTRAP_METHODS);
    }

    private static List<JmsClient> extractJmsClients(
            String componentName, Object object, InstructionHandle instructionHandle,
            ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods,
            Method method, Collection<Component> components, ConfigurableApplicationContext context,
            MethodArgumentResolver methodArgumentResolver, MethodReturnResolver methodReturnResolver) {
        log.trace("extractJmsClients, componentName {}", componentName);
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);
        var direction = getJmsDirection(methodName);
        if (direction == undefined) {
            return List.of();
        } else {
            var eval = new EvalBytecode(context, object, componentName, object.getClass(), constantPoolGen,
                    bootstrapMethods, method, components, methodArgumentResolver, methodReturnResolver);
            var arguments = eval.evalArguments(instructionHandle, instruction);
            var argumentsArguments = arguments.getArguments();
            if (argumentsArguments.isEmpty()) {
                return List.of(newJmsClient(DEFAULT_DESTINATION, direction, methodName));
            } else {
                var first = argumentsArguments.get(0);
                var resolved = eval.resolve(first);
                return resolved.stream().map(v -> newJmsClient(JmsOperationsUtils.getDestination(v.getValue()),
                        direction, methodName)).collect(toList());
            }
        }
    }

    private static JmsClient newJmsClient(String destination, Direction direction, String methodName) {
        return JmsClient.builder()
                .destination(destination)
                .direction(direction)
                .name(methodName)
                .build();
    }

    private static String getDestination(Object firstArg) {
        String destination;
        try {
            destination = firstArg instanceof CharSequence ? firstArg.toString()
                    : firstArg instanceof Queue ? ((Queue) firstArg).getQueueName()
                    : firstArg instanceof Topic ? ((Topic) firstArg).getTopicName()
                    : UNDEFINED_DESTINATION;
        } catch (JMSException e) {
            log.error("destination name error", e);
            destination = UNRECOGNIZED_DESTINATION;
        }
        return destination;
    }

    static Direction getJmsDirection(String methodName) {
        var methodNameLowerCase = methodName.toLowerCase();
        int sendIndex = methodNameLowerCase.indexOf("send");
        int receiveIndex = methodNameLowerCase.indexOf("receive");
        return sendIndex >= 0 && receiveIndex > sendIndex ? outIn : sendIndex >= 0
                ? out : receiveIndex >= 0 ? in : undefined;
    }
}
