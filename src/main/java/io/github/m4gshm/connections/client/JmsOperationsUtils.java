package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.ComponentsExtractor.JmsClient;
import io.github.m4gshm.connections.eval.bytecode.CallCacheKey;
import io.github.m4gshm.connections.eval.bytecode.Eval;
import io.github.m4gshm.connections.eval.bytecode.EvalContextFactory;
import io.github.m4gshm.connections.eval.bytecode.NotInvokedException;
import io.github.m4gshm.connections.eval.result.DelayInvoke;
import io.github.m4gshm.connections.eval.result.Resolver;
import io.github.m4gshm.connections.eval.result.Result;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.Interface.Direction;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.generic.*;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.JmsTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.client.RestOperationsUtils.isClass;
import static io.github.m4gshm.connections.client.Utils.resolveInvokeParameters;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static io.github.m4gshm.connections.model.Interface.Direction.*;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

@Slf4j
@UtilityClass
public class JmsOperationsUtils {

    public static final String UNRECOGNIZED_DESTINATION = "unrecognized";
    public static final String UNDEFINED_DESTINATION = "undefined";
    public static final String DEFAULT_DESTINATION = "jmsTemplate-default";
    //todo move to options
    private static final Set<String> jmsQueueClassNames = Set.of("javax.jms.Queue", "jakarta.jms.Queue");
    private static final Set<String> jmsTopicClassNames = Set.of("javax.jms.Topic", "jakarta.jms.Topic");

    public static List<JmsClient> extractJmsClients(Component component,
                                                    Map<CallCacheKey, Result> callCache,
                                                    EvalContextFactory evalContextFactory,
                                                    Resolver resolver) {
        var javaClasses = getClassHierarchy(component.getType());
        return javaClasses.stream().flatMap(javaClass -> {
            var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
            var methods = javaClass.getMethods();

            return stream(methods).flatMap(method -> instructionHandleStream(method.getCode()).flatMap(instructionHandle -> {
                var instruction = instructionHandle.getInstruction();
                var expectedType = instruction instanceof INVOKEVIRTUAL ? JmsTemplate.class :
                        instruction instanceof INVOKEINTERFACE ? JmsOperations.class : null;
                var match = expectedType != null && isClass(expectedType, ((InvokeInstruction) instruction), constantPoolGen);
                return match
                        ? extractJmsClients(component, instructionHandle,
                        constantPoolGen, callCache, evalContextFactory.getEvalContext(component, javaClass, method), resolver).stream()
                        : Stream.of();
            }).filter(Objects::nonNull));
        }).collect(toList());
    }

    private static List<JmsClient> extractJmsClients(
            Component component,
            InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
            Map<CallCacheKey, Result> callCache, Eval eval, Resolver resolver) {
        log.trace("extractJmsClients, componentName {}", component.getName());
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);
        var direction = getJmsDirection(methodName);
        if (direction == undefined) {
            return List.of();
        } else {

            var result = (DelayInvoke) eval.eval(instructionHandle, callCache);

            var variants = resolveInvokeParameters(eval, result, component, methodName, resolver);

            var results = variants.stream().flatMap(paramVariant -> {
                return getJmsClientStream(paramVariant, direction, methodName, eval, resolver);
            }).collect(toList());
            return results;
        }
    }

    private static Stream<JmsClient> getJmsClientStream(List<Result> paramVariant, Direction direction,
                                                        String methodName, Eval eval, Resolver resolver) {
        try {
            if (paramVariant.size() < 2) {
                return Stream.of(newJmsClient(DEFAULT_DESTINATION, direction, methodName));
            } else {
                var first = paramVariant.get(1);
                var resolved = eval.resolveExpand(first, resolver);
                return resolved.stream().flatMap(v -> v.getValue(resolver).stream())
                        .map(v -> newJmsClient(getDestination(v), direction, methodName));
            }
        } catch (NotInvokedException e) {
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
            if (firstArg instanceof CharSequence) destination = firstArg.toString();
            else {
                var firstArgClass = firstArg.getClass();
                var name = firstArgClass.getName();
                String methodName;
                if (jmsQueueClassNames.contains(name)) {
                    methodName = "getQueueName";
                } else if (jmsTopicClassNames.contains(name)) {
                    methodName = "getTopicName";
                } else {
                    methodName = null;
                }
                if (methodName != null) {
                    var method = getDeclaredMethod(firstArgClass, methodName, new Class[0]);
                    destination = (String) method.invoke(firstArg);
                } else {
                    //log
                    destination = UNDEFINED_DESTINATION;
                }
            }
        } catch (Exception e) {
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
