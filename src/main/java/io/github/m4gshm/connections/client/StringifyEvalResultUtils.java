package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Arguments;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Delay;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.InvokeWithUnresolvedParameters;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable;
import io.github.m4gshm.connections.bytecode.EvalBytecodeException;
import io.github.m4gshm.connections.bytecode.UnevaluatedResultException;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.constant;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.getWrapped;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.*;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethod;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethodInfo;
import static java.util.Arrays.copyOfRange;

@UtilityClass
public class StringifyEvalResultUtils {

    public static final Result.UnevaluatedResolver STRINGIFY_UNRESOLVED = StringifyEvalResultUtils::stringifyUnresolved;

    public static Result stringifyUnresolved(Result current, List<Class<?>> expectedResultClass, EvalBytecodeException unresolved) {
        var wrapped = getWrapped(current);
        if (wrapped != null) {
            current = wrapped;
        }
        if (current instanceof Result.Stub) {
            var s = (Result.Stub) current;
            var stubbed = s.getStubbed();
            return stringifyUnresolved(stubbed, expectedResultClass, unresolved);
        } else if (current instanceof Variable) {
            var variable = (Variable) current;
            var name = variable.getName();
            var resultClass = expectedResultClass != null && !expectedResultClass.isEmpty()
                    ? expectedResultClass.get(expectedResultClass.size() - 1)
                    : getClassByName(variable.getType().getClassName());
            if (CharSequence.class.isAssignableFrom(resultClass)) {
                return stringifyVariable(current, name, variable);
            }
        } else if (current instanceof InvokeWithUnresolvedParameters) {
            var invokeWithUnresolvedParameters = (InvokeWithUnresolvedParameters) current;
            return stringifyUnresolved(invokeWithUnresolvedParameters.getDelay(), expectedResultClass, unresolved);
        } else if (current instanceof Result.CallArg) {
            return stringifyUnresolved(((Result.CallArg) current).getResult(), expectedResultClass, unresolved);
        } else if (current instanceof Delay) {
            return stringifyDelay((Delay) current, expectedResultClass);
        }
        throw new UnevaluatedResultException("bad stringify, expectedResultClass " + expectedResultClass, current);
    }

    private static Result stringifyDelay(Delay delay, List<Class<?>> expectedResultClass) {
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

                return eval.callInvokeDynamic(instructionHandle, delay, expectedResultClass, arguments, argumentClasses,
                        StringifyEvalResultUtils::forceStringifyVariables, (objects, parent) -> {
                            var string = Stream.of(objects).map(String::valueOf).reduce(String::concat).orElse("");
                            return constant(string, delay.getLastInstruction(), delay.getEvalContext(), delay);
                        });
            }
        } else if (instruction instanceof INVOKEINTERFACE || instruction instanceof INVOKEVIRTUAL) {
            var invokeInstruction = (InvokeInstruction) instruction;
            var eval = delay.getEvalContext();
            var constantPoolGen = eval.getConstantPoolGen();
            var methodName = invokeInstruction.getMethodName(constantPoolGen);
            var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
            var argumentClasses = toClasses(argumentTypes);

            var argumentsAmount = argumentTypes.length;
            var arguments = eval.evalArguments(instructionHandle, argumentsAmount, delay);
            var invokeObject = eval.evalInvokeObject(invokeInstruction, arguments, delay);

            var objectClass = toClass(invokeInstruction.getClassName(constantPoolGen));

            return eval.callInvokeVirtual(instructionHandle, delay, invokeObject, expectedResultClass, arguments,
                    argumentClasses, StringifyEvalResultUtils::forceStringifyVariables, (parameters, lastInstruction) -> {
                        var argValues = copyOfRange(parameters, 1, parameters.length);
                        return stringifyInvokeResult(delay, objectClass, methodName, arguments, argValues);
                    });
        } else if (instruction instanceof INVOKESTATIC) {
            var invokeInstruction = (InvokeInstruction) instruction;
            var eval = delay.getEvalContext();
            var constantPoolGen = eval.getConstantPoolGen();
            var methodName = invokeInstruction.getMethodName(constantPoolGen);
            var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
            var argumentClasses = toClasses(argumentTypes);

            var argumentsAmount = argumentTypes.length;
            var arguments = eval.evalArguments(instructionHandle, argumentsAmount, delay);
            var objectClass = toClass(invokeInstruction.getClassName(constantPoolGen));

            return eval.callInvokeStatic(instructionHandle, delay, expectedResultClass, arguments, argumentClasses,
                    StringifyEvalResultUtils::forceStringifyVariables, (parameters, lastInstruction) -> {
                        return stringifyInvokeResult(delay, objectClass, methodName, arguments, parameters);
                    });
        }
        throw new UnevaluatedResultException("bad stringify delay, expectedResultClass " + expectedResultClass, delay);
    }

    private static Result.Const stringifyInvokeResult(Delay delay, Class<?> objectClass, String methodName,
                                                      Arguments arguments, Object[] values) {
        var variableValues = getVariableValues(values, arguments.getArguments());
        final var string = variableValues.size() > 1
                ? stringifyMethodCall(objectClass, methodName, stringifyArguments(variableValues.toArray()))
                : variableValues.size() == 1 ? "" + variableValues.get(0)
                : stringifyMethodCall(objectClass, methodName, stringifyArguments(values));
        return constant(string, delay.getLastInstruction(), delay.getEvalContext(), delay);
    }

    private static ArrayList<Object> getVariableValues(Object[] values, List<Result> arguments) {
        var variables = new ArrayList<Object>();
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (argument instanceof Variable) {
                var parameter = values[i];
                variables.add(parameter);
            }
        }
        return variables;
    }

    private static Result.Const stringifyVariable(Result current, String name, Variable variable) {
        return constant("{" + name + "}", variable.getLastInstruction(), variable.getEvalContext(), current);
    }

    private static String stringifyMethodCall(Class<?> objectClass, String methodName, String stringifiedArguments) {
        return objectClass.getSimpleName() + "." + methodName + "(" + stringifiedArguments + ")";
    }

    private static String stringifyArguments(Object[] arguments) {
        return Stream.of(arguments).map(a -> (String) a).reduce("", (l, r) -> (l.isEmpty() ? "" : l + ",") + r);
    }

    public static Result forceStringifyVariables(Result current, List<Class<?>> expectedResultClass,
                                                 EvalBytecodeException unresolved) {
        var wrapped = getWrapped(current);
        if (wrapped != null) {
            current = wrapped;
        }
        if (current instanceof Result.Stub) {
            var s = (Result.Stub) current;
            var stubbed = s.getStubbed();
            return forceStringifyVariables(stubbed, expectedResultClass, unresolved);
        } else if (current instanceof Variable) {
            var variable = (Variable) current;
            return stringifyVariable(current, variable.getName(), variable);
        } else if (current instanceof Delay) {
            return stringifyDelay((Delay) current, expectedResultClass);
        }
        throw new UnevaluatedResultException("bad stringify, expectedResultClass " + expectedResultClass, current);
    }
}
