package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.bytecode.EvalBytecode.EvalArguments;
import io.github.m4gshm.connections.bytecode.EvalBytecode.ParameterValue;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Delay;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable;
import io.github.m4gshm.connections.bytecode.EvalBytecodeException;
import io.github.m4gshm.connections.bytecode.UnevaluatedResultException;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.*;

import java.util.List;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.*;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.*;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethod;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethodInfo;
import static java.util.stream.Collectors.toList;

@UtilityClass
public class StringifyEvalResultUtils {

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
            var resultClass = expectedResultClass != null && !expectedResultClass.isEmpty()
                    ? expectedResultClass.get(expectedResultClass.size() - 1)
                    : getClassByName(variable.getType().getClassName());
//            if (CharSequence.class.isAssignableFrom(resultClass)) {
            return stringifyVariable(variable);
//            }
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
                        null, (parameters, parent) -> {
                            var values = getValues(parameters, StringifyEvalResultUtils::stringifyUnresolved);
                            var string = Stream.of(values).map(String::valueOf).reduce(String::concat).orElse("");
                            var lastInstruction = delay.getLastInstruction();
                            return invoked(string, lastInstruction, lastInstruction, delay.getEvalContext(), delay, parameters);
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
                    argumentClasses, null, (parameters, lastInstruction) -> {
                        var args = parameters.subList(1, parameters.size());
                        return stringifyInvokeResult(delay, objectClass, methodName, arguments, args);
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
                    null, (parameters, lastInstruction) -> {
                        return stringifyInvokeResult(delay, objectClass, methodName, arguments, parameters);
                    });
        }
        throw new UnevaluatedResultException("bad stringify delay, expectedResultClass " + expectedResultClass, delay);
    }

    private static Result.Const stringifyInvokeResult(Delay delay, Class<?> objectClass, String methodName,
                                                      EvalArguments evalArguments, List<ParameterValue> resolvedArguments
    ) {
        var variables = resolvedArguments.stream()
                .filter(a -> a.getParameter() instanceof Variable)
                .collect(toList());
        var args = !variables.isEmpty() ? variables : resolvedArguments;
        var values = getValues(args, StringifyEvalResultUtils::stringifyUnresolved);
        var string = stringifyMethodCall(objectClass, methodName, values, stringifyArguments(values));
        var lastInstruction = delay.getLastInstruction();
        return invoked(string, lastInstruction, lastInstruction, delay.getEvalContext(), delay, resolvedArguments);
    }

    public static Object[] getValues(List<ParameterValue> parameterValues, Result.UnevaluatedResolver unevaluatedHandler) {
        return parameterValues.stream().map(pv -> {
            var exception = pv.getException();
            if (exception != null) {
                return unevaluatedHandler.resolve(pv.getParameter(), pv.getExpectedResultClass(), exception).getValue(pv.getExpectedResultClass());
            }
            return pv.getValue();
        }).toArray(Object[]::new);
    }

    private static Result.Const stringifyVariable(Variable variable) {
        var methodName = variable.getMethod().getName();
        var componentType = variable.getComponentType().getSimpleName();
        var value = "{" + componentType + "." + methodName + "(" + "{" + variable.getName() + "}" + ")" + "}";
        var lastInstruction = variable.getLastInstruction();
        return constant(value, lastInstruction, lastInstruction, variable.getEvalContext(), variable);
    }

    private static String stringifyMethodCall(Class<?> objectClass, String methodName, Object[] argsValues, String arguments) {
        return "{" + objectClass.getSimpleName() + "." + methodName + "(" + arguments + ")" + "}";
    }

    private static String stringifyArguments(Object[] arguments) {
        return Stream.of(arguments).map(a -> (String) a).reduce("", (l, r) -> (l.isEmpty() ? "" : l + ",") + r);
    }

}
