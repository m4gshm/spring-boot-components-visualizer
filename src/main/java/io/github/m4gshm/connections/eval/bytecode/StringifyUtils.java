package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.bytecode.EvalBytecode.ParameterValue;
import io.github.m4gshm.connections.eval.result.*;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.*;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.eval.bytecode.EvalBytecode.collapse;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecode.expand;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.toClass;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.toClasses;
import static io.github.m4gshm.connections.eval.bytecode.InvokeDynamicUtils.getBootstrapMethod;
import static io.github.m4gshm.connections.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodInfo;
import static io.github.m4gshm.connections.eval.bytecode.LocalVariableUtils.*;
import static io.github.m4gshm.connections.eval.result.Result.*;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.*;

@UtilityClass
public class StringifyUtils {

    public static Result stringifyUnresolved(Result current, EvalBytecodeException ex) {
        if (ex instanceof NoCallException) {
            throw ex;
        }
        var wrapped = getWrapped(current);
        if (wrapped != null) {
            current = wrapped;
        }
        if (current instanceof Stub) {
            var s = (Stub) current;
            var stubbed = s.getStubbed();
            return stringifyUnresolved(stubbed, ex);
        } else if (current instanceof Variable) {
            var variable = (Variable) current;
            return stringifyVariable(variable);
        } else if (current instanceof Delay) {
            return stringifyDelay((Delay) current, ex);
        }
        throw new UnresolvedResultException("bad stringify", current);
    }

