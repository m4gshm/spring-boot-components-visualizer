package io.github.m4gshm.components.visualizer.client;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import lombok.var;

import io.github.m4gshm.components.visualizer.extractor.JmsService;
import io.github.m4gshm.components.visualizer.eval.bytecode.CallCacheKey;
import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalContextFactory;
import io.github.m4gshm.components.visualizer.eval.bytecode.NotInvokedException;
import io.github.m4gshm.components.visualizer.eval.result.DelayInvoke;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.model.Component;
import io.github.m4gshm.components.visualizer.model.Direction;
import io.github.m4gshm.components.visualizer.model.MethodId;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.JmsTemplate;

import java.util.*;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.ComponentsExtractor.getClassHierarchy;
import static io.github.m4gshm.components.visualizer.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.components.visualizer.client.RestOperationsUtils.isClass;
import static io.github.m4gshm.components.visualizer.client.Utils.resolveInvokeParameters;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.instructionHandleStream;
import static io.github.m4gshm.components.visualizer.model.Direction.*;
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
    private static final Set<String> jmsQueueClassNames = ImmutableSet.of("javax.jms.Queue", "jakarta.jms.Queue");
    private static final Set<String> jmsTopicClassNames = ImmutableSet.of("javax.jms.Topic", "jakarta.jms.Topic");

    public static List<JmsService> extractJmsClients(Component component,
                                                     Map<CallCacheKey, Result> callCache,
                                                     EvalContextFactory evalContextFactory,
                                                     Resolver resolver) {
        List<JavaClass> javaClasses = getClassHierarchy(component.getType());
        return javaClasses.stream().flatMap(javaClass -> {
            ConstantPoolGen constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
            Method[] methods = javaClass.getMethods();

            return stream(methods).flatMap(method -> instructionHandleStream(method.getCode()).flatMap(instructionHandle -> {
                Instruction instruction = instructionHandle.getInstruction();
                Class<? extends JmsOperations> expectedType = instruction instanceof INVOKEVIRTUAL ? JmsTemplate.class :
                        instruction instanceof INVOKEINTERFACE ? JmsOperations.class : null;
                boolean match = expectedType != null && isClass(expectedType, ((InvokeInstruction) instruction), constantPoolGen);
                return match
                        ? extractJmsClients(component, instructionHandle,
                        constantPoolGen, callCache, evalContextFactory.getEvalContext(component, javaClass, method), resolver).stream()
                        : Stream.of();
            }).filter(Objects::nonNull));
        }).collect(toList());
    }

    private static List<JmsService> extractJmsClients(
            Component component, InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
            Map<CallCacheKey, Result> callCache, Eval eval, Resolver resolver) {
        log.trace("extractJmsClients, componentName {}", component.getName());
        InvokeInstruction instruction = (InvokeInstruction) instructionHandle.getInstruction();

        String methodName = instruction.getMethodName(constantPoolGen);
        Direction direction = getJmsDirection(methodName);
        if (direction == undefined) {
            return ImmutableList.of();
        } else {
            DelayInvoke result = (DelayInvoke) eval.eval(instructionHandle, callCache);
            List<List<Result>> variants = resolveInvokeParameters(eval, result, component, methodName, resolver);
            List<JmsService> results = variants.stream().flatMap(paramVariant -> {
                return getJmsClientStream(paramVariant, direction, methodName, eval, resolver);
            }).collect(toList());
            return results;
        }
    }

    private static Stream<JmsService> getJmsClientStream(List<Result> paramVariant, Direction direction,
                                                         String methodName, Eval eval, Resolver resolver) {
        try {
            MethodId ref = newMethodId(eval.getMethod());
            if (paramVariant.size() < 2) {
                return Stream.of(newJmsClient(DEFAULT_DESTINATION, direction, methodName, ref, null));
            } else {
                Result first = paramVariant.get(1);
                List<Result> destinations = eval.resolveExpand(first, resolver);
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
                Class<?> firstArgClass = firstArg.getClass();
                String name = firstArgClass.getName();
                String methodName;
                if (jmsQueueClassNames.contains(name)) {
                    methodName = "getQueueName";
                } else if (jmsTopicClassNames.contains(name)) {
                    methodName = "getTopicName";
                } else {
                    methodName = null;
                }
                if (methodName != null) {
                    java.lang.reflect.Method method = getDeclaredMethod(firstArgClass, methodName, new Class[0]);
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
        String methodNameLowerCase = methodName.toLowerCase();
        int sendIndex = methodNameLowerCase.indexOf("send");
        int receiveIndex = methodNameLowerCase.indexOf("receive");
        return sendIndex >= 0 && receiveIndex > sendIndex ? outIn : sendIndex >= 0
                ? out : receiveIndex >= 0 ? in : undefined;
    }
}
