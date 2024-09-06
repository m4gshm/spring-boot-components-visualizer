package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode;
import io.github.m4gshm.connections.bytecode.EvalBytecode.MethodArgumentResolver;
import io.github.m4gshm.connections.bytecode.EvalBytecode.MethodReturnResolver;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
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

import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.lookupClass;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@Slf4j
@UtilityClass
public class RestOperationsUtils {
    public static List<HttpMethod> extractRestOperationsUris(String componentName, Class<?> componentType,
                                                             ConfigurableApplicationContext context,
                                                             Collection<Component> components,
                                                             MethodArgumentResolver methodArgumentResolver,
                                                             MethodReturnResolver methodReturnResolver) {
        var javaClass = lookupClass(componentType);
        var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
        var methods = javaClass.getMethods();
        var bootstrapMethods = javaClass.<BootstrapMethods>getAttribute(ATTR_BOOTSTRAP_METHODS);
        return stream(methods).flatMap(method -> instructionHandleStream(new InstructionList(method.getCode().getCode())
        ).map(instructionHandle -> {
            var instruction = instructionHandle.getInstruction();
            var expectedType = instruction instanceof INVOKEVIRTUAL ? RestTemplate.class :
                    instruction instanceof INVOKEINTERFACE ? RestOperations.class : null;
            var match = expectedType != null && isClass(expectedType, ((InvokeInstruction) instruction), constantPoolGen);
            return match
                    ? extractHttpMethods(componentName, context.getBean(componentName), instructionHandle,
                    constantPoolGen, bootstrapMethods, components, method, context, methodArgumentResolver, methodReturnResolver)
                    : null;
        }).filter(Objects::nonNull).flatMap(Collection::stream)).filter(Objects::nonNull).collect(toList());
    }

    static boolean isClass(Class<?> expectedClass, InvokeInstruction instruction, ConstantPoolGen constantPoolGen) {
        var className = instruction.getClassName(constantPoolGen);
        return expectedClass.getName().equals(className);
    }

    private static List<HttpMethod> extractHttpMethods(
            String componentName, Object object, InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
            BootstrapMethods bootstrapMethods, Collection<Component> components, Method method,
            ConfigurableApplicationContext context, MethodArgumentResolver methodArgumentResolver,
            MethodReturnResolver methodReturnResolver) {
        log.trace("extractHttpMethod componentName {}", componentName);
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);
        var onEval = instructionHandle.getPrev();
        var httpMethod = getHttpMethod(methodName);


        var eval = new EvalBytecode(context, object, componentName, object.getClass(), constantPoolGen,
                bootstrapMethods, method, components, methodArgumentResolver, methodReturnResolver);

        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var arguments = new Result[argumentTypes.length];
        for (int i = argumentTypes.length; i > 0; i--) {
            var evalResult = eval.eval(onEval);
            arguments[i - 1] = evalResult;
            onEval = evalResult.getLastInstruction().getPrev();
        }

        return eval.resolve(arguments[0]).stream().map(Result::getValue).map(Object::toString)
                .map(url -> HttpMethod.builder().method(httpMethod).path(url).build())
                .collect(toList());
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