    private static Result stringifyDelay(Delay delay, EvalBytecodeException ex) {
//        if (ex instanceof IllegalInvokeException) {
//            var illegalInvokeException = (IllegalInvokeException) ex;
//            var unresolved = illegalInvokeException.getResult();
//            if (unresolved == delay) {
//                System.out.println(unresolved);
//            }
//        }
        var instructionHandle = delay.getFirstInstruction();
        var instruction = instructionHandle.getInstruction();
        var eval = delay.getEvalContext();
        var constantPoolGen = eval.getConstantPoolGen();
        var constantPool = constantPoolGen.getConstantPool();
        if (instruction instanceof INVOKEDYNAMIC) {
            var invokedynamic = (INVOKEDYNAMIC) instruction;
            var bootstrapMethods = eval.getBootstrapMethods();

            var bootstrapMethod = getBootstrapMethod(invokedynamic, bootstrapMethods, constantPool);
            var bootstrapMethodInfo = getBootstrapMethodInfo(bootstrapMethod, constantPool);

            var className = bootstrapMethodInfo.getClassName();
            var methodName = bootstrapMethodInfo.getMethodName();
            var stringConcatenation = "java.lang.invoke.StringConcatFactory".equals(className) && "makeConcatWithConstants".equals(methodName);
            if (stringConcatenation) {
                //arg1+arg2
                var argumentClasses = toClasses(invokedynamic.getArgumentTypes(constantPoolGen));
                var arguments = eval.evalArguments(instructionHandle, argumentClasses.length, delay);

                return eval.callInvokeDynamic(methodName, delay, arguments, argumentClasses,
                        StringifyUtils::stringifyUnresolved, (parameters, parent) -> {
                            var values = getValues(parameters, StringifyUtils::stringifyUnresolved);
                            var string = Stream.of(values).map(String::valueOf).reduce(String::concat).orElse("");
                            var lastInstruction = delay.getLastInstruction();
                            return invoked(string, lastInstruction, lastInstruction, eval, delay, parameters);
                        });
            } else {
                var argumentClasses = toClasses(invokedynamic.getArgumentTypes(constantPoolGen));
                var arguments = eval.evalArguments(instructionHandle, argumentClasses.length, delay);

                var result = eval.callInvokeDynamic(methodName, delay, arguments, argumentClasses,
                        StringifyUtils::stringifyUnresolved, (parameters, parent) -> {
                            var values = getValues(parameters, StringifyUtils::stringifyUnresolved);
                            var string = Stream.of(values).map(String::valueOf).reduce(String::concat).orElse("");
                            var lastInstruction = delay.getLastInstruction();
                            return invoked(string, lastInstruction, lastInstruction, eval, delay, parameters);
                        });
                return result;
            }
        } else if (instruction instanceof INVOKEINTERFACE || instruction instanceof INVOKEVIRTUAL) {
            var invokeInstruction = (InvokeInstruction) instruction;
            var methodName = invokeInstruction.getMethodName(constantPoolGen);
            var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
            var argumentClasses = toClasses(argumentTypes);

            var argumentsAmount = argumentTypes.length;
            var arguments = eval.evalArguments(instructionHandle, argumentsAmount, delay);
            var invokeObject = eval.evalInvokeObject(invokeInstruction, arguments, delay);
            var objectClass = toClass(invokeInstruction.getClassName(constantPoolGen));

            return eval.callInvokeVirtual(instructionHandle, delay, invokeObject, arguments,
                    argumentClasses, StringifyUtils::stringifyUnresolved, (parameters, lastInstruction) -> {
                        var object = parameters.get(0);
                        var args = parameters.subList(1, parameters.size());
                        return stringifyInvokeResult(delay, objectClass, methodName, object, args);
                    });
        } else if (instruction instanceof INVOKESTATIC) {
            var invokeInstruction = (InvokeInstruction) instruction;
            var methodName = invokeInstruction.getMethodName(constantPoolGen);
            var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
            var argumentClasses = toClasses(argumentTypes);

            var argumentsAmount = argumentTypes.length;
            var arguments = eval.evalArguments(instructionHandle, argumentsAmount, delay);
            var objectClass = toClass(invokeInstruction.getClassName(constantPoolGen));

            return eval.callInvokeStatic(delay, arguments, argumentClasses,
                    StringifyUtils::stringifyUnresolved, (parameters, lastInstruction) -> {
                        return stringifyInvokeResult(delay, objectClass, methodName, null, parameters);
                    });
        } else if (instruction instanceof INVOKESPECIAL) {
            var invokeInstruction = (InvokeInstruction) instruction;
            var methodName = invokeInstruction.getMethodName(constantPoolGen);
            var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
            var argumentClasses = toClasses(argumentTypes);

            var objectClass = toClass(invokeInstruction.getClassName(constantPoolGen));
            var argumentsAmount = argumentTypes.length;
            var arguments = eval.evalArguments(instructionHandle, argumentsAmount, null);
            var invokeObject = eval.evalInvokeObject(invokeInstruction, arguments, delay);
            return eval.callInvokeSpecial(delay, invokeObject, arguments, argumentClasses,
                    StringifyUtils::stringifyUnresolved, (parameters, lastInstruction) -> {
                        if ("<init>".equals(methodName)) {
                            return stringifyInvokeNew(delay, objectClass, methodName, parameters);
                        } else {
                            var object = parameters.get(0);
                            var args = parameters.subList(1, parameters.size());
                            return stringifyInvokeResult(delay, objectClass, methodName, object, args);
                        }
                    });
        } else if (instruction instanceof ArithmeticInstruction) {
            var first = eval.eval(eval.getPrev(instructionHandle), delay);
            var second = instruction.consumeStack(constantPoolGen) == 2
                    ? eval.eval(eval.getPrev(first.getLastInstruction())) : null;
            var strings = stringifyArithmetic((ArithmeticInstruction) instruction, first, second, eval);
            var lastInstruction = second != null ? second.getLastInstruction() : first.getLastInstruction();
            var values = strings.stream()
                    .map(v -> constant(v, instructionHandle, lastInstruction, eval, delay, first, second))
                    .collect(toList());
            return collapse(values, instructionHandle, lastInstruction, delay.getMethod().getConstantPool(), eval);
        } else if (instruction instanceof ArrayInstruction) {
            var element = eval.eval(eval.getPrev(instructionHandle), delay);
            var index = eval.eval(eval.getPrev(element.getLastInstruction()), delay);
            var array = eval.eval(eval.getPrev(index.getLastInstruction()), delay);
            var result = array.getValue(StringifyUtils::stringifyUnresolved);
            var lastInstruction = array.getLastInstruction();
            return constant(result, lastInstruction, lastInstruction, eval, delay, element, index, array);
        } else if (instruction instanceof LoadInstruction) {
            var aload = (LoadInstruction) instruction;
            var aloadIndex = aload.getIndex();
            var localVariables = getLocalVariables(eval.getMethod(), aloadIndex);
            var localVariable = findLocalVariable(eval.getMethod(), localVariables, instructionHandle);

            var name = localVariable != null ? localVariable.getName() : null;
            if ("this".equals(name)) {
                var value = eval.getObject();
                return constant(value, instructionHandle, instructionHandle, eval, delay);
            }

            var storeResults = eval.findStoreInstructionResults(instructionHandle, localVariables, aloadIndex, delay);

            var strings = storeResults.stream()
//                    .flatMap(storeResult -> expand(eval.resolve(storeResult, StringifyUtils::stringifyUnresolved)).stream())
                    .flatMap(storeResult -> {
                        return storeResult
                                .getValue(StringifyUtils::stringifyUnresolved).stream()
                                .map(String::valueOf)
                                .map(s -> constant(s, instructionHandle, instructionHandle, eval, delay, storeResult));
                    })
                    .collect(toList());

            return strings.size() == 1 ? strings.get(0)
                    : multiple(strings, instructionHandle, instructionHandle, eval);
        }
        throw new UnresolvedResultException("bad stringify delay", delay);
    }

    private static Const stringifyInvokeResult(Delay delay, Class<?> objectClass, String methodName,
                                               ParameterValue object, List<ParameterValue> resolvedArguments
    ) {
        var argValues = getArgValues(resolvedArguments);
        var objectValue = object != null ? getValue(object, StringifyUtils::stringifyUnresolved) : null;
        var string = stringifyMethodCall(objectClass, (String) objectValue, methodName, stringifyArguments(argValues));
        var lastInstruction = delay.getLastInstruction();
        return invoked(string, lastInstruction, lastInstruction, delay.getEvalContext(), delay, resolvedArguments);
    }

