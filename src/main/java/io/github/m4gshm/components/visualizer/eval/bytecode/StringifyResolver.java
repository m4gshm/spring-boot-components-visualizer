package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.result.*;
import io.github.m4gshm.components.visualizer.model.Component;
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
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeDynamicUtils.*;
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
    Map<CallCacheKey, Result> callCache;
    boolean failFast;

    public static StringifyResolver newStringify(Level level, boolean failFast, Map<CallCacheKey, Result> callCache) {
        return new StringifyResolver(level, callCache, failFast);
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

    public Result stringifyUnresolved(Result current, EvalException ex) {
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
        } else if (current instanceof Constant) {
            return current;
        } else if (current instanceof Duplicate) {
            return stringifyUnresolved(((Duplicate) current).getOnDuplicate(), ex);
        }
        throw new UnresolvedResultException("bad stringify", current);
    }

    private Result stringifyDelay(Delay delay, EvalException ex) {
        var instructionHandle = delay.getFirstInstruction();
        var instruction = instructionHandle.getInstruction();
        var eval = delay.getEval();
        var constantPoolGen = eval.getConstantPoolGen();
        var component = eval.getComponent();
        var method = eval.getMethod();
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
                var result = callInvokeDynamic((DelayInvoke) delay, argumentClasses, eval, true, this::stringifyUnresolved, callCache,
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
                        this::stringifyUnresolved, callCache, (parameters, parent) -> {
                            var values = getParameterValues(parameters, this::stringifyUnresolved);
                            var string = Stream.of(values).map(String::valueOf).reduce(String::concat).orElse("");
                            var lastInstruction = delay.getLastInstruction();
                            return invoked(string, getType(string), lastInstruction, lastInstruction, component, method, parameters);
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
                        this::stringifyUnresolved, callCache, (parameters, lastInstruction) -> {
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
                            this::stringifyUnresolved, callCache, (parameters, lastInstruction) -> {
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
                        this::stringifyUnresolved, callCache, (parameters, lastInstruction) -> {
                            if ("<init>".equals(methodName)) {
                                return stringifyInvokeNew(delay, objectClass, parameters, component, method);
                            } else {
                                var object = parameters.get(0);
                                var args = parameters.subList(1, parameters.size());
                                return stringifyInvokeResult(delay, objectClass, methodName, object, args, eval);
                            }
                        });
            } else {
                if (instruction instanceof ArithmeticInstruction) {
                    var first = resolve(eval.evalPrev(instructionHandle, delay, callCache), ex);
                    var second = instruction.consumeStack(constantPoolGen) == 2
                            ? resolve(eval.evalPrev(first, callCache), ex) : null;

                    List<String> arithmeticString;
                    try {
                        arithmeticString = stringifyArithmetic((ArithmeticInstruction) instruction, first, second,
                                component, method);
                    } catch (NotInvokedException ee) {
                        throw ee;
                    }
                    var lastInstruction = second != null ? second.getLastInstruction() : first.getLastInstruction();
                    var values = arithmeticString.stream()
                            .map(v -> constant(v, instructionHandle, lastInstruction, component, method, this, asList(first, second)))
                            .collect(toList());
                    return collapse(values, instructionHandle, lastInstruction, delay.getMethod().getConstantPool(), component, method);
                } else if (instruction instanceof ArrayInstruction) {
                    var element = eval.evalPrev(instructionHandle, delay, callCache);
                    var index = eval.evalPrev(element, callCache);
                    var array = eval.evalPrev(index, callCache);
                    var result = stringifyValue(array);
                    var lastInstruction = array.getLastInstruction();
                    return constant(result, lastInstruction, lastInstruction, component, method, this, asList(delay, element, index, array));
                } else if (instruction instanceof LoadInstruction) {
                    var aload = (LoadInstruction) instruction;
                    var aloadIndex = aload.getIndex();
                    var localVariables = getLocalVariables(method, aloadIndex);
                    var localVariable = findLocalVariable(method, localVariables, instructionHandle);

                    var name = localVariable != null ? localVariable.getName() : null;
                    if (THIS.equals(name)) {
                        var value = eval.getObject();
                        return constant(value, instructionHandle, instructionHandle, component, method, this, asList(delay));
                    }

                    var storeResults = eval.findStoreInstructionResults(instructionHandle, localVariables, aloadIndex, delay, callCache);

                    var strings = storeResults.stream().flatMap(storeResult -> stringifyValue(storeResult).stream()
                                    .map(String::valueOf)
                                    .map(s -> constant(s, instructionHandle, instructionHandle, component, method, this, asList(delay, storeResult))))
                            .collect(toList());

                    return strings.size() == 1 ? strings.get(0)
                            : multiple(strings, instructionHandle, instructionHandle, component, method);
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
        var argValues = getArgValues(resolvedArguments);
        var objectValue = object != null ? getParameterValue(object, this::stringifyUnresolved) : null;
        var string = stringifyMethodCall(objectClass, methodName, (String) objectValue, argValues);
        var lastInstruction = delay.getLastInstruction();
        var parameterValues = concatCallParameters(object, resolvedArguments);
        return invoked(string, getType(string), lastInstruction, lastInstruction, eval.getComponent(), eval.getMethod(), parameterValues);
    }

    private Constant stringifyInvokeNew(Delay delay, Class<?> objectClass, List<ParameterValue> resolvedArguments,
                                        Component component, Method method) {
        var argValues = getArgValues(resolvedArguments);
        var string = stringifyNewCall(objectClass, argValues);
        var lastInstruction = delay.getLastInstruction();
        return invoked(string, getType(string), lastInstruction, lastInstruction, component, method, resolvedArguments);
    }

    private Object[] getArgValues(List<ParameterValue> resolvedArguments) {
        var notStringVariables = resolvedArguments.stream().filter(arg -> {
                    var variable = arg.getParameter() instanceof Variable;
                    var string = arg.getValue() instanceof String;
                    return variable && !string;
                })
                .collect(toList());
        var args = !notStringVariables.isEmpty() ? notStringVariables : resolvedArguments;
        return getParameterValues(args, this::stringifyUnresolved);
    }

    private Object[] getParameterValues(List<ParameterValue> parameterValues, Resolver resolver) {
        return parameterValues.stream().map(parameterValue -> getParameterValue(parameterValue, resolver)).toArray(Object[]::new);
    }

    private Object getParameterValue(ParameterValue pv, Resolver resolver) {
        var exception = pv.getException();
        var parameter = pv.getParameter();
        if (exception instanceof NotInvokedException) {
            throw exception;
        } else if (exception != null) {
            var resolved = resolver.resolve(parameter, exception);
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
        var lastInstruction = variable.getLastInstruction();
        var eval = variable.getEvalContext();
        return constant(value, lastInstruction, lastInstruction, eval.getComponent(), eval.getMethod(), this, asList(variable));
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

    private List<String> stringifyArithmetic(ArithmeticInstruction instruction,
                                             Result first, Result second, Component component, Method method) {
        var opcode = instruction.getOpcode();
        switch (opcode) {
            case DADD:
            case FADD:
            case IADD:
            case LADD:
                return invoke(first, second, (a, b) -> a + "+" + b);
            case DDIV:
            case FDIV:
            case IDIV:
            case LDIV:
                return invoke(first, second, (a, b) -> a + "/" + b);
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
                return invoke(first, second, (a, b) -> a + "%" + b);
            case DSUB:
            case FSUB:
            case ISUB:
            case LSUB:
                return invoke(first, second, (a, b) -> a + "-" + b);
            case IAND:
            case LAND:
                return invoke(first, second, (a, b) -> a + "&" + b);
            case IOR:
            case LOR:
                return invoke(first, second, (a, b) -> a + "|" + b);
            case ISHL:
            case LSHL:
                return shiftLeft(first, second, component, method);
            case ISHR:
            case LSHR:
                return shiftRight(first, second, component, method);
            case IUSHR:
            case LUSHR:
                return unsignedShiftRight(first, second, component, method);
            case IXOR:
            case LXOR:
                return invoke(first, second, (a, b) -> a + "^" + b);
            default:
                throw new IllegalStateException("unsupported arithmetic op " + opcode);
        }
    }

    private List<String> neg(Result result) {
        return expand(result).stream().flatMap(f -> stringifyValue(f).stream())
                .map(v -> (level == full ? "-" : "") + v).collect(toList());
    }

    private List<String> unsignedShiftRight(Result first, Result second, Component component, Method method) {
        return invokeVariants(s(first, component, method), expand(second), (a, b) -> a + ">>>" + b);
    }

    private List<String> shiftRight(Result first, Result second, Component component, Method method) {
        return invokeVariants(s(first, component, method), expand(second), (a, b) -> a + ">>" + b);
    }

    private List<String> shiftLeft(Result first, Result second, Component component, Method method) {
        return invokeVariants(s(first, component, method), expand(second), (a, b) -> a + "<<" + b);
    }

    private List<String> invoke(Result first, Result second, BiFunction<String, String, String> op) {
        if (level == full) {
            return invokeVariants(expand(first), expand(second), op);
        } else if (resolvedBy(first, this, loopControl())) {
            return stringifyValue(first).stream().map(o -> o + "").collect(toList());
        } else if (resolvedBy(second, this, loopControl())) {
            return stringifyValue(second).stream().map(o -> o + "").collect(toList());
        } else {
            return invokeVariants(expand(first), expand(second), op);
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

    private List<String> invokeVariants(List<Result> firstVariants, List<Result> secondVariants,
                                        BiFunction<String, String, String> op) {
        return secondVariants.stream().flatMap(s -> firstVariants.stream().flatMap(f -> {
            var values1 = stringifyValue(s);
            var values2 = stringifyValue(f);
            return values1.stream().flatMap(value1 -> values2.stream().map(value2 -> op.apply(value1 + "", value2 + "")));
        })).collect(toList());
    }

    private List<Object> stringifyValue(Result result) {
        if (result instanceof Multiple) {
            var multiple = (Multiple) result;
            return multiple.getResults().stream().map(this::stringifyValue).filter(Objects::nonNull)
                    .flatMap(Collection::stream).collect(toList());
        }
        try {
            return result.getValue(this::stringifyUnresolved);
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

    private List<Result> s(Result result, Component component, Method method) {
        var value2Variants = expand(result);
        return value2Variants.stream()
                .flatMap(resultVariant -> stringifyValue(resultVariant).stream()
                        .map(value -> value instanceof Number
                                ? (((Number) value).longValue() & 0X1f) + ""
                                : value + "")
                        .map(v -> {
                            var firstInstruction = result.getFirstInstruction();
                            var lastInstruction = result.getLastInstruction();
                            return constant(v, firstInstruction, lastInstruction, component, method, this, asList(result, resultVariant));
                        }))
                .collect(toList());
    }

    @Override
    public Result resolve(Result unresolved, EvalException cause) {
        return stringifyUnresolved(unresolved, cause);
    }

    public enum Level {
        full,
        varOnly
    }
}
