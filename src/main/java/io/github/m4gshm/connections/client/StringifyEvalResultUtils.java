package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.INVOKEDYNAMIC;

import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.constant;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.getClassByName;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.toClasses;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethod;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethodInfo;

@UtilityClass
public class StringifyEvalResultUtils {

    public static final Function<Result, Result> STRINGIFY_UNRESOLVED = StringifyEvalResultUtils::stringifyUnresolved;

    public static Result stringifyUnresolved(Result result) {
        if (result instanceof Result.Stub) {
            var s = (Result.Stub) result;
            var stubbed = s.getStubbed();
            return stringifyUnresolved(stubbed);
        } else if (result instanceof Result.Variable) {
            var variable = (Result.Variable) result;
            var name = variable.getName();
            var classByName = getClassByName(variable.getType().getClassName());
            if (CharSequence.class.isAssignableFrom(classByName)) {
                return constant("{" + name + "}", variable.getLastInstruction(), variable.getEvalContext(), result);
            }
        } else if (result instanceof Result.Delay) {
            var delay = (Result.Delay) result;
            var instructionHandle = delay.getFirstInstruction();
            var instruction = instructionHandle.getInstruction();
            if (instruction instanceof INVOKEDYNAMIC) {
                var invokedynamic = (INVOKEDYNAMIC) instruction;
                var eval = delay.getEvalContext();
                var constantPoolGen = eval.getConstantPoolGen();
                var bootstrapMethods = eval.getBootstrapMethods();

                var constantPool = constantPoolGen.getConstantPool();
                var bootstrapMethod = getBootstrapMethod(invokedynamic, bootstrapMethods, constantPool);
                var bootstrapMethodInfo = getBootstrapMethodInfo(bootstrapMethod, constantPool);

                var stringConcatenation =
                        "java.lang.invoke.StringConcatFactory".equals(bootstrapMethodInfo.getClassName()) &&
                                "makeConcatWithConstants".equals(bootstrapMethodInfo.getMethodName());
                if (stringConcatenation) {
                    //arg1+arg2
                    var argumentClasses = toClasses(invokedynamic.getArgumentTypes(constantPoolGen));
                    var arguments = eval.evalArguments(instructionHandle, argumentClasses.length, delay);

                    return eval.callInvokeDynamic(instructionHandle, delay, arguments, argumentClasses,
                            StringifyEvalResultUtils::forceStringifyVariables, (objects, parent) -> {
                                var string = Stream.of(objects).map(String::valueOf).reduce(String::concat).orElse("");
                                return constant(string, delay.getLastInstruction(), delay.getEvalContext(), result);
                            });
                }
            }
        }
        return result;
    }

    public static Result forceStringifyVariables(Result result) {
        if (result instanceof Result.Stub) {
            var s = (Result.Stub) result;
            var stubbed = s.getStubbed();
            return forceStringifyVariables(stubbed);
        } else if (result instanceof Result.Variable) {
            var variable = (Result.Variable) result;
            var name = variable.getName();
            return constant("{" + name + "}", variable.getLastInstruction(), variable.getEvalContext(), result);
        }
        return result;
    }
}
