package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.model.HttpMethod;
import io.github.m4gshm.connections.bytecode.EvalUtils.CallResult;
import lombok.experimental.UtilityClass;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.INVOKEINTERFACE;
import org.apache.bcel.generic.INVOKEVIRTUAL;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.InvokeInstruction;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Objects;
import java.util.stream.StreamSupport;

import static io.github.m4gshm.connections.bytecode.EvalUtils.eval;
import static io.github.m4gshm.connections.bytecode.EvalUtils.lookupClass;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.ATTR_BOOTSTRAP_METHODS;

@UtilityClass
public class RestOperationsUtils {
    public static List<HttpMethod> extractRestOperationsUris(String componentName, Class<?> componentType,
                                                             ConfigurableApplicationContext context) {
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
                        instruction instanceof INVOKEVIRTUAL ? RestTemplate.class :
                                instruction instanceof INVOKEINTERFACE ? RestOperations.class : null;

                var match = expectedType != null && isClass(expectedType, ((InvokeInstruction) instruction), constantPoolGen);
                return match
                        ? extractHttpMethod(context.getBean(componentName), instructionHandle, constantPoolGen, bootstrapMethods)
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
            Object object, InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen,
            BootstrapMethods bootstrapMethods
    ) {
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();

        var methodName = instruction.getMethodName(constantPoolGen);

        var onEval = instructionHandle.getPrev();

        var httpMethod = getHttpMethod(methodName);

        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var arguments = new CallResult[argumentTypes.length];
        for (int i = argumentTypes.length; i > 0; i--) {
            var evalResult = eval(object, onEval, constantPoolGen, bootstrapMethods);
            arguments[i - 1] = evalResult;
            onEval = evalResult.getLastInstruction().getPrev();
        }

        var url = String.valueOf(arguments[0].getResult());
        return HttpMethod.builder().method(httpMethod).url(url).build();
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
