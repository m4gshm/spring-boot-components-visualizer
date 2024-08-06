package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.ConnectionsExtractor.JmsClient;
import io.github.m4gshm.connections.ConnectionsExtractor.JmsClient.Type;
import io.github.m4gshm.connections.bytecode.EvalUtils;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.INVOKEINTERFACE;
import org.apache.bcel.generic.INVOKEVIRTUAL;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.InvokeInstruction;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.Topic;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

import static io.github.m4gshm.connections.bytecode.EvalUtils.eval;
import static io.github.m4gshm.connections.bytecode.EvalUtils.lookupClass;
import static io.github.m4gshm.connections.client.RestOperationsUtils.isClass;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class JmsOperationsUtils {

    public static final String UNRECOGNIZED_DESTINATION = "???";
    public static final String UNDEFINED_DESTINATION = "???";
    public static final String DEFAULT_DESTINATION = "default";

    public static List<JmsClient> extractJmsClients(
            String componentName, Class<?> componentType, ConfigurableApplicationContext context) {
        var javaClass = lookupClass(componentType);
        var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
        var methods = javaClass.getMethods();
        var bootstrapMethods = javaClass.<BootstrapMethods>getAttribute(ATTR_BOOTSTRAP_METHODS);
        return stream(methods).flatMap(method -> {
            var code = method.getCode();
            var instructionList = new InstructionList(code.getCode());

            var values = StreamSupport.stream(instructionList.spliterator(), false).map(instructionHandle -> {
                var instruction = instructionHandle.getInstruction();
                var expectedType =
                        instruction instanceof INVOKEVIRTUAL ? JmsTemplate.class :
                                instruction instanceof INVOKEINTERFACE ? JmsOperations.class : null;

                var match = expectedType != null && isClass(expectedType, ((InvokeInstruction) instruction), constantPoolGen);
                return match
                        ? extractJmsClients(context.getBean(componentName), instructionHandle, constantPoolGen, bootstrapMethods)
                        : null;
            }).filter(Objects::nonNull).collect(toList());

            return values.stream();
        }).filter(Objects::nonNull).collect(toList());
    }

    private static JmsClient extractJmsClients(
            Object object, InstructionHandle instructionHandle,
            ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods
    ) {
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);

        var onEval = instructionHandle.getPrev();

        var jmsClientType = getJmsClientType(methodName);
        if (jmsClientType.isEmpty()) {
            return null;
        } else {
            var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
            var arguments = new EvalUtils.CallResult[argumentTypes.length];
            for (int i = argumentTypes.length; i > 0; i--) {
                var evalResult = eval(object, onEval, constantPoolGen, bootstrapMethods);
                arguments[i - 1] = evalResult;
                onEval = evalResult.getLastInstruction().getPrev();
            }

            var destination = arguments.length > 0 ? getDestination(arguments[0].getResult()) : DEFAULT_DESTINATION;

            return JmsClient.builder()
                    .destination(destination)
                    .types(jmsClientType)
                    .name(methodName)
                    .build();
        }
    }

    private static String getDestination(Object firstArg) {
        String destination;
        try {
            destination = firstArg instanceof CharSequence ? firstArg.toString()
                    : firstArg instanceof Queue ? ((Queue) firstArg).getQueueName()
                    : firstArg instanceof Topic ? ((Topic) firstArg).getTopicName() : UNDEFINED_DESTINATION;
        } catch (JMSException e) {
            log.error("destination name error", e);
            destination = UNRECOGNIZED_DESTINATION;
        }
        return destination;
    }

    private static Set<Type> getJmsClientType(String methodName) {
        var result = new HashSet<Type>();
        var methodNameLowerCase = methodName.toLowerCase();
        if (methodNameLowerCase.contains("send")) {
            result.add(Type.sender);
        }
        if (methodNameLowerCase.contains("receive")) {
            result.add(Type.listener);
        }
        return result;
    }
}
