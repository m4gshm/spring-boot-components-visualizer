package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.result.*;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.lang.invoke.MethodType;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.eval.bytecode.Eval.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodHandlerAndArguments;
import static io.github.m4gshm.components.visualizer.eval.bytecode.LocalVariableUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.StringifyResolver.Level.full;
import static io.github.m4gshm.components.visualizer.eval.result.Result.*;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.*;

@Slf4j
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PROTECTED)
public class StringifyResolver implements Resolver {

    public static final String THIS = "this";
    Level level;
    boolean failFast;

    public static StringifyResolver newStringify(Level level, boolean failFast) {
        return new StringifyResolver(level, failFast);
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

    public Result stringifyUnresolved(Result current, EvalException ex, Eval eval) {
        var wrapped = getWrapped(current);
        if (wrapped != null) {
            current = wrapped;
        }
        if (current instanceof Stub) {
            var s = (Stub) current;
            var stubbed = s.getStubbed();
            return stringifyUnresolved(stubbed, ex, eval);
        } else if (current instanceof Variable) {
            var variable = (Variable) current;
            return stringifyVariable(variable);
        } else if (current instanceof Delay) {
            return stringifyDelay((Delay) current, ex, eval);
        } else if (current instanceof Constant) {
            return current;
        } else if (current instanceof Duplicate) {
            return stringifyUnresolved(((Duplicate) current).getOnDuplicate(), ex, eval);
        }
        throw new UnresolvedResultException("bad stringify", current);
    }

    private Result stringifyDelay(Delay delay, EvalException ex, Eval eval) {
        var instructionHandles = delay.getFirstInstructions();
        var constantPoolGen = eval.getConstantPoolGen();
        var component = eval.getComponent();
        var method = eval.getMethod();
        return collapse(instructionHandles.stream().map(instructionHandle -> {
            return stringifyDelay(delay, ex, instructionHandle, eval, constantPoolGen, method);
        }).collect(toList()), eval);
    }

    private Result stringifyDelay(Delay delay, EvalException ex, InstructionHandle instructionHandle, Eval eval,
                                  ConstantPoolGen constantPoolGen, Method method) {
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof INVOKEDYNAMIC) {
            var invokedynamic = (INVOKEDYNAMIC) instruction;
            var bootstrapMethods = eval.getBootstrapMethods();
            var bootstrapMethodAndArguments = getBootstrapMethodHandlerAndArguments(
                    (INVOKEDYNAMIC) instruction, bootstrapMethods, constantPoolGen);
            var bootstrapMethodInfo = bootstrapMethodAndArguments.getBootstrapMethodInfo();

            var className = bootstrapMethodInfo.getClassName();
            var methodName = bootstrapMethodInfo.getMethodName();
            var stringConcatenation = "java.lang.invoke.StringConcatFactory".equals(className) && "makeConcatWithConstants".equals(methodName);
            var argumentClasses = toClasses(invokedynamic.getArgumentTypes(constantPoolGen));
            if (stringConcatenation) {
                var invokeResultType = invokedynamic.getType(constantPoolGen);
                var result = callInvokeDynamic((DelayInvoke) delay, argumentClasses, eval, true, this,
                        (parameters, lastInstruction) -> {
                            var handler = bootstrapMethodAndArguments.getHandler();
                            var methodArguments = bootstrapMethodAndArguments.getBootstrapMethodArguments();
                            var methodType = (MethodType) methodArguments.get(2);
                            methodType = MethodType.methodType(methodType.returnType(), toStringTypes(methodType.parameterList()));
                            methodArguments.set(2, methodType);
                            var callSite = getCallSite(handler, methodArguments);
                            return callBootstrapMethod(getValues(parameters), instructionHandle, lastInstruction,
                                    invokeResultType, eval, parameters, callSite);
                        });
                return result;
            } else {
                var result = callInvokeDynamic((DelayInvoke) delay, argumentClasses, eval, true,
                        this, (parameters, parent) -> {
                            var values = getParameterValues(parameters, this, eval);
                            var string = Stream.of(values).map(String::valueOf).reduce(String::concat).orElse("");
                            var lastInstruction = delay.getLastInstruction();
                            return invoked(string, getType(string), lastInstruction, lastInstruction, this, eval, parameters);
                        });
                return result;
            }
        } else {
            if (instruction instanceof INVOKEINTERFACE || instruction instanceof INVOKEVIRTUAL) {
                var invokeInstruction = (InvokeInstruction) instruction;
                var methodName = invokeInstruction.getMethodName(constantPoolGen);
                var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
                var argumentClasses = toClasses(argumentTypes);

                var objectClass = toClass(invokeInstruction.getClassName(constantPoolGen));

                return callInvokeVirtual((DelayInvoke) delay, argumentClasses, eval, false,
                        this, (parameters, lastInstruction) -> {
                            var object = parameters.get(0);
                            var args = parameters.subList(1, parameters.size());
                            return stringifyInvokeResult(delay, objectClass, methodName, object, args, eval);
                        });
            } else if (instruction instanceof INVOKESTATIC) {
                var invokeInstruction = (InvokeInstruction) instruction;
                var methodName = invokeInstruction.getMethodName(constantPoolGen);
                var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
                var argumentClasses = toClasses(argumentTypes);

                var objectClass = toClass(invokeInstruction.getClassName(constantPoolGen));

                try {
                    return callInvokeStatic((DelayInvoke) delay, argumentClasses, eval, false,
                            this, (parameters, lastInstruction) -> {
                                try {
                                    return stringifyInvokeResult(delay, objectClass, methodName, null, parameters, eval);
                                } catch (EvalException e) {
                                    throw e;
                                }
                            });
                } catch (EvalException e) {
                    throw e;
                }
            } else if (instruction instanceof INVOKESPECIAL) {
                var invokeInstruction = (InvokeInstruction) instruction;
                var methodName = invokeInstruction.getMethodName(constantPoolGen);
                var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
                var argumentClasses = toClasses(argumentTypes);

                var objectClass = toClass(invokeInstruction.getClassName(constantPoolGen));
                return callInvokeSpecial((DelayInvoke) delay, argumentClasses, eval, false,
                        this, (parameters, lastInstruction) -> {
                            if ("<init>".equals(methodName)) {
                                return stringifyInvokeNew(delay, objectClass, parameters, eval);
                            } else {
                                var object = parameters.get(0);
                                var args = parameters.subList(1, parameters.size());
                                return stringifyInvokeResult(delay, objectClass, methodName, object, args, eval);
                            }
                        });
            } else {
                if (instruction instanceof ArithmeticInstruction) {
                    var first = resolve(eval.evalPrev(instructionHandle), ex, eval);
                    var second = instruction.consumeStack(constantPoolGen) == 2
                            ? resolve(eval.evalPrev(first), ex, eval) : null;

                    List<String> arithmeticString;
                    try {
                        arithmeticString = stringifyArithmetic((ArithmeticInstruction) instruction, first, second, eval);
                    } catch (NotInvokedException ee) {
                        throw ee;
                    }
                    var lastInstructions = second != null ? second.getLastInstructions() : first.getLastInstructions();
                    var values = arithmeticString.stream()
                            .map(v -> constant(v, List.of(instructionHandle), lastInstructions, eval, this, asList(first, second)))
                            .collect(toList());
                    return collapse(values, delay.getEval());
                } else if (instruction instanceof ArrayInstruction) {
                    var element = eval.evalPrev(instructionHandle);
                    var index = eval.evalPrev(element);
                    var array = eval.evalPrev(index);
                    var result = stringifyValue(array, eval);
                    var lastInstruction = array.getLastInstructions();
                    return constant(result, lastInstruction, lastInstruction, eval, this, asList(delay, element, index, array));
                } else if (instruction instanceof LoadInstruction) {
                    var aload = (LoadInstruction) instruction;
                    var aloadIndex = aload.getIndex();
                    var localVariables = getLocalVariables(method, aloadIndex);
                    var localVariable = findLocalVariable(method, localVariables, instructionHandle);

                    var name = localVariable != null ? localVariable.getName() : null;
                    if (THIS.equals(name)) {
                        var value = eval.getObject();
                        return constant(value, Result.getInstructions(instructionHandle), Result.getInstructions(instructionHandle), eval, this, List.of(delay));
                    }

                    var storeResults = eval.getStoreInstructionResults(instructionHandle, aloadIndex);

                    var strings = storeResults.stream().flatMap(storeResult -> stringifyValue(storeResult, eval).stream()
                                    .map(String::valueOf)
                                    .map(s -> constant(s, Result.getInstructions(instructionHandle), Result.getInstructions(instructionHandle), eval, this, asList(delay, storeResult))))
                            .collect(toList());

                    return strings.size() == 1 ? strings.get(0) : multiple(strings, eval);
                }
            }
        }
        throw new UnresolvedResultException("bad stringify delay", delay);
    }

