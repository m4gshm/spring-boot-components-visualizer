package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalResult;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static io.github.m4gshm.connections.bytecode.EvalUtils.*;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class RestOperationsUtils {
    public static List<HttpMethod> extractRestOperationsUris(String componentName, Class<?> componentType,
                                                             ConfigurableApplicationContext context, Collection<Component> components) {
        var javaClass = lookupClass(componentType);
        var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
        var methods = javaClass.getMethods();
        var bootstrapMethods = javaClass.<BootstrapMethods>getAttribute(ATTR_BOOTSTRAP_METHODS);
        return stream(methods).flatMap(method -> {
            var code = method.getCode();

            var values = instructionHandleStream(new InstructionList(code.getCode())).map(instructionHandle -> {
                var instruction = instructionHandle.getInstruction();
                var expectedType = instruction instanceof INVOKEVIRTUAL ? RestTemplate.class :
                        instruction instanceof INVOKEINTERFACE ? RestOperations.class : null;
                var match = expectedType != null && isClass(expectedType, ((InvokeInstruction) instruction), constantPoolGen);
                return match
                        ? extractHttpMethod(componentName, context.getBean(componentName), instructionHandle,
                        constantPoolGen, bootstrapMethods, components, method, context)
                        : null;
            }).filter(Objects::nonNull).collect(toList());

            return values.stream();
        }).filter(Objects::nonNull).collect(toList());
    }

    static boolean isClass(Class<?> expectedClass, InvokeInstruction instruction, ConstantPoolGen constantPoolGen) {
        var className = instruction.getClassName(constantPoolGen);
        return expectedClass.getName().equals(className);
    }

    private static HttpMethod extractHttpMethod(
            String componentName, Object object, InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
            BootstrapMethods bootstrapMethods,
            Collection<Component> components, Method method, ConfigurableApplicationContext context) {
        log.trace("extractHttpMethod componentName {}", componentName);
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);
        var onEval = instructionHandle.getPrev();
        var httpMethod = getHttpMethod(methodName);

        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var arguments = new EvalResult[argumentTypes.length];
        for (int i = argumentTypes.length; i > 0; i--) {
            var evalResult = eval(object, componentName, onEval, constantPoolGen,
                    bootstrapMethods, method, components, context);
            arguments[i - 1] = evalResult;
            onEval = evalResult.getLastInstruction().getPrev();
        }

        var url = String.valueOf(arguments[0].getResult());
        return HttpMethod.builder().method(httpMethod).path(url).build();
    }

    private static String getHttpMethod(String methodName) {
        return methodName.startsWith("get") ? "GET"
                : methodName.startsWith("post") ? "POST"
                : methodName.startsWith("put") ? "PUT"
                : methodName.startsWith("delete") ? "DELETE"
                : methodName.startsWith("head") ? "HEAD"
                : methodName.startsWith("patch") ? "PATCH"
                : methodName.startsWith("exchange") ? "EXCHANGE"
                : methodName.startsWith("execute") ? "EXECUTE"
                : null;
    }
}
