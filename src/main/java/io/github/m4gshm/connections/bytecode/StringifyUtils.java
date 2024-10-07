package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.ParameterValue;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Delay;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.*;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.*;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.getResultVariants;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.*;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethod;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethodInfo;
import static java.util.Collections.max;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.*;
import static org.apache.bcel.Const.LXOR;

@UtilityClass
public class StringifyUtils {

    public static Result stringifyUnresolved(Result current, Class<?> expectedResultClass, EvalBytecodeException unresolved) {
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
            return stringifyVariable(variable);
        } else if (current instanceof Result.CallArg) {
            return stringifyUnresolved(((Result.CallArg) current).getResult(), expectedResultClass, unresolved);
        } else if (current instanceof Delay) {
            return stringifyDelay((Delay) current, expectedResultClass);
        }
        throw new UnevaluatedResultException("bad stringify, expectedResultClass " + expectedResultClass, current);
    }

    private static Result stringifyDelay(Delay delay, Class<?> expectedResultClass) {
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
                        StringifyUtils::stringifyUnresolved, (parameters, parent) -> {
                            var values = getValues(parameters, StringifyUtils::stringifyUnresolved);
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
                    argumentClasses, StringifyUtils::stringifyUnresolved, (parameters, lastInstruction) -> {
                        var args = parameters.subList(1, parameters.size());
                        return stringifyInvokeResult(delay, objectClass, methodName, args);
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
                    StringifyUtils::stringifyUnresolved, (parameters, lastInstruction) -> {
                        return stringifyInvokeResult(delay, objectClass, methodName, parameters);
                    });
        } else if (instruction instanceof ArithmeticInstruction) {
            var eval = delay.getEvalContext();
            var first = eval.eval(eval.getPrev(instructionHandle), delay);
            var second = instruction.consumeStack(eval.getConstantPoolGen()) == 2
                    ? eval.eval(eval.getPrev(first.getLastInstruction())) : null;
            var strings = stringifyArithmetic((ArithmeticInstruction) instruction, first, second, eval);
            var lastInstruction = second != null ? second.getLastInstruction() : first.getLastInstruction();
            var values = strings.stream()
                    .map(v -> constant(v, instructionHandle, lastInstruction, eval, delay))
                    .collect(toList());
            return values.size() == 1 ? values.get(0) : multiple(values, instructionHandle, lastInstruction, delay);
        }
        throw new UnevaluatedResultException("bad stringify delay, expectedResultClass " + expectedResultClass, delay);
    }

    private static Result.Const stringifyInvokeResult(Delay delay, Class<?> objectClass, String methodName,
                                                      List<ParameterValue> resolvedArguments
    ) {
        var variables = resolvedArguments.stream()
                .filter(a -> a.getParameter() instanceof Variable)
                .collect(toList());
        var args = !variables.isEmpty() ? variables : resolvedArguments;
        var values = getValues(args, StringifyUtils::stringifyUnresolved);
        var string = stringifyMethodCall(objectClass, methodName, stringifyArguments(values));
        var lastInstruction = delay.getLastInstruction();
        return invoked(string, lastInstruction, lastInstruction, delay.getEvalContext(), delay, resolvedArguments);
    }

    public static Object[] getValues(List<ParameterValue> parameterValues, UnevaluatedResolver unevaluatedHandler) {
        return parameterValues.stream().map(pv -> {
            var exception = pv.getException();
            if (exception != null) {
                var resolved = unevaluatedHandler.resolve(pv.getParameter(), pv.getExpectedResultClass(), exception);
                return resolved.getValue();
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

    private static String stringifyMethodCall(Class<?> objectClass, String methodName, String arguments) {
        return "{" + objectClass.getSimpleName() + "." + methodName + "(" + arguments + ")" + "}";
    }

    private static String stringifyArguments(Object[] arguments) {
        return Stream.of(arguments).map(a -> (String) a).reduce("", (l, r) -> (l.isEmpty() ? "" : l + ",") + r);
    }

    public static List<String> stringifyArithmetic(ArithmeticInstruction instruction,
                                                   Result first, Result second, EvalBytecode eval) {
        var opcode = instruction.getOpcode();
        switch (opcode) {
            case DADD:
            case FADD:
                return invoke(first, second, (a1, b1) -> a1 + "+" + b1);
            case DDIV:
            case FDIV:
            case IDIV:
            case LDIV:
                return invoke(first, second, (a1, b1) -> a1 + "/" + b1);
            case DMUL:
            case FMUL:
            case IMUL:
            case LMUL:
                return invoke(first, second, (a, b) -> a + "*" + b);
            case DNEG:
            case INEG:
            case FNEG:
            case LNEG:
                return neg(first);
            case DREM:
            case FREM:
            case IREM:
            case LREM:
                return rem(first, second);
            case DSUB:
            case FSUB:
            case ISUB:
            case LSUB:
                return sub(first, second);
            case IADD:
            case LADD:
                return add(first, second);
            case IAND:
            case LAND:
                return and(first, second);
            case IOR:
            case LOR:
                return or(first, second);
            case ISHL:
            case LSHL:
                return shiftLeft(first, second, eval);
            case ISHR:
            case LSHR:
                return shiftRight(first, second, eval);
            case IUSHR:
            case LUSHR:
                return unsignedShiftRight(first, second, eval);
            case IXOR:
            case LXOR:
                return xor(first, second);
            default:
                throw new IllegalStateException("unsupported arithmetic op " + opcode);
        }
    }

    private static List<String> neg(Result result) {
        return getResultVariants(result).stream().map(f -> "-" + f.getValue(StringifyUtils::stringifyUnresolved)).collect(toList());
    }

    private static List<String> xor(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "^" + b);
    }

    private static List<String> or(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "|" + b);
    }

    private static List<String> and(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "&" + b);
    }

    private static List<String> add(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "+" + b);
    }

    private static List<String> sub(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "-" + b);
    }

    private static List<String> rem(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "%" + b);
    }

    private static List<String> unsignedShiftRight(Result first, Result second, EvalBytecode eval) {
        return invokeVariants(s(first, eval), getResultVariants(second), (a, b) -> a + ">>>" + b);
    }

    private static List<String> shiftRight(Result first, Result second, EvalBytecode eval) {
        return invokeVariants(s(first, eval), getResultVariants(second), (a, b) -> a + ">>" + b);
    }

    private static List<String> shiftLeft(Result first, Result second, EvalBytecode eval) {
        return invokeVariants(s(first, eval), getResultVariants(second), (a, b) -> a + "<<" + b);
    }

    private static List<String> invoke(Result first, Result second, BiFunction<String, String, String> op) {
        return invokeVariants(getResultVariants(first), getResultVariants(second), op);
    }

    private static List<String> invokeVariants(List<Result> firstVariants, List<Result> secondVariants,
                                               BiFunction<String, String, String> op) {
        return secondVariants.stream().flatMap(s -> firstVariants.stream().flatMap(f -> {
            var values1 = s.getValue(StringifyUtils::stringifyUnresolved);
            var values2 = f.getValue(StringifyUtils::stringifyUnresolved);
            return values1.stream().flatMap(value1 -> values2.stream().map(value2 -> op.apply(value1 + "", value2 + "")));
        })).collect(toList());
    }

    private static List<Result> s(Result result, EvalBytecode eval) {
        var value2Variants = getResultVariants(result);
        return value2Variants.stream()
                .map(value2 -> value2.getValue((StringifyUtils::stringifyUnresolved)))
                .map(value2 -> value2 instanceof Number
                        ? (((Number) value2).longValue() & 0X1f) + ""
                        : value2 + "")
                .map(v -> constant(v, result.getFirstInstruction(), result.getLastInstruction(), eval, result))
                .collect(toList());
    }
}