    private List<Class<?>> toStringTypes(List<Class<?>> methodTypes) {
        return methodTypes.stream().map(t -> String.class).collect(toList());
    }

    private Constant stringifyInvokeResult(Delay delay, Class<?> objectClass, String methodName,
                                           ParameterValue object, List<ParameterValue> resolvedArguments, Eval eval) {
        var argValues = getArgValues(resolvedArguments, eval);
        var objectValue = object != null ? getParameterValue(object, this, eval) : null;
        final String stringValue;
        if (objectValue instanceof String)
            stringValue = (String) objectValue;
        else if (objectValue != null) {
            stringValue = String.valueOf(objectValue);
        } else {
            stringValue = null;
        }
        var string = stringifyMethodCall(objectClass, methodName, stringValue, argValues);
        var lastInstruction = delay.getLastInstruction();
        var parameterValues = concatCallParameters(object, resolvedArguments);
        return invoked(string, getType(string), lastInstruction, lastInstruction, this, eval, parameterValues);
    }

    private Constant stringifyInvokeNew(Delay delay, Class<?> objectClass, List<ParameterValue> resolvedArguments, Eval eval) {
        var argValues = getArgValues(resolvedArguments, eval);
        var string = stringifyNewCall(objectClass, argValues);
        var lastInstruction = delay.getLastInstruction();
        return invoked(string, getType(string), lastInstruction, lastInstruction, this, eval, resolvedArguments);
    }

