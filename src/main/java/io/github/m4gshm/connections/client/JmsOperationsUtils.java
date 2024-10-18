package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.ComponentsExtractor.JmsClient;
import io.github.m4gshm.connections.eval.bytecode.EvalBytecode;
import io.github.m4gshm.connections.eval.bytecode.EvalBytecode.CallCacheKey;
import io.github.m4gshm.connections.eval.result.Result;
import io.github.m4gshm.connections.eval.result.DelayInvoke;
import io.github.m4gshm.connections.eval.bytecode.NoCallException;
import io.github.m4gshm.connections.eval.bytecode.StringifyUtils;
import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.Interface.Direction;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.Topic;
import java.util.*;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static io.github.m4gshm.connections.client.RestOperationsUtils.isClass;
import static io.github.m4gshm.connections.client.RestOperationsUtils.resolveInvokeParameters;
import static io.github.m4gshm.connections.model.Interface.Direction.*;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class JmsOperationsUtils {

    public static final String UNRECOGNIZED_DESTINATION = "unrecognized";
    public static final String UNDEFINED_DESTINATION = "undefined";
    public static final String DEFAULT_DESTINATION = "jmsTemplate-default";

    public static List<JmsClient> extractJmsClients(Component component,
                                                    Map<Component, List<Component>> dependencyToDependentMap,
                                                    Map<Component, List<CallPoint>> callPointsCache,
                                                    Map<CallCacheKey, Result> callCache) {
        var javaClasses = getClassHierarchy(component.getType());
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
                        ? extractJmsClients(component, dependencyToDependentMap, instructionHandle,
                        constantPoolGen, bootstrapMethods, method, callPointsCache, callCache).stream()
                        : Stream.of();
            }).filter(Objects::nonNull));
        }).collect(toList());
    }

    public static BootstrapMethods getBootstrapMethods(JavaClass javaClass) {
        return javaClass.getAttribute(ATTR_BOOTSTRAP_METHODS);
    }

    private static List<JmsClient> extractJmsClients(
            Component component, Map<Component, List<Component>> dependencyToDependentMap,
            InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
            BootstrapMethods bootstrapMethods, Method method,
            Map<Component, List<CallPoint>> callPointsCache, Map<CallCacheKey, Result> callCache) {
        log.trace("extractJmsClients, componentName {}", component.getName());
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);
        var direction = getJmsDirection(methodName);
        if (direction == undefined) {
            return List.of();
        } else {
            var eval = new EvalBytecode(component, dependencyToDependentMap, constantPoolGen,
                    bootstrapMethods, method, callPointsCache, callCache, new ArrayList<Result>());

            var result = (DelayInvoke) eval.eval(instructionHandle);

            var variants = resolveInvokeParameters(eval, result, component, methodName);

            var results = variants.stream().flatMap(paramVariant -> {
                return getJmsClientStream(paramVariant, direction, methodName, eval);
            }).collect(toList());
            return results;
        }
    }

    private static Stream<JmsClient> getJmsClientStream(List<Result> paramVariant, Direction direction,
                                                        String methodName, EvalBytecode eval) {
        try {
            if (paramVariant.size() < 2) {
                return Stream.of(newJmsClient(DEFAULT_DESTINATION, direction, methodName));
            } else {
                var first = paramVariant.get(1);
                var resolved = eval.resolveExpand(first, (current, ex) -> StringifyUtils.stringifyUnresolved(current, ex));
                return resolved.stream().flatMap(v -> v.getValue((current, ex) -> StringifyUtils.stringifyUnresolved(current, ex), eval).stream())
                        .map(v -> newJmsClient(getDestination(v), direction, methodName));
            }
        } catch (NoCallException e) {
            //log
            return Stream.empty();
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
