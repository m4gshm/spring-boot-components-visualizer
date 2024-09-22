package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.INVOKEDYNAMIC;

import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.constant;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.getResult;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.*;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.*;
import static java.util.stream.Collectors.toList;

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
                    var argumentTypes = invokedynamic.getArgumentTypes(constantPoolGen);
                    var argumentClasses = getArgumentClasses(argumentTypes);

                    var evalArguments = eval.evalArguments(instructionHandle, argumentTypes,
                            StringifyEvalResultUtils::forceStringifyVariables, delay);
                    var invokeObject = eval.evalInvokeObject(invokedynamic, evalArguments, StringifyEvalResultUtils::forceStringifyVariables, delay);
                    var lastInstruction = invokeObject.getLastInstruction();
                    var argumentsResults = evalArguments.getArguments();

                    var filledVariants = eval.resolveArguments(invokedynamic, argumentTypes, argumentsResults, StringifyEvalResultUtils::forceStringifyVariables);
                    var results = filledVariants.stream().map(firstVariantResults -> {
                        var arguments = eval.getArguments(argumentClasses, firstVariantResults, StringifyEvalResultUtils::forceStringifyVariables);
                        var string = Stream.of(arguments).map(String::valueOf).reduce(String::concat).orElse("");
                        return constant(string, delay.getLastInstruction(), delay.getEvalContext(), result);
                    }).collect(toList());
                    return getResult(instruction, instructionHandle, constantPoolGen, lastInstruction, results, delay);

                    //arg1+arg2
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