    private Object[] getArgValues(List<ParameterValue> resolvedArguments, Eval eval) {
        var notStringVariables = resolvedArguments.stream().filter(arg -> {
                    var variable = arg.getParameter() instanceof Variable;
                    var string = arg.getValue() instanceof String;
                    return variable && !string;
                })
                .collect(toList());
        var args = !notStringVariables.isEmpty() ? notStringVariables : resolvedArguments;
        return getParameterValues(args, this, eval);
    }

    private Object[] getParameterValues(List<ParameterValue> parameterValues, Resolver resolver, Eval eval) {
        return parameterValues.stream().map(parameterValue -> getParameterValue(parameterValue, resolver, eval))
                .toArray(Object[]::new);
    }

    private Object getParameterValue(ParameterValue pv, Resolver resolver, Eval eval) {
        var exception = pv.getException();
        var parameter = pv.getParameter();
        if (exception instanceof NotInvokedException) {
            throw exception;
        } else if (exception != null) {
            var resolved = resolver.resolve(parameter, exception, eval);
            return resolved.getValue();
        }
        var value = pv.getValue();
        if (value == null) {
            return null;
        }
        var valueClass = value.getClass();
        var packageName = valueClass.getPackageName();
        if (valueClass.isPrimitive() || packageName.startsWith("java")) {
            return value;
        }

        if (level != full) {
            return null;
        }

        var instructionHandle = parameter.getFirstInstruction();
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof LoadInstruction) {
            var loadInst = (LoadInstruction) instruction;
            var localVariable = getLocalVariable(parameter.getMethod(), loadInst.getIndex(), instructionHandle);
            var name = localVariable != null ? localVariable.getName() : null;
            if (!(name == null || THIS.equals(name))) {
                return name;
            }
        }
        var simpleName = value.getClass().getSimpleName();
        int length = simpleName.length();
        var classAsVar = length > 2 ? simpleName.substring(0, 3).toLowerCase() + (length > 3
                ? simpleName.substring(3) : "") : simpleName;
        return classAsVar;
    }

    private Constant stringifyVariable(Variable variable) {
        var methodName = variable.getMethod().getName();
        var variableComponentType = variable.getComponentType();
        var componentType = variableComponentType != null ? variableComponentType.getSimpleName() : null;
        var variableName = "{" + variable.getName() + "}";
        var value = level == full ? "{" + componentType + "." + methodName + "(" + variableName + ")" + "}"
                : variableName;
        var lastInstruction = variable.getLastInstructions();
        var eval = variable.getEval();
        return constant(value, lastInstruction, lastInstruction, eval, this, List.of(variable));
    }

    private String stringifyMethodCall(Class<?> objectClass, String methodName, String objectValue, Object[] argValues) {
        var arguments = stringifyArguments(argValues);
        if (level == full) {
            return "{" + (objectValue != null ? objectValue : objectClass.getSimpleName()) + "." + methodName + "(" + arguments + ")" + "}";
        } else {
            var objStr = objectValue != null ? objectValue : "";
            var srgStr = argValues.length > 1 || (objStr.length() > 1 && argValues.length > 0) ? "(" + arguments + ")" : arguments;
            return objStr + srgStr;
        }
    }

    private String stringifyNewCall(Class<?> objectClass, Object[] argValues) {
        var arguments = stringifyArguments(argValues);
        if (level == full) {
            return "{" + "new " + objectClass.getSimpleName() + "(" + arguments + ")" + "}";
        } else {
            return argValues.length > 1 ? "(" + arguments + ")" : arguments;
        }
    }

    private String stringifyArguments(Object... arguments) {
        return Stream.of(arguments).map(a -> {
            if (a instanceof CharSequence) {
                return a.toString();
            } else {
                if (a != null) {
                    log.warn("unexpected non string value of type {}, value '{}'", a.getClass().getName(), a);
                }
                return a + "";
            }
        }).reduce("", (l, r) -> (l.isEmpty() ? "" : l + ", ") + r);
    }

    private List<String> stringifyArithmetic(ArithmeticInstruction instruction, Result first, Result second, Eval eval) {
        var opcode = instruction.getOpcode();
        switch (opcode) {
            case DADD:
            case FADD:
            case IADD:
            case LADD:
                return invoke(first, second, (a, b) -> a + "+" + b, eval);
            case DDIV:
            case FDIV:
            case IDIV:
            case LDIV:
                return invoke(first, second, (a, b) -> a + "/" + b, eval);
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
                return invoke(first, second, (a, b) -> a + "%" + b, eval);
            case DSUB:
            case FSUB:
            case ISUB:
            case LSUB:
                return invoke(first, second, (a, b) -> a + "-" + b, eval);
            case IAND:
            case LAND:
                return invoke(first, second, (a, b) -> a + "&" + b, eval);
            case IOR:
            case LOR:
                return invoke(first, second, (a, b) -> a + "|" + b, eval);
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
                return invoke(first, second, (a, b) -> a + "^" + b, eval);
            default:
                throw new IllegalStateException("unsupported arithmetic op " + opcode);
        }
    }

    private List<String> neg(Result result, Eval eval) {
        return expand(result).stream().flatMap(f -> stringifyValue(f, eval).stream())
                .map(v -> (level == full ? "-" : "") + v).collect(toList());
    }

    private List<String> unsignedShiftRight(Result first, Result second, Eval eval) {
        return invokeVariants(s(first, eval), expand(second), (a, b) -> a + ">>>" + b, eval);
    }

    private List<String> shiftRight(Result first, Result second, Eval eval) {
        return invokeVariants(s(first, eval), expand(second), (a, b) -> a + ">>" + b, eval);
    }

    private List<String> shiftLeft(Result first, Result second, Eval eval) {
        return invokeVariants(s(first, eval), expand(second), (a, b) -> a + "<<" + b, eval);
    }

    private List<String> invoke(Result first, Result second, BiFunction<String, String, String> op, Eval eval) {
        if (level == full) {
            return invokeVariants(expand(first), expand(second), op, eval);
        } else if (resolvedBy(first, this, loopControl())) {
            return stringifyValue(first, eval).stream().map(o -> o + "").collect(toList());
        } else if (resolvedBy(second, this, loopControl())) {
            return stringifyValue(second, eval).stream().map(o -> o + "").collect(toList());
        } else {
            return invokeVariants(expand(first), expand(second), op, eval);
        }
    }

    private Predicate<Object> loopControl() {
        var touched = new IdentityHashMap<Object, Object>();
        return o -> touched.put(o, o) == null;
    }

    private boolean resolvedBy(Result result, Object resolver, Predicate<Object> touched) {
        if (!touched.test(result)) {
            //log
            return false;
        }
        if (result instanceof Constant) {
            var constant = (Constant) result;
            var resolvedBy = constant.getResolvedBy();
            if (resolvedBy != null) {
                return resolvedBy == resolver;
            } else {
                return constant.getRelations().stream().anyMatch(r -> resolvedBy(r, resolver, touched));
            }
        } else if (result instanceof Multiple) {
            var multiple = (Multiple) result;
            return multiple.getResults().stream().anyMatch(r -> resolvedBy(r, resolver, touched));
        }
        return false;
    }

    private List<String> invokeVariants(Collection<Result> firstVariants, Collection<Result> secondVariants,
                                        BiFunction<String, String, String> op, Eval eval) {
        return secondVariants.stream().flatMap(s -> firstVariants.stream().flatMap(f -> {
            var values1 = stringifyValue(s, eval);
            var values2 = stringifyValue(f, eval);
            return values1.stream().flatMap(value1 -> values2.stream().map(value2 -> op.apply(value1 + "", value2 + "")));
        })).collect(toList());
    }

    private List<Object> stringifyValue(Result result, Eval eval) {
        if (result instanceof Multiple) {
            var multiple = (Multiple) result;
            return multiple.getResults().stream().map(result1 -> stringifyValue(result1, eval)).filter(Objects::nonNull)
                    .flatMap(Collection::stream).collect(toList());
        }
        try {
            return result.getValue(this, eval);
        } catch (NotInvokedException e) {
            //log
            throw e;
        } catch (EvalException e) {
            if (failFast) {
                throw new IllegalStateException("unexpected eval bytecode error", e);
            } else {
                throw e;
            }
        }
    }

    private List<Result> s(Result result, Eval eval) {
        var value2Variants = expand(result);
        return value2Variants.stream()
                .flatMap(resultVariant -> stringifyValue(resultVariant, eval).stream()
                        .map(value -> value instanceof Number
                                ? (((Number) value).longValue() & 0X1f) + ""
                                : value + "")
                        .map(v -> {
                            var firstInstruction = result.getFirstInstructions();
                            var lastInstruction = result.getLastInstructions();
                            return constant(v, firstInstruction, lastInstruction, eval, this, asList(result, resultVariant));
                        }))
                .collect(toList());
    }

    @Override
    public Result resolve(Result unresolved, EvalException cause, Eval eval) {
        return stringifyUnresolved(unresolved, cause, eval);
    }

    public enum Level {
        full,
        varOnly
    }
}
