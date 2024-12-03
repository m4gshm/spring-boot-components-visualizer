package io.github.m4gshm.components.visualizer.client;

import io.github.m4gshm.components.visualizer.ComponentsExtractor.JmsService;
import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalContextFactory;
import io.github.m4gshm.components.visualizer.eval.bytecode.NotInvokedException;
import io.github.m4gshm.components.visualizer.eval.result.DelayInvoke;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.model.Component;
import io.github.m4gshm.components.visualizer.model.Interface.Direction;
import io.github.m4gshm.components.visualizer.model.MethodId;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.generic.*;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.JmsTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.getClassSources;
import static io.github.m4gshm.components.visualizer.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.components.visualizer.client.RestOperationsUtils.isClass;
import static io.github.m4gshm.components.visualizer.client.Utils.resolveInvokeParameters;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.instructionHandleStream;
import static io.github.m4gshm.components.visualizer.model.Interface.Direction.*;
import static io.github.m4gshm.components.visualizer.model.MethodId.newMethodId;
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

    public static List<JmsService> extractJmsClients(Component component,
                                                     EvalContextFactory evalContextFactory, Resolver resolver) {
        var javaClasses = getClassSources(component.getType());
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
                        constantPoolGen, evalContextFactory.getEvalContext(component, javaClass, method), resolver).stream()
                        : Stream.of();
            }).filter(Objects::nonNull));
        }).collect(toList());
    }

    private static List<JmsService> extractJmsClients(
            Component component, InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
            Eval eval, Resolver resolver) {
        log.trace("extractJmsClients, componentName {}", component.getName());
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);
        var direction = getJmsDirection(methodName);
        if (direction == undefined) {
            return List.of();
        } else {
            var result = (DelayInvoke) eval.eval(instructionHandle);
            var variants = resolveInvokeParameters(eval, result, component, methodName, resolver);
            var results = variants.stream().flatMap(paramVariant -> {
                return getJmsClientStream(paramVariant, direction, methodName, eval, resolver);
            }).collect(toList());
            return results;
        }
    }

    private static Stream<JmsService> getJmsClientStream(List<Result> paramVariant, Direction direction,
                                                         String methodName, Eval eval, Resolver resolver) {
        try {
            var ref = newMethodId(eval.getMethod());
            if (paramVariant.size() < 2) {
                return Stream.of(newJmsClient(DEFAULT_DESTINATION, direction, methodName, ref, null));
            } else {
                var first = paramVariant.get(1);
                var destinations = eval.resolveExpand(first, resolver);
                return destinations.stream().flatMap(result -> result.getValue(resolver).stream()
                        .map(rawDestination -> newJmsClient(getDestination(rawDestination), direction, methodName, ref, result)));
            }
        } catch (NotInvokedException e) {
            //log
            return Stream.empty();
        }
    }

    private static JmsService newJmsClient(String destination, Direction direction, String methodName, MethodId methodSource, Result result) {
        return JmsService.builder()
                .destination(destination)
                .direction(direction)
                .name(methodName)
                .evalSource(result)
                .methodSource(methodSource)
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