    private static Const stringifyInvokeNew(Delay delay, Class<?> objectClass, String methodName,
                                            List<ParameterValue> resolvedArguments
    ) {
        var argValues = getArgValues(resolvedArguments);
        var string = stringifyNewCall(objectClass, stringifyArguments(argValues));
        var lastInstruction = delay.getLastInstruction();
        return invoked(string, lastInstruction, lastInstruction, delay.getEvalContext(), delay, resolvedArguments);
    }

    private static Object[] getArgValues(List<ParameterValue> resolvedArguments) {
        var variables = resolvedArguments.stream()
                .filter(a -> a.getParameter() instanceof Variable)
                .collect(toList());
        var args = !variables.isEmpty() ? variables : resolvedArguments;
        return getValues(args, StringifyUtils::stringifyUnresolved);
    }


    public static Object[] getValues(List<ParameterValue> parameterValues, Resolver unevaluatedHandler) {
        return parameterValues.stream().map(pv -> getValue(pv, unevaluatedHandler)).toArray(Object[]::new);
    }

    private static Object getValue(ParameterValue pv, Resolver unevaluatedHandler) {
        var exception = pv.getException();
        var parameter = pv.getParameter();
        if (exception != null) {
            var resolved = unevaluatedHandler.resolve(parameter, exception);
            return resolved.getValue();
        }
        Object value = pv.getValue();
        if (value == null) {
            return null;
        }
        var valueClass = value.getClass();
        var packageName = valueClass.getPackageName();
        if (valueClass.isPrimitive() || packageName.startsWith("java")) {
            return value;
        }
        var instructionHandle = parameter.getFirstInstruction();
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof LoadInstruction) {
            var loadInst = (LoadInstruction) instruction;
            var localVariable = getLocalVariable(parameter.getMethod(), loadInst.getIndex(),
                    instructionHandle);
            if (localVariable != null && localVariable.getName() != null) {
                return localVariable.getName();
            }
        }
        var simpleName = value.getClass().getSimpleName();
        int length = simpleName.length();
        var classAsVar = length > 2 ? simpleName.substring(0, 3).toLowerCase() + (length > 3
                ? simpleName.substring(1) : "") : simpleName;
        return classAsVar;
    }

    private static Const stringifyVariable(Variable variable) {
        var methodName = variable.getMethod().getName();
        var componentType = variable.getComponentType().getSimpleName();
        var value = "{" + componentType + "." + methodName + "(" + "{" + variable.getName() + "}" + ")" + "}";
        var lastInstruction = variable.getLastInstruction();
        return constant(value, lastInstruction, lastInstruction, variable.getEvalContext(), variable);
    }

    private static String stringifyMethodCall(Class<?> objectClass, String object, String methodName, String arguments) {
        return "{" + (object != null ? object : objectClass.getSimpleName()) + "." + methodName + "(" + arguments + ")" + "}";
    }

    private static String stringifyNewCall(Class<?> objectClass, String arguments) {
        return "{" + "new " + objectClass.getSimpleName() + "(" + arguments + ")" + "}";
    }


    private static String stringifyArguments(Object... arguments) {
        return Stream.of(arguments).map(a -> (String) a).reduce("", (l, r) -> (l.isEmpty() ? "" : l + ", ") + r);
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
        return expand(result).stream().flatMap(f -> f.getValue(StringifyUtils::stringifyUnresolved).stream())
                .map(v -> "-" + v).collect(toList());
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
        return invokeVariants(s(first, eval), expand(second), (a, b) -> a + ">>>" + b);
    }

    private static List<String> shiftRight(Result first, Result second, EvalBytecode eval) {
        return invokeVariants(s(first, eval), expand(second), (a, b) -> a + ">>" + b);
    }

    private static List<String> shiftLeft(Result first, Result second, EvalBytecode eval) {
        return invokeVariants(s(first, eval), expand(second), (a, b) -> a + "<<" + b);
    }

    private static List<String> invoke(Result first, Result second, BiFunction<String, String, String> op) {
        return invokeVariants(expand(first), expand(second), op);
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
        var value2Variants = expand(result);
        return value2Variants.stream()
                .flatMap(resultVariant -> resultVariant.getValue(StringifyUtils::stringifyUnresolved).stream()
                        .map(value -> value instanceof Number
                                ? (((Number) value).longValue() & 0X1f) + ""
                                : value + "")
                        .map(v -> {
                            var firstInstruction = result.getFirstInstruction();
                            var lastInstruction = result.getLastInstruction();
                            return constant(v, firstInstruction, lastInstruction, eval, result, resultVariant);
                        }))
                .collect(toList());
    }
}
