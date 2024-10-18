package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.*;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.eval.bytecode.EvalBytecode.*;
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
        if (current instanceof NoCall) {
            current = ((NoCall) current).getDelay();
        }
//        if (ex instanceof NoCallException) {
//            throw ex;
//        }
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
        var eval = delay.getEval();
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

                return callInvokeDynamic((DelayInvoke) delay, argumentClasses, eval, false, (current, ex1) -> {
                            try {
                                Result result = stringifyUnresolved(current, ex1);
                                return result;
                            } catch (Exception e) {
                                throw e;
                            }
                        }, (parameters, parent) -> {
                            var values = getValues(parameters, (current, ex1) -> stringifyUnresolved(current, ex1), eval);
                            var string = Stream.of(values).map(String::valueOf).reduce(String::concat).orElse("");
                            var lastInstruction = delay.getLastInstruction();
                            return invoked(string, lastInstruction, lastInstruction, eval.getComponent(), eval.getMethod(), parameters);
                        });
            } else {
                var argumentClasses = toClasses(invokedynamic.getArgumentTypes(constantPoolGen));
                var result = callInvokeDynamic((DelayInvoke) delay, argumentClasses,
                        eval, true, (current, ex1) -> {
                            try {
                                Result result1 = stringifyUnresolved(current, ex1);
                                return result1;
                            } catch (Exception e) {
                                throw e;
                            }
                        }, (parameters, parent) -> {
                            var values = getValues(parameters, (current, ex1) -> stringifyUnresolved(current, ex1), eval);
                            var string = Stream.of(values).map(String::valueOf).reduce(String::concat).orElse("");
                            var lastInstruction = delay.getLastInstruction();
                            return invoked(string, lastInstruction, lastInstruction, eval.getComponent(), eval.getMethod(), parameters);
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

            return callInvokeVirtual(instructionHandle, (DelayInvoke) delay,
                    argumentClasses, eval, false, (current, ex1) -> {
                        try {
                            Result result = stringifyUnresolved(current, ex1);
                            return result;
                        } catch (Exception e) {
                            throw e;
                        }
                    }, (parameters, lastInstruction) -> {
                        var object = parameters.get(0);
                        var args = parameters.subList(1, parameters.size());
                        return stringifyInvokeResult(delay, objectClass, methodName, object, args, eval);
                    });
        } else if (instruction instanceof INVOKESTATIC) {
            var invokeInstruction = (InvokeInstruction) instruction;
            var methodName = invokeInstruction.getMethodName(constantPoolGen);
            var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
            var argumentClasses = toClasses(argumentTypes);

            var argumentsAmount = argumentTypes.length;
            var arguments = eval.evalArguments(instructionHandle, argumentsAmount, delay);
            var objectClass = toClass(invokeInstruction.getClassName(constantPoolGen));

            return callInvokeStatic((DelayInvoke) delay, argumentClasses,
                    eval, false, (current, ex1) -> {
                        try {
                            Result result = stringifyUnresolved(current, ex1);
                            return result;
                        } catch (Exception e) {
                            throw e;
                        }
                    }, (parameters, lastInstruction) -> {
                        return stringifyInvokeResult(delay, objectClass, methodName, null, parameters, eval);
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
            return callInvokeSpecial((DelayInvoke) delay, argumentClasses,
                    eval, false, (current, ex1) -> {
                        try {
                            Result result = stringifyUnresolved(current, ex1);
                            return result;
                        } catch (Exception e) {
                            throw e;
                        }
                    }, (parameters, lastInstruction) -> {
                        if ("<init>".equals(methodName)) {
                            return stringifyInvokeNew(delay, objectClass, parameters, eval);
                        } else {
                            var object = parameters.get(0);
                            var args = parameters.subList(1, parameters.size());
                            return stringifyInvokeResult(delay, objectClass, methodName, object, args, eval);
                        }
                    });
        } else if (instruction instanceof ArithmeticInstruction) {
            var first = eval.eval(eval.getPrev(instructionHandle), delay);
            var second = instruction.consumeStack(constantPoolGen) == 2
                    ? eval.eval(eval.getPrev(first.getLastInstruction())) : null;
            var strings = stringifyArithmetic((ArithmeticInstruction) instruction, first, second, eval);
            var lastInstruction = second != null ? second.getLastInstruction() : first.getLastInstruction();
            var values = strings.stream()
                    .map(v -> constant(v, instructionHandle, lastInstruction, eval.getComponent(), eval.getMethod(), delay, first, second))
                    .collect(toList());
            return collapse(values, instructionHandle, lastInstruction, delay.getMethod().getConstantPool(), eval.getComponent(), eval.getMethod());
        } else if (instruction instanceof ArrayInstruction) {
            var element = eval.eval(eval.getPrev(instructionHandle), delay);
            var index = eval.eval(eval.getPrev(element.getLastInstruction()), delay);
            var array = eval.eval(eval.getPrev(index.getLastInstruction()), delay);
            var result = array.getValue((current, ex1) -> stringifyUnresolved(current, ex1), eval);
            var lastInstruction = array.getLastInstruction();
            return constant(result, lastInstruction, lastInstruction, eval.getComponent(), eval.getMethod(), delay, element, index, array);
        } else if (instruction instanceof LoadInstruction) {
            var aload = (LoadInstruction) instruction;
            var aloadIndex = aload.getIndex();
            var localVariables = getLocalVariables(eval.getMethod(), aloadIndex);
            var localVariable = findLocalVariable(eval.getMethod(), localVariables, instructionHandle);

            var name = localVariable != null ? localVariable.getName() : null;
            if ("this".equals(name)) {
                var value = eval.getObject();
                return constant(value, instructionHandle, instructionHandle, eval.getComponent(), eval.getMethod(), delay);
            }

            var storeResults = eval.findStoreInstructionResults(instructionHandle, localVariables, aloadIndex, delay);

            var strings = storeResults.stream()
//                    .flatMap(storeResult -> expand(eval.resolve(storeResult, StringifyUtils::stringifyUnresolved)).stream())
                    .flatMap(storeResult -> {
                        try {
                            return storeResult
                                    .getValue((current, ex1) -> {
                                        try {
                                            return stringifyUnresolved(current, ex1);
                                        } catch (Exception e) {
                                            throw e;
                                        }
                                    }, eval).stream()
                                    .map(String::valueOf)
                                    .map(s -> constant(s, instructionHandle, instructionHandle, eval.getComponent(), eval.getMethod(), delay, storeResult));
                        } catch (Exception e) {
                            throw e;
                        }
                    })
                    .collect(toList());

            return strings.size() == 1 ? strings.get(0)
                    : multiple(strings, instructionHandle, instructionHandle, eval.getComponent(), eval.getMethod());
        }
        throw new UnresolvedResultException("bad stringify delay", delay);
    }

    private static Constant stringifyInvokeResult(Delay delay, Class<?> objectClass, String methodName,
                                                  ParameterValue object, List<ParameterValue> resolvedArguments, EvalBytecode eval
    ) {
        var argValues = getArgValues(resolvedArguments, eval);
        var objectValue = object != null ? getValue(object, (current, ex) -> stringifyUnresolved(current, ex), eval) : null;
        var string = stringifyMethodCall(objectClass, (String) objectValue, methodName, stringifyArguments(argValues));
        var lastInstruction = delay.getLastInstruction();
        var parameterValues = concatCallParameters(object, resolvedArguments);
        return invoked(string, lastInstruction, lastInstruction, eval.getComponent(), eval.getMethod(), parameterValues);
    }

    private static List<ParameterValue> concatCallParameters(ParameterValue object, List<ParameterValue> arguments) {
        if (object == null) {
            return arguments;
        }
        var parameterValues = new ArrayList<ParameterValue>(arguments.size() + 1);
        parameterValues.add(object);
        parameterValues.addAll(arguments);
        return parameterValues;
    }

    private static Constant stringifyInvokeNew(Delay delay, Class<?> objectClass, List<ParameterValue> resolvedArguments, EvalBytecode eval
    ) {
        var argValues = getArgValues(resolvedArguments, eval);
        var string = stringifyNewCall(objectClass, stringifyArguments(argValues));
        var lastInstruction = delay.getLastInstruction();
        return invoked(string, lastInstruction, lastInstruction, eval.getComponent(), eval.getMethod(), resolvedArguments);
    }

    private static Object[] getArgValues(List<ParameterValue> resolvedArguments, EvalBytecode eval) {
        var variables = resolvedArguments.stream()
                .filter(a -> a.getParameter() instanceof Variable)
                .collect(toList());
        var args = !variables.isEmpty() ? variables : resolvedArguments;
        return getValues(args, (current, ex) -> stringifyUnresolved(current, ex), eval);
    }


    private static Object[] getValues(List<ParameterValue> parameterValues, Resolver unevaluatedHandler, EvalBytecode eval) {
        return parameterValues.stream().map(pv -> getValue(pv, unevaluatedHandler, eval)).toArray(Object[]::new);
    }

    private static Object getValue(ParameterValue pv, Resolver unevaluatedHandler, EvalBytecode eval) {
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
                ? simpleName.substring(3) : "") : simpleName;
        return classAsVar;
    }

    private static Constant stringifyVariable(Variable variable) {
        var methodName = variable.getMethod().getName();
        var componentType = variable.getComponentType().getSimpleName();
        var value = "{" + componentType + "." + methodName + "(" + "{" + variable.getName() + "}" + ")" + "}";
        var lastInstruction = variable.getLastInstruction();
        return constant(value, lastInstruction, lastInstruction, variable.getEvalContext().getComponent(), variable.getEvalContext().getMethod(), variable);
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
                return invoke(first, second, (a1, b1) -> a1 + "+" + b1, eval);
            case DDIV:
            case FDIV:
            case IDIV:
            case LDIV:
                return invoke(first, second, (a1, b1) -> a1 + "/" + b1, eval);
            case DMUL:
            case FMUL:
            case IMUL:
            case LMUL:
                return invoke(first, second, (a, b) -> a + "*" + b, eval);
            case DNEG:
            case INEG:
            case FNEG:
            case LNEG:
                return neg(first, eval);
            case DREM:
            case FREM:
            case IREM:
            case LREM:
                return rem(first, second, eval);
            case DSUB:
            case FSUB:
            case ISUB:
            case LSUB:
                return sub(first, second, eval);
            case IADD:
            case LADD:
                return add(first, second, eval);
            case IAND:
            case LAND:
                return and(first, second, eval);
            case IOR:
            case LOR:
                return or(first, second, eval);
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
                return xor(first, second, eval);
            default:
                throw new IllegalStateException("unsupported arithmetic op " + opcode);
        }
    }

    private static List<String> neg(Result result, EvalBytecode eval) {
        return expand(result).stream().flatMap(f -> f.getValue((current, ex) -> stringifyUnresolved(current, ex), eval).stream())
                .map(v -> "-" + v).collect(toList());
    }

    private static List<String> xor(Result first, Result second, EvalBytecode eval) {
        return invoke(first, second, (a, b) -> a + "^" + b, eval);
    }

    private static List<String> or(Result first, Result second, EvalBytecode eval) {
        return invoke(first, second, (a, b) -> a + "|" + b, eval);
    }

    private static List<String> and(Result first, Result second, EvalBytecode eval) {
        return invoke(first, second, (a, b) -> a + "&" + b, eval);
    }

    private static List<String> add(Result first, Result second, EvalBytecode eval) {
        return invoke(first, second, (a, b) -> a + "+" + b, eval);
    }

    private static List<String> sub(Result first, Result second, EvalBytecode eval) {
        return invoke(first, second, (a, b) -> a + "-" + b, eval);
    }

    private static List<String> rem(Result first, Result second, EvalBytecode eval) {
        return invoke(first, second, (a, b) -> a + "%" + b, eval);
    }

    private static List<String> unsignedShiftRight(Result first, Result second, EvalBytecode eval) {
        return invokeVariants(s(first, eval), expand(second), (a, b) -> a + ">>>" + b, eval);
    }

    private static List<String> shiftRight(Result first, Result second, EvalBytecode eval) {
        return invokeVariants(s(first, eval), expand(second), (a, b) -> a + ">>" + b, eval);
    }

    private static List<String> shiftLeft(Result first, Result second, EvalBytecode eval) {
        return invokeVariants(s(first, eval), expand(second), (a, b) -> a + "<<" + b, eval);
    }

    private static List<String> invoke(Result first, Result second, BiFunction<String, String, String> op, EvalBytecode eval) {
        return invokeVariants(expand(first), expand(second), op, eval);
    }

    private static List<String> invokeVariants(List<Result> firstVariants, List<Result> secondVariants,
                                               BiFunction<String, String, String> op, EvalBytecode eval) {
        return secondVariants.stream().flatMap(s -> firstVariants.stream().flatMap(f -> {
            var values1 = s.getValue((current, ex) -> stringifyUnresolved(current, ex), eval);
            var values2 = f.getValue((current, ex) -> stringifyUnresolved(current, ex), eval);
            return values1.stream().flatMap(value1 -> values2.stream().map(value2 -> op.apply(value1 + "", value2 + "")));
        })).collect(toList());
    }

    private static List<Result> s(Result result, EvalBytecode eval) {
        var value2Variants = expand(result);
        return value2Variants.stream()
                .flatMap(resultVariant -> resultVariant.getValue((current, ex) -> stringifyUnresolved(current, ex), eval).stream()
                        .map(value -> value instanceof Number
                                ? (((Number) value).longValue() & 0X1f) + ""
                                : value + "")
                        .map(v -> {
                            var firstInstruction = result.getFirstInstruction();
                            var lastInstruction = result.getLastInstruction();
                            return constant(v, firstInstruction, lastInstruction, eval.getComponent(), eval.getMethod(), result, resultVariant);
                        }))
                .collect(toList());
    }
}
