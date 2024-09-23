package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable;
import io.github.m4gshm.connections.bytecode.UnevaluatedResultException;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.INVOKEDYNAMIC;

import java.util.stream.Stream;

import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.constant;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.getClassByName;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.toClasses;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethod;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethodInfo;

@UtilityClass
public class StringifyEvalResultUtils {

    public static final Result.UnevaluatedResolver STRINGIFY_UNRESOLVED = StringifyEvalResultUtils::stringifyUnresolved;

    public static Result stringifyUnresolved(Result current, Result unresolved) {
        if (current instanceof Result.Stub) {
            var s = (Result.Stub) current;
            var stubbed = s.getStubbed();
            return stringifyUnresolved(stubbed, unresolved);
        } else if (current instanceof Variable) {
            var variable = (Variable) current;
            var name = variable.getName();
            var classByName = getClassByName(variable.getType().getClassName());
            if (CharSequence.class.isAssignableFrom(classByName)) {
                return constant("{" + name + "}", variable.getLastInstruction(), variable.getEvalContext(), current);
            }
        } else if (current instanceof Result.Delay) {
            var delay = (Result.Delay) current;
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
                                return constant(string, delay.getLastInstruction(), delay.getEvalContext(), current);
                            });
                }
            }
        }

        throw new UnevaluatedResultException("bad stringify", current);
    }

    public static Result forceStringifyVariables(Result result, Result unresolved) {
        if (result instanceof Result.Stub) {
            var s = (Result.Stub) result;
            var stubbed = s.getStubbed();
            return forceStringifyVariables(stubbed, unresolved);
        } else if (result instanceof Variable) {
            var variable = (Variable) result;
            var name = variable.getName();
            return constant("{" + name + "}", variable.getLastInstruction(), variable.getEvalContext(), result);
        }
        return result;
    }
}
