package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.*;
import io.github.m4gshm.connections.model.Component;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.eval.bytecode.Eval.*;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.toClass;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.toClasses;
import static io.github.m4gshm.connections.eval.bytecode.InvokeDynamicUtils.getBootstrapMethod;
import static io.github.m4gshm.connections.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodInfo;
import static io.github.m4gshm.connections.eval.bytecode.LocalVariableUtils.*;
import static io.github.m4gshm.connections.eval.bytecode.StringifyResolver.Level.full;
import static io.github.m4gshm.connections.eval.result.Result.*;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.*;

@Slf4j
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PROTECTED)
public class StringifyResolver implements Resolver {

    Level level;
    Map<CallCacheKey, Result> callCache;
    boolean failFast;

    public static StringifyResolver newStringify(Map<CallCacheKey, Result> callCache) {
        return new StringifyResolver(full, callCache, true);
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

    public Result stringifyUnresolved(Result current, EvalBytecodeException ex) {
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
        }
        throw new UnresolvedResultException("bad stringify", current);
    }

    private Result stringifyDelay(Delay delay, EvalBytecodeException ex) {
        var instructionHandle = delay.getFirstInstruction();
        var instruction = instructionHandle.getInstruction();
        var eval = delay.getEval();
        var constantPoolGen = eval.getConstantPoolGen();
        var constantPool = constantPoolGen.getConstantPool();
        var component = eval.getComponent();
        var method = eval.getMethod();
        if (instruction instanceof INVOKEDYNAMIC) {
            var invokedynamic = (INVOKEDYNAMIC) instruction;
            var bootstrapMethods = eval.getBootstrapMethods();

            var bootstrapMethod = getBootstrapMethod(invokedynamic, bootstrapMethods, constantPool);
            var bootstrapMethodInfo = getBootstrapMethodInfo(bootstrapMethod, constantPool);

            var className = bootstrapMethodInfo.getClassName();
            var methodName = bootstrapMethodInfo.getMethodName();
            var stringConcatenation = "java.lang.invoke.StringConcatFactory".equals(className) && "makeConcatWithConstants".equals(methodName);
            var argumentClasses = toClasses(invokedynamic.getArgumentTypes(constantPoolGen));
            if (stringConcatenation) {
                //arg1+arg2
                return callInvokeDynamic((DelayInvoke) delay, argumentClasses, eval, false,
                        this::stringifyUnresolved, callCache, (parameters, parent) -> {
                            var values = getParameterValues(parameters, this::stringifyUnresolved);
                            var string = Stream.of(values).map(String::valueOf).reduce(String::concat).orElse("");
                            var lastInstruction = delay.getLastInstruction();
                            return invoked(string, lastInstruction, lastInstruction, component, method, parameters);
                        });
            } else {
                var result = callInvokeDynamic((DelayInvoke) delay, argumentClasses, eval, true,
                        this::stringifyUnresolved, callCache, (parameters, parent) -> {
                            var values = getParameterValues(parameters, this::stringifyUnresolved);
                            var string = Stream.of(values).map(String::valueOf).reduce(String::concat).orElse("");
                            var lastInstruction = delay.getLastInstruction();
                            return invoked(string, lastInstruction, lastInstruction, component, method, parameters);
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
                                } catch (EvalBytecodeException e) {
                                    throw e;
                                }
                            });
                } catch (EvalBytecodeException e) {
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
                    var first = resolve(eval.eval(eval.getPrev(instructionHandle), delay, callCache), ex);
                    var second = instruction.consumeStack(constantPoolGen) == 2
                            ? resolve(eval.eval(eval.getPrev(first.getLastInstruction()), callCache), ex): null;

                    List<String> arithmeticString;
                    try {
                        arithmeticString = stringifyArithmetic((ArithmeticInstruction) instruction, first, second,
                                component, method, callCache);
                    } catch (NotInvokedException ee) {
                        throw ee;
                    }
                    var lastInstruction = second != null ? second.getLastInstruction() : first.getLastInstruction();
                    var values = arithmeticString.stream()
                            .map(v -> constant(v, instructionHandle, lastInstruction, component, method, first, second))
                            .collect(toList());
                    return collapse(values, instructionHandle, lastInstruction, delay.getMethod().getConstantPool(), component, method);
                } else if (instruction instanceof ArrayInstruction) {
                    var element = eval.eval(eval.getPrev(instructionHandle), delay, callCache);
                    var index = eval.eval(eval.getPrev(element.getLastInstruction()), delay, callCache);
                    var array = eval.eval(eval.getPrev(index.getLastInstruction()), delay, callCache);
                    var result = stringifyValue(array);
                    var lastInstruction = array.getLastInstruction();
                    return constant(result, lastInstruction, lastInstruction, component, method, delay, element, index, array);
                } else if (instruction instanceof LoadInstruction) {
                    var aload = (LoadInstruction) instruction;
                    var aloadIndex = aload.getIndex();
                    var localVariables = getLocalVariables(method, aloadIndex);
                    var localVariable = findLocalVariable(method, localVariables, instructionHandle);

                    var name = localVariable != null ? localVariable.getName() : null;
                    if ("this".equals(name)) {
                        var value = eval.getObject();
                        return constant(value, instructionHandle, instructionHandle, component, method, delay);
                    }

                    var storeResults = eval.findStoreInstructionResults(instructionHandle, localVariables, aloadIndex, delay, callCache);

                    var strings = storeResults.stream().flatMap(storeResult -> stringifyValue(storeResult).stream()
                                    .map(String::valueOf)
                                    .map(s -> constant(s, instructionHandle, instructionHandle, component, method, delay, storeResult)))
                            .collect(toList());

                    return strings.size() == 1 ? strings.get(0)
                            : multiple(strings, instructionHandle, instructionHandle, component, method);
                }
            }
        }
        throw new UnresolvedResultException("bad stringify delay", delay);
    }

    private Constant stringifyInvokeResult(Delay delay, Class<?> objectClass, String methodName,
                                           ParameterValue object, List<ParameterValue> resolvedArguments, Eval eval) {
        var argValues = getArgValues(resolvedArguments);
        var objectValue = object != null ? getParameterValue(object, this::stringifyUnresolved) : null;
        var string = stringifyMethodCall(objectClass, (String) objectValue, methodName, stringifyArguments(argValues));
        var lastInstruction = delay.getLastInstruction();
        var parameterValues = concatCallParameters(object, resolvedArguments);
        return invoked(string, lastInstruction, lastInstruction, eval.getComponent(), eval.getMethod(), parameterValues);
    }

    private Constant stringifyInvokeNew(Delay delay, Class<?> objectClass, List<ParameterValue> resolvedArguments,
                                        Component component, Method method) {
        var argValues = getArgValues(resolvedArguments);
        var string = stringifyNewCall(objectClass, stringifyArguments(argValues));
        var lastInstruction = delay.getLastInstruction();
        return invoked(string, lastInstruction, lastInstruction, component, method, resolvedArguments);
    }

    private Object[] getArgValues(List<ParameterValue> resolvedArguments) {
        var notStringVariables = resolvedArguments.stream().filter(a -> {
                    var variable = a.getParameter() instanceof Variable;
                    var string = a.getValue() instanceof String;
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

    private Constant stringifyVariable(Variable variable) {
        var methodName = variable.getMethod().getName();
        var componentType = variable.getComponentType().getSimpleName();
        var value = "{" + componentType + "." + methodName + "(" + "{" + variable.getName() + "}" + ")" + "}";
        var lastInstruction = variable.getLastInstruction();
        var eval = variable.getEvalContext();
        return constant(value, lastInstruction, lastInstruction, eval.getComponent(), eval.getMethod(), variable);
    }

    private String stringifyMethodCall(Class<?> objectClass, String object, String methodName, String arguments) {
        return "{" + (object != null ? object : objectClass.getSimpleName()) + "." + methodName + "(" + arguments + ")" + "}";
    }

    private String stringifyNewCall(Class<?> objectClass, String arguments) {
        return "{" + "new " + objectClass.getSimpleName() + "(" + arguments + ")" + "}";
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
                                             Result first, Result second, Component component, Method method,
                                             Map<CallCacheKey, Result> callCache) {
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
                return shiftLeft(first, second, component, method, callCache);
            case ISHR:
            case LSHR:
                return shiftRight(first, second, component, method, callCache);
            case IUSHR:
            case LUSHR:
                return unsignedShiftRight(first, second, component, method, callCache);
            case IXOR:
            case LXOR:
                return xor(first, second);
            default:
                throw new IllegalStateException("unsupported arithmetic op " + opcode);
        }
    }

    private List<String> neg(Result result) {
        return expand(result).stream().flatMap(f -> stringifyValue(f).stream())
                .map(v -> "-" + v).collect(toList());
    }

    private List<String> xor(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "^" + b);
    }

    private List<String> or(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "|" + b);
    }

    private List<String> and(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "&" + b);
    }

    private List<String> add(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "+" + b);
    }

    private List<String> sub(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "-" + b);
    }

    private List<String> rem(Result first, Result second) {
        return invoke(first, second, (a, b) -> a + "%" + b);
    }

    private List<String> unsignedShiftRight(Result first, Result second, Component component, Method method, Map<CallCacheKey, Result> callCache) {
        return invokeVariants(s(first, component, method), expand(second), (a, b) -> a + ">>>" + b);
    }

    private List<String> shiftRight(Result first, Result second, Component component, Method method, Map<CallCacheKey, Result> callCache) {
        return invokeVariants(s(first, component, method), expand(second), (a, b) -> a + ">>" + b);
    }

    private List<String> shiftLeft(Result first, Result second, Component component, Method method, Map<CallCacheKey, Result> callCache) {
        return invokeVariants(s(first, component, method), expand(second), (a, b) -> a + "<<" + b);
    }

    private List<String> invoke(Result first, Result second, BiFunction<String, String, String> op) {
        return invokeVariants(expand(first), expand(second), op);
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
        try {
            return result.getValue(this::stringifyUnresolved);
        } catch (NotInvokedException e) {
            //log
            throw e;
        } catch (EvalBytecodeException e) {
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
                            return constant(v, firstInstruction, lastInstruction, component, method, result, resultVariant);
                        }))
                .collect(toList());
    }

    @Override
    public Result resolve(Result unresolved, EvalBytecodeException cause) {
        return stringifyUnresolved(unresolved, cause);
    }

    public enum Level {
        full,
        varOnly
    }
}
