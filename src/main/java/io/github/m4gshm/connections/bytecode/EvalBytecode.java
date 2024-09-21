package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Const;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.ContextAware;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Delay;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Delay.DelayFunction;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Illegal.Status;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Multiple;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.ParentAware;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable;
import io.github.m4gshm.connections.client.JmsOperationsUtils;
import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.CallPointsHelper.getCallsHierarchy;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.Utils.classByName;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Illegal.Status.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable.VarType.LocalVar;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable.VarType.MethodArg;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.constant;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.delay;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.methodArg;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.notFound;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.variable;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeException.newInvalidEvalException;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeException.newUnsupportedEvalException;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.*;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethodAndArguments;
import static io.github.m4gshm.connections.bytecode.MethodInfo.newMethodInfo;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.Map.entry;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.ofNullable;
import static lombok.AccessLevel.*;
import static org.apache.bcel.Const.CONSTANT_Class;
import static org.apache.bcel.generic.Type.getType;

@Slf4j
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class EvalBytecode {
    @EqualsAndHashCode.Include
    Component component;
    @EqualsAndHashCode.Include
    Method method;
    //for debug
    Code methodCode;
    ConstantPoolGen constantPoolGen;
    BootstrapMethods bootstrapMethods;
    Map<Integer, List<InstructionHandle>> jumpTo;
    private final Map<Component, List<Component>> dependencyToDependentMap;
    private final Map<Component, List<CallPoint>> callPointsCache;

    public EvalBytecode(@NonNull Component component,
                        @NonNull Map<Component, List<Component>> dependencyToDependentMap,
                        @NonNull ConstantPoolGen constantPoolGen,
                        BootstrapMethods bootstrapMethods,
                        @NonNull Method method, Map<Component, List<CallPoint>> callPointsCache) {
        this.component = component;
        this.constantPoolGen = constantPoolGen;
        this.bootstrapMethods = bootstrapMethods;
        this.method = method;
        this.methodCode = method.getCode();
        this.dependencyToDependentMap = dependencyToDependentMap;
        this.jumpTo = instructionHandleStream(method.getCode()).map(instructionHandle -> {
            var instruction = instructionHandle.getInstruction();
            if (instruction instanceof BranchInstruction) {
                return entry(((BranchInstruction) instruction).getTarget().getPosition(), instructionHandle);
            } else {
                return null;
            }
        }).filter(Objects::nonNull).collect(groupingBy(Entry::getKey, mapping(Entry::getValue, toList())));
        this.callPointsCache = callPointsCache;
    }

    public static ArrayList<Object> getInvokeArgs(Object object, Object[] arguments) {
        var invokeArgs = new ArrayList<>(arguments.length);
        invokeArgs.add(object);
        invokeArgs.addAll(asList(arguments));
        return invokeArgs;
    }

    private static Class<?> getCalledMethodClass(CallPoint calledMethodInsideDependent) {
        var ownerClass = calledMethodInsideDependent.getOwnerClass();
        var ownerClassName = calledMethodInsideDependent.getOwnerClassName();
        Class<?> calledMethodClass = null;
        try {
            calledMethodClass = ownerClass == null ? classByName(ownerClassName) : ownerClass;
        } catch (ClassNotFoundException e) {
            log.debug("getCalledMethodClass", e);
        }
        return calledMethodClass;
    }

    private static void extracted(Result result, Map<MethodInfo, List<Result>> perMethod, Set<Result> uniques) {
        if (!uniques.add(result)) {
            return;
        }
        if (result instanceof ParentAware) {
            var parentAware = (ParentAware<?>) result;
            var parent = parentAware.getParent();
            if (parent instanceof ContextAware) {
                var parentMethodAware = (ContextAware) parent;
                var method = parentMethodAware.getMethod();

                var type = parentMethodAware.getComponent().getType();
//                        method.getName()
                var methodInfo = newMethodInfo(type, method.getName(), method.getSignature());
                perMethod.computeIfAbsent(methodInfo, k -> new ArrayList<>()).add(result);
            } else if (parent == null && result instanceof ContextAware) {
                var contextAware = (ContextAware) result;
                var method = contextAware.getMethod();
                var type = contextAware.getComponent().getType();
                var methodInfo = newMethodInfo(type, method.getName(), method.getSignature());
                perMethod.computeIfAbsent(methodInfo, k -> new ArrayList<>()).add(result);
            } else {

                //log
            }
            if (parent != null) {
                extracted(parent, perMethod, uniques);
            }
        } else {
            //log
        }
    }

    private static Result getResult(Instruction instruction, InstructionHandle instructionHandle,
                                    ConstantPoolGen constantPoolGen,
                                    InstructionHandle lastInstruction, List<Result> results, Result parent) {
        if (results.isEmpty()) {
            throw newInvalidEvalException("empty results", instruction, constantPoolGen);
        }
        if (results.size() > 1) return Result.multiple(results, instructionHandle, lastInstruction, parent);
        return results.get(0);
    }

    private static void populateArgumentsResults(List<Result> argumentsResults,
                                                 Map<CallContext, Result[]> callContexts,
                                                 ContextAware variant, int i) {
        callContexts.computeIfAbsent(
                new CallContext(variant.getComponent(), variant.getMethod()),
                k -> new Result[argumentsResults.size()]
        )[i] = variant;
    }

    private static void populateArgumentsResults(List<Result> argumentsResults,
                                                 Map<CallContext, Result[]> callContexts,
                                                 Result variant, int i) {
        if (variant instanceof Multiple) {
            var multiple = (Multiple) variant;
            var first = multiple.getResults().get(0);
            populateArgumentsResults(argumentsResults, callContexts, first, i);
        } else {
            var contextAware = variant instanceof ContextAware;
            if (contextAware) {
                populateArgumentsResults(argumentsResults, callContexts, (ContextAware) variant, i);
            }
            if (variant instanceof ParentAware) {
                var parent = ((ParentAware<?>) variant).getParent();
                if (parent instanceof Variable) {
                    var parentMethodArg = (Variable) parent;
                    while (parentMethodArg != null) {
                        populateArgumentsResults(argumentsResults, callContexts, parentMethodArg, i);
                        var parent1 = parentMethodArg.getParent();
                        if (parent1 instanceof Variable) {
                            parentMethodArg = (Variable) parent1;
                        } else {
                            parentMethodArg = null;
                        }
                    }
                }
            }

            if (!(contextAware || variant instanceof ParentAware)) {
                //todo
                throw new UnsupportedOperationException(variant.toString() + " of arg " + argumentsResults.get(i).toString());
            }
        }
    }

    private static void log(String op, UnevaluatedVariableException e) {
        var variable = e.getVariable();
        var evalContext = variable.evalContext;
        var variableMethod = evalContext.getMethod();
        log.info("{} is aborted, cannot evaluate variable {}, in method {} {} of {}", op,
                variable.getName(), variableMethod.getName(),
                variableMethod.getSignature(), evalContext.getComponentType()
        );
    }

    private static List<LocalVariable> getLocalVariables(Method method, int index) {
        var localVariableTable = Stream.of(method.getLocalVariableTable().getLocalVariableTable())
                .collect(groupingBy(LocalVariable::getIndex));
        return localVariableTable.getOrDefault(index, List.of());
    }

    private static Object convertNumberTo(Number number, Type convertTo) {
        if (Type.INT.equals(convertTo)) {
            return number.intValue();
        } else if (Type.LONG.equals(convertTo)) {
            return number.longValue();
        } else if (Type.DOUBLE.equals(convertTo)) {
            return number.doubleValue();
        } else if (Type.FLOAT.equals(convertTo)) {
            return number.floatValue();
        } else if (Type.SHORT.equals(convertTo)) {
            return number.shortValue();
        } else if (Type.CHAR.equals(convertTo)) {
            return (char) number.shortValue();
        } else if (Type.BYTE.equals(convertTo)) {
            return (byte) number.shortValue();
        } else {
            throw new IllegalStateException("unsupported conversion type " + convertTo);
        }
    }

    public Component getComponent() {
        return component;
    }

    public String getComponentName() {
        return getComponent().getName();
    }

    public Class<?> getComponentType() {
        return getComponent().getType();
    }

    public Object getObject() {
        return this.component.getObject();
    }

    @Override
    public String toString() {
        return "Eval{" +
                "componentName='" + getComponentName() + "', " +
                "method='" + EvalBytecodeUtils.toString(method) + '\'' +
                '}';
    }

    private LocalVariable findLocalVariable(InstructionHandle instructionHandle, List<LocalVariable> localVariables) {
        if (localVariables.isEmpty()) {
            log.info("no matched local variables for instruction {}, method {}", instructionHandle,
                    EvalBytecodeUtils.toString(method));
            return null;
        }
        var position = instructionHandle.getPosition();
        return localVariables.stream().filter(variable -> {
            int startPC = variable.getStartPC();
            var endPC = startPC + variable.getLength();
            return startPC <= position && position <= endPC;
        }).findFirst().orElseGet(() -> {
            return localVariables.get(0);
        });
    }

    public Result eval(InstructionHandle instructionHandle, Function<Result, Result> unevaluatedHandler) {
        return eval(instructionHandle, unevaluatedHandler, null);
    }

    public Result eval(InstructionHandle instructionHandle, Function<Result, Result> unevaluatedHandler, Result parent) {
        var instruction = instructionHandle.getInstruction();
        var consumeStack = instruction.consumeStack(constantPoolGen);
        var instructionText = getInstructionString(instructionHandle, constantPoolGen);
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            var value = ldc.getValue(constantPoolGen);
            return constant(value, instructionHandle, this, parent);
        } else if (instruction instanceof LDC2_W) {
            var ldc = (LDC2_W) instruction;
            var value = ldc.getValue(constantPoolGen);
            return constant(value, instructionHandle, this, parent);
        } else if (instruction instanceof LoadInstruction) {
            var aload = (LoadInstruction) instruction;
            var aloadIndex = aload.getIndex();
            var localVariables = getLocalVariables(this.getMethod(), aloadIndex);
            var localVariable = findLocalVariable(instructionHandle, localVariables);

            var name = localVariable != null ? localVariable.getName() : null;
            if ("this".equals(name)) {
                return constant(getObject(), instructionHandle, this, parent);
            }

            var aStoreResults = findStoreInstructionResults(instructionHandle, localVariables, aloadIndex, unevaluatedHandler, parent);
            if (aStoreResults.size() == 1) {
                return delay(instructionText + " from stored invocation", instructionHandle, this, parent, (thisDelay, needResolve) -> {
                    var storeResult = aStoreResults.get(0);
                    if (needResolve) {
                        var value = storeResult.getValue(unevaluatedHandler);
                        return constant(value, instructionHandle, this, parent);
                    } else {
                        return thisDelay.evaluated(instructionHandle);
                    }
                });
            } else if (!aStoreResults.isEmpty()) {
                return delay(instructionText + " from stored invocations", instructionHandle, this, parent, (thisDelay, needResolve) -> {
                    return Result.multiple(aStoreResults, instructionHandle, instructionHandle, parent);
                });
            }
            if (log.isDebugEnabled()) {
                log.debug("not found store for {}", instructionText);
            }
            if (localVariable == null) {
                var argumentType = method.getArgumentTypes()[aloadIndex - 1];
                return methodArg(this, aloadIndex, null, argumentType, instructionHandle, parent);
            } else {
                return methodArg(this, localVariable, instructionHandle, parent);
            }
        } else if (instruction instanceof StoreInstruction) {
            int position = instructionHandle.getPosition();
            var codeException = Arrays.stream(method.getCode().getExceptionTable())
                    .filter(et -> et.getHandlerPC() == position)
                    .findFirst().orElse(null);
            if (codeException != null) {
                var catchType = constantPoolGen.getConstantPool().getConstantString(
                        codeException.getCatchType(), CONSTANT_Class);
                var errType = ObjectType.getInstance(catchType);
                var localVarIndex = ((StoreInstruction) instruction).getIndex();
                var localVariables = getLocalVariables(method, localVarIndex);
                var localVariable = findLocalVariable(instructionHandle, localVariables);
                return localVariable != null
                        ? variable(this, localVariable, instructionHandle, parent)
                        : variable(this, localVarIndex, null, errType, instructionHandle, parent);
            } else {
                return eval(getPrev(instructionHandle), unevaluatedHandler, parent);
            }
        } else if (instruction instanceof GETSTATIC) {
            var getStatic = (GETSTATIC) instruction;
            var fieldName = getStatic.getFieldName(constantPoolGen);
            var loadClassType = getStatic.getLoadClassType(constantPoolGen);
            var loadClass = InvokeDynamicUtils.getClassByName(loadClassType.getClassName());
            return getFieldValue(null, loadClass, fieldName, instructionHandle, instructionHandle, this, parent);
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;
            var evalFieldOwnedObject = eval(getPrev(instructionHandle), unevaluatedHandler, parent);
            var fieldName = getField.getFieldName(constantPoolGen);
            var lastInstruction = evalFieldOwnedObject.getLastInstruction();
            return getFieldValue(evalFieldOwnedObject, fieldName, instructionHandle, lastInstruction,
                    constantPoolGen, unevaluatedHandler, this, parent);
        } else if (instruction instanceof CHECKCAST) {
            return eval(getPrev(instructionHandle), unevaluatedHandler, parent);
        } else if (instruction instanceof InvokeInstruction) {
            return evalInvoke(instructionHandle, (InvokeInstruction) instruction, unevaluatedHandler, parent);
        } else if (instruction instanceof ANEWARRAY) {
            var anewarray = (ANEWARRAY) instruction;
            var loadClassType = anewarray.getLoadClassType(constantPoolGen);
            var arrayElementType = InvokeDynamicUtils.getClassByName(loadClassType.getClassName());
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var size = eval(getPrev(instructionHandle), unevaluatedHandler, parent);
                return constant(Array.newInstance(arrayElementType, (int) size.getValue(unevaluatedHandler)),
                        instructionHandle, size.getLastInstruction(), this, thisDelay);
            });
        } else if (instruction instanceof ConstantPushInstruction) {
            var cpi = (ConstantPushInstruction) instruction;
            var value = cpi.getValue();
            return constant(value, instructionHandle, this, parent);
        } else if (instruction instanceof ArrayInstruction && instruction instanceof StackConsumer) {
            //AASTORE
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var element = eval(getPrev(instructionHandle), unevaluatedHandler, thisDelay);
                var index = eval(getPrev(element.getLastInstruction()), unevaluatedHandler, thisDelay);
                var array = eval(getPrev(index.getLastInstruction()), unevaluatedHandler, thisDelay);
                var lastInstruction = array.getLastInstruction();
                if (needResolve) {
                    var result = array.getValue(unevaluatedHandler);
                    if (result instanceof Object[]) {
                        var indexValue = index.getValue(unevaluatedHandler);
                        ((Object[]) result)[(int) indexValue] = element.getValue(unevaluatedHandler);
                    } else {
                        throw newInvalidEvalException("expected array but was " + result.getClass(), instruction, constantPoolGen);
                    }
                    return constant(result, instructionHandle, lastInstruction, this, thisDelay);
                } else {
                    return thisDelay.evaluated(lastInstruction);
                }
            });
        } else if (instruction instanceof ArrayInstruction && instruction instanceof StackProducer) {
            //AALOAD
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var element = eval(getPrev(instructionHandle), unevaluatedHandler, thisDelay);
                var index = eval(getPrev(element.getLastInstruction()), unevaluatedHandler, thisDelay);
                var array = eval(getPrev(index.getLastInstruction()), unevaluatedHandler, thisDelay);
                var result = array.getValue(unevaluatedHandler);
                if (result instanceof Object[]) {
                    var a = (Object[]) result;
                    var i = (int) index.getValue(unevaluatedHandler);
                    var e = a[i];
                    return constant(e, array.getLastInstruction(), this, thisDelay);
                } else {
                    throw newInvalidEvalException("expected array but was " + result.getClass(), instruction, constantPoolGen);
                }
            });
        } else if (instruction instanceof ARRAYLENGTH) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var arrayRef = eval(getPrev(instructionHandle), unevaluatedHandler, thisDelay);
                var results = resolve(arrayRef, unevaluatedHandler);
                return getResult(instruction, instructionHandle, constantPoolGen, arrayRef.getLastInstruction(), results, thisDelay);
            });
        } else if (instruction instanceof NEW) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var newInstance = (NEW) instruction;
                var loadClassType = newInstance.getLoadClassType(constantPoolGen);
                var type = InvokeDynamicUtils.getClassByName(loadClassType.getClassName());
                var result = instantiateObject(instructionHandle, type, new Class[0], new Object[0], this, thisDelay);
                return result;
            });
        } else if (instruction instanceof DUP) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var prev = instructionHandle.getPrev();
                var duplicated = eval(prev, unevaluatedHandler, thisDelay);
                return duplicated;
            });
        } else if (instruction instanceof DUP2) {
//            return eval(getPrev(instructionHandle), unevaluatedHandler);
        } else if (instruction instanceof POP) {
            var onRemove = getPrev(instructionHandle);// eval(getPrev(instructionHandle), unevaluatedHandler);
            //log removed
//            var prev = onRemove.getLastInstruction().getPrev();
            //todo on remove must produce stack
            var onRemoveInstruction = onRemove.getInstruction();
            var stackProducer = onRemoveInstruction instanceof StackProducer;
            if (!stackProducer) {
                throw newInvalidEvalException("pop stack variable must be produced by prev instruction",
                        onRemoveInstruction, constantPoolGen);
            }
            var prev = onRemove.getPrev();
            return eval(prev, unevaluatedHandler, parent);
        } else if (instruction instanceof POP2) {
//            return eval(getPrev(instructionHandle), unevaluatedHandler);
        } else if (instruction instanceof ACONST_NULL) {
            return constant(null, instructionHandle, this, parent);
        } else if (instruction instanceof IfInstruction) {
            var args = new Result[consumeStack];
            var current = instructionHandle;
            for (var i = consumeStack - 1; i >= 0; --i) {
                current = getPrev(instructionHandle);
                args[i] = eval(current, unevaluatedHandler, parent);
            }
            var lastInstruction = args.length > 0 ? args[0].getLastInstruction() : instructionHandle;
            //now only positive scenario
            //todo need evaluate negative branch
            return eval(getPrev(lastInstruction), unevaluatedHandler, parent);
        } else if (instruction instanceof ConversionInstruction) {
            //I2L,
            var conv = (ConversionInstruction) instruction;
            var convertTo = conv.getType(constantPoolGen);
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                if (needResolve) {
                    var result = eval(getPrev(instructionHandle), unevaluatedHandler, thisDelay);
                    var value = result.getValue(unevaluatedHandler);
                    var number = (Number) value;
                    var converted = convertNumberTo(number, convertTo);
                    return constant(converted, instructionHandle, this, thisDelay);
                } else {
                    return thisDelay.evaluated(instructionHandle);
                }
            });
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    private List<Result> findStoreInstructionResults(InstructionHandle instructionHandle,
                                                     List<LocalVariable> localVariables, int index,
                                                     Function<Result, Result> unevaluatedHandler, Result parent) {
        var prev = getPrev(instructionHandle);
        var aStoreResults = new ArrayList<Result>(localVariables.size());
        var cycleCheck = new IdentityHashMap<InstructionHandle, InstructionHandle>();
        while (prev != null) {
            InstructionHandle existed = cycleCheck.put(prev, prev);
            if (existed != null) {
                var instText = getInstructionString(instructionHandle, constantPoolGen);
                throw new IllegalStateException("cycle detected, " + instText);
            }
            var instruction = prev.getInstruction();
            if (instruction instanceof StoreInstruction) {
                var store = (StoreInstruction) instruction;
                if (store.getIndex() == index) {
                    var storedInLocal = eval(prev, unevaluatedHandler, parent);
                    aStoreResults.add(storedInLocal);
                    prev = getPrev(prev);
                }
            }
            prev = getPrev(prev);
        }
        return aStoreResults;
    }

    public Result evalInvoke(InstructionHandle instructionHandle, InvokeInstruction instruction,
                             Function<Result, Result> unevaluatedHandler, Result parent) {
        var instructionText = getInstructionString(instructionHandle, constantPoolGen);
        if (log.isTraceEnabled()) {
            log.trace("eval {}", instructionText);
        }
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);

        var argumentClasses = getArgumentClasses(argumentTypes);
        if (instruction instanceof INVOKEVIRTUAL) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var evalArguments = evalArguments(instructionHandle, argumentTypes, unevaluatedHandler, thisDelay);
                var invokeObject = evalInvokeObject(instruction, evalArguments, unevaluatedHandler, thisDelay);
                var argumentsResults = evalArguments.getArguments();
                if (needResolve) {
                    var filledVariants = resolveArguments(instruction, argumentsResults, unevaluatedHandler);
                    var results = filledVariants.stream().flatMap(firstVariantResults -> {
                        var arguments = getArguments(argumentClasses, firstVariantResults, unevaluatedHandler);
                        var objectCallsResolved = resolve(invokeObject.object, unevaluatedHandler);
                        var callsResult = objectCallsResolved.stream().map(firstResolved -> {
                            var object = firstResolved.getValue(unevaluatedHandler);
                            return callMethod(object, object.getClass(), methodName, argumentClasses,
                                    arguments, instructionHandle, invokeObject.lastInstruction, constantPoolGen, thisDelay);
                        }).collect(toList());
                        return callsResult.stream();
                    }).collect(toList());
                    return getResult(instruction, instructionHandle, constantPoolGen, invokeObject.lastInstruction, results, thisDelay);
                } else {
                    return thisDelay.evaluated(invokeObject.lastInstruction);
                }
            });
        } else if (instruction instanceof INVOKEINTERFACE) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var evalArguments = evalArguments(instructionHandle, argumentTypes, unevaluatedHandler, thisDelay);
                var invokeObject = evalInvokeObject(instruction, evalArguments, unevaluatedHandler, thisDelay);
                var argumentsResults = evalArguments.getArguments();
                if (needResolve) {
                    var filledVariants = resolveArguments(instruction, argumentsResults, unevaluatedHandler);
                    var results = filledVariants.stream().flatMap(firstVariantResults -> {
                        var type = InvokeDynamicUtils.getClassByName(instruction.getClassName(constantPoolGen));
                        var arguments = getArguments(argumentClasses, firstVariantResults, unevaluatedHandler);
                        var objectCallsResolved = resolve(invokeObject.object, unevaluatedHandler);
                        var callsResult = objectCallsResolved.stream().map(firstResolved -> {
                            var object = firstResolved.getValue(unevaluatedHandler);
                            return callMethod(object, type, methodName, argumentClasses, arguments,
                                    instructionHandle, invokeObject.lastInstruction, constantPoolGen, thisDelay);
                        }).collect(toList());
                        return callsResult.stream();
                    }).collect(toList());
                    return getResult(instruction, instructionHandle, constantPoolGen, invokeObject.lastInstruction, results, thisDelay);
                } else {
                    return thisDelay.evaluated(invokeObject.lastInstruction);
                }
            });
        } else if (instruction instanceof INVOKEDYNAMIC) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var evalArguments = evalArguments(instructionHandle, argumentTypes, unevaluatedHandler, thisDelay);
                var invokeObject = evalInvokeObject(instruction, evalArguments, unevaluatedHandler, thisDelay);
                var argumentsResults = evalArguments.getArguments();
                if (needResolve) {
                    var filledVariants = resolveArguments(instruction, argumentsResults, unevaluatedHandler);
                    var results = filledVariants.stream().map(firstVariantResults -> {
                        var arguments = getArguments(argumentClasses, firstVariantResults, unevaluatedHandler);
                        return callBootstrapMethod(arguments, (INVOKEDYNAMIC) instruction, instructionHandle,
                                constantPoolGen, bootstrapMethods, invokeObject.lastInstruction, this, thisDelay);
                    }).collect(toList());
                    var result = getResult(instruction, instructionHandle, constantPoolGen,
                            invokeObject.lastInstruction, results, thisDelay);
                    return result;
                } else {
                    return thisDelay.evaluated(invokeObject.lastInstruction);
                }
            });
        } else if (instruction instanceof INVOKESTATIC) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var evalArguments = evalArguments(instructionHandle, argumentTypes, unevaluatedHandler, thisDelay);
                var invokeObject = evalInvokeObject(instruction, evalArguments, unevaluatedHandler, thisDelay);
                var argumentsResults = evalArguments.getArguments();
                if (needResolve) {
                    var filledVariants = resolveArguments(instruction, argumentsResults, unevaluatedHandler);
                    var results = filledVariants.stream().map(firstVariantResults -> {
                        var arguments = getArguments(argumentClasses, firstVariantResults, unevaluatedHandler);
                        var type = InvokeDynamicUtils.getClassByName(instruction.getClassName(constantPoolGen));
                        return callMethod(null, type, methodName, argumentClasses, arguments, instructionHandle, invokeObject.lastInstruction,
                                constantPoolGen, thisDelay);
                    }).collect(toList());
                    return getResult(instruction, instructionHandle, constantPoolGen, invokeObject.lastInstruction, results, thisDelay);
                } else {
                    return thisDelay.evaluated(invokeObject.lastInstruction);
                }
            });
        } else if (instruction instanceof INVOKESPECIAL) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve) -> {
                var evalArguments = evalArguments(instructionHandle, argumentTypes, unevaluatedHandler, thisDelay);
                var invokeObject = evalInvokeObject(instruction, evalArguments, unevaluatedHandler, thisDelay);
                var argumentsResults = evalArguments.getArguments();
                if (needResolve) {
                    var filledVariants = resolveArguments(instruction, argumentsResults, unevaluatedHandler);
                    var results = filledVariants.stream().map(firstVariantResults -> {
                        var arguments = getArguments(argumentClasses, firstVariantResults, unevaluatedHandler);
                        var invokeSpec = (INVOKESPECIAL) instruction;
                        var lookup = MethodHandles.lookup();
                        var type = InvokeDynamicUtils.getClassByName(instruction.getClassName(constantPoolGen));
                        var signature = invokeSpec.getSignature(constantPoolGen);
                        var methodType = fromMethodDescriptorString(signature, type.getClassLoader());
                        if ("<init>".equals(methodName)) {
                            return instantiateObject(invokeObject.lastInstruction, type, argumentClasses, arguments, this, thisDelay);
                        } else {
                            var privateLookup = InvokeDynamicUtils.getPrivateLookup(type, lookup);
                            var methodHandle = getMethodHandle(() -> privateLookup.findSpecial(type,
                                    methodName, methodType, type));
                            return invoke(methodHandle, getInvokeArgs(getObject(), arguments), instructionHandle,
                                    invokeObject.lastInstruction, this, thisDelay);
                        }
                    }).collect(toList());
                    return getResult(instruction, instructionHandle, constantPoolGen, invokeObject.lastInstruction, results, thisDelay);
                } else {
                    return thisDelay.evaluated(invokeObject.lastInstruction);
                }
            });
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    public InvokeObject evalInvokeObject(InvokeInstruction invokeInstruction, Arguments evalArguments,
                                         Function<Result, Result> unevaluatedHandler, Result parent) {
        final InstructionHandle firstInstruction, lastInstruction;
        final Result objectCallResult;
        var lastArgInstruction = evalArguments.getLastArgInstruction();
        var methodName = invokeInstruction.getMethodName(constantPoolGen);
        if (invokeInstruction instanceof INVOKESPECIAL && methodName.equals("<init>")) {
            var prev = getPrev(lastArgInstruction);
            if (prev.getInstruction() instanceof DUP) {
                prev = getPrev(prev);
            }
            if (prev.getInstruction() instanceof NEW) {
                firstInstruction = prev;
                lastInstruction = prev;
            } else {
                //log warn
                firstInstruction = lastArgInstruction;
                lastInstruction = lastArgInstruction;
            }
            objectCallResult = null;
        } else if (invokeInstruction instanceof INVOKEVIRTUAL || invokeInstruction instanceof INVOKEINTERFACE) {
            var prev = getPrev(lastArgInstruction);
            objectCallResult = eval(prev, unevaluatedHandler, parent);
            firstInstruction = objectCallResult.getFirstInstruction();
            lastInstruction = objectCallResult.getLastInstruction();
        } else {
            objectCallResult = null;
            firstInstruction = lastArgInstruction;
            lastInstruction = lastArgInstruction;
        }
        return new InvokeObject(firstInstruction, lastInstruction, objectCallResult);
    }

    public List<List<Result>> resolveArguments(InvokeInstruction instruction, List<Result> argumentsResults,
                                               Function<Result, Result> unevaluatedHandler) {
        if (argumentsResults.isEmpty()) {
            return List.of(argumentsResults);
        }
        //todo arguments should be resolved in reverse mode
        var argumentsVariants = argumentsResults.stream().map(result -> resolve(result, unevaluatedHandler))
                .collect(toList());

        var callContexts = new LinkedHashMap<CallContext, Result[]>();
        for (int i = 0; i < argumentsResults.size(); i++) {
            var argumentsResult = argumentsResults.get(i);
            if (argumentsResult instanceof Const) {
                var constant = (Const) argumentsResult;
                populateArgumentsResults(argumentsResults, callContexts, constant, i);
            } else {
                var variants = argumentsVariants.get(i);
                for (var variant : variants) {
                    populateArgumentsResults(argumentsResults, callContexts, variant, i);
                }
            }
        }

        var groupedContexts = callContexts.values().stream()
                .filter(args -> Arrays.stream(args).noneMatch(Objects::isNull))
                .map(Arrays::asList).distinct().collect(partitioningBy(r -> r.stream().anyMatch(rr -> rr instanceof Const)));

        var resolved = groupedContexts.get(true);
        var fullUnresolved = groupedContexts.get(false);

        var result = resolved.isEmpty() ? fullUnresolved : resolved;
        if (result.isEmpty()) {
            throw newInvalidEvalException("no arguments variants of invocation", instruction, constantPoolGen);
        }
        return result;
    }

    private Object[] getArguments(Class<?>[] argumentTypes, List<Result> results, Function<Result, Result> unevaluatedHandler) {
        return normalizeArgumentTypes(argumentTypes, results.stream().map(result -> result.getValue(unevaluatedHandler)).toArray());
    }

    protected Result callMethod(Object object, Class<?> type, String methodName,
                                Class<?>[] argTypes, Object[] args,
                                InstructionHandle invokeInstruction,
                                InstructionHandle lastInstruction,
                                ConstantPoolGen constantPoolGen, Result parent) {
        var msg = "callMethod";
        var declaredMethod = getDeclaredMethod(methodName, type, argTypes);
        if (declaredMethod == null) {
            log.info("{}, method not found '{}.{}', instruction {}", msg, type.getName(), methodName,
                    EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
            return notFound(methodName, invokeInstruction, parent);
        } else if (!declaredMethod.trySetAccessible()) {
            log.warn("{}, method is not accessible, method '{}.{}', instruction {}", msg, type.getName(), methodName,
                    EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
            return notAccessible(declaredMethod, invokeInstruction, parent);
        }
        Object result;
        try {
            result = declaredMethod.invoke(object, args);
        } catch (IllegalAccessException e) {
            throw new EvalBytecodeException(e);
//            result = resolveStub(object, declaredMethod, e);
        } catch (InvocationTargetException e) {
            throw new EvalBytecodeException(e);
//            result = resolveStub(object, declaredMethod, e.getTargetException());
        } catch (NullPointerException e) {
            //todo just check the object is null
            throw e;
        }
        if (log.isDebugEnabled()) {
            log.debug("{}, success, method '{}.{}', result: {}, instruction {}", msg, type.getName(), methodName,
                    result, EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
        }
        return constant(result, invokeInstruction, lastInstruction, this, parent);
    }

//    private Object resolveStub(Object object, java.lang.reflect.Method declaredMethod, Throwable targetException) {
//        Object result;
//        var objectClass = object != null ? object.getClass() : null;
//        var returnType = declaredMethod.getReturnType();
//        if (log.isDebugEnabled()) {
//            log.debug("method invocation error, type {}, method {}. Trying to make stub of type {}",
//                    objectClass, declaredMethod, returnType.getName(), targetException);
//        } else {
//            log.info("method invocation error, type {}, method {}, message {}. Trying to make stub of type {}",
//                    objectClass, declaredMethod, targetException.getMessage(), returnType.getName());
//        }
//        var signature = getType(declaredMethod.getReturnType()).getSignature();
//        try {
//            result = this.methodReturnResolver.resolve(newMethodInfo(objectClass, declaredMethod.getName(), signature));
//        } catch (Exception re) {
//            throw new EvalBytecodeException(re);
//        }
//        return result;
//    }

    private Object[] normalizeArgumentTypes(Class<?>[] argumentTypes, Object[] arguments) {
        for (var i = 0; i < argumentTypes.length; i++) {
            var argumentType = argumentTypes[i];
            var argument = arguments[i];
            if (argument != null) {
                var eq = argumentType.isAssignableFrom(argument.getClass());
                if (!eq) {
                    if (argument instanceof Integer) {
                        int integer = (Integer) argument;
                        if (argumentType == boolean.class || argumentType == Boolean.class) {
                            argument = integer != 0;
                        } else if (argumentType == byte.class || argumentType == Byte.class) {
                            argument = (byte) integer;
                        } else if (argumentType == char.class || argumentType == Character.class) {
                            argument = (char) integer;
                        } else if (argumentType == short.class || argumentType == Short.class) {
                            argument = (short) integer;
                        }
                    }
                }
                arguments[i] = argument;
            }
        }

        return arguments;
    }

    public List<Result> resolve(Result value, Function<Result, Result> unevaluatedHandler) {
        if (value instanceof Variable && ((Variable) value).varType == MethodArg) {
            var variable = (Variable) value;

            var evalContext = variable.evalContext;
            var component = evalContext.component;

            var dependentOnThisComponent = getDependentOnThisComponent(component);

            var componentType = evalContext.getComponentType();
            var evalContextMethod = evalContext.getMethod();
            var methodName = evalContextMethod.getName();
            var argumentTypes = evalContextMethod.getArgumentTypes();
            var methodCallPoints = getCallPoints(componentType, methodName, argumentTypes,
                    dependentOnThisComponent, value, unevaluatedHandler);
            Map<Component, Map<CallPoint, List<Arguments>>> methodArgumentVariants = getEvalCallPointVariants(methodCallPoints, value, unevaluatedHandler);
            List<List<Result>> argumentVariants = getArgumentVariants(methodArgumentVariants, variable);

            var valueVariants = argumentVariants.stream().map(variant -> {
                var i = variable.getIndex() - 1;
                return variant.get(i);
            }).collect(toList());

            if (!valueVariants.isEmpty()) {
                var resolved = valueVariants.stream().flatMap(variant -> {
                    try {
                        return resolve(variant, unevaluatedHandler).stream();
                    } catch (UnevaluatedVariableException e) {
                        log("resolve method argument variant", e);
                        return Stream.of(variant);
                    }
                }).collect(toList());
                return resolved;
            } else {
                return List.of(variable);
            }
        } else if (value instanceof Delay) {
            try {
                return resolve(((Delay) value).getDelayed(true), unevaluatedHandler);
            } catch (UnevaluatedVariableException e) {
                log("resolve delay invocation", e);
                return List.of(value);
            }
        } else if (value instanceof Multiple) {
            return ((Multiple) value).getResults().stream().flatMap(v -> resolve(v, unevaluatedHandler).stream()).collect(toList());
        } else {
            return List.of(value);
        }
    }

    private List<Component> getDependentOnThisComponent(Component component) {
        var dependencies = dependencyToDependentMap.getOrDefault(component, List.of());
        return Stream.concat(Stream.of(component),
                dependencies.stream()).collect(toList());
    }

    private Map<Component, Map<CallPoint, List<Arguments>>> getEvalCallPointVariants(
            Map<Component, Map<CallPoint, List<CallPoint>>> callPoints, Result parent,
            Function<Result, Result> unevaluatedHandler
    ) {
        return callPoints.entrySet().stream().map(e -> {
            var dependentComponent = e.getKey();
            var callPointListMap = e.getValue();

            var variants = callPointListMap.entrySet().stream().map(ee -> {
                var dependentMethod = ee.getKey();
                var matchedCallPoints = ee.getValue();
                return evalCallPointArgumentVariants(dependentComponent, dependentMethod, matchedCallPoints, parent, unevaluatedHandler);
            }).filter(Objects::nonNull).collect(toMap(Entry::getKey, Entry::getValue));
            return entry(dependentComponent, variants);
        }).collect(toMap(Entry::getKey, Entry::getValue));
    }

    private Map<Component, Map<CallPoint, List<CallPoint>>> getCallPoints(
            Class<?> objectType, String methodName, Type[] argumentTypes, List<Component> dependentOnThisComponent,
            Result parent, Function<Result, Result> unevaluatedHandler) {
        return dependentOnThisComponent.stream().map(dependentComponent -> {
            var callPoints = getCallsHierarchy(dependentComponent, dependencyToDependentMap,
                    unevaluatedHandler, callPointsCache, parent);
            var callersWithVariants = callPoints.stream().map(dependentMethod -> {
                var matchedCallPoints = getMatchedCallPoints(dependentMethod, methodName, argumentTypes, objectType);

                return entry(dependentMethod, matchedCallPoints);
            }).filter(e -> !e.getValue().isEmpty()).collect(toMap(Entry::getKey, Entry::getValue));
            return !callersWithVariants.isEmpty() ? entry(dependentComponent, callersWithVariants) : null;
        }).filter(Objects::nonNull).collect(toMap(Entry::getKey, Entry::getValue));
    }

    private Entry<CallPoint, List<Arguments>> evalCallPointArgumentVariants(
            Component dependentComponent, CallPoint dependentMethod, List<CallPoint> matchedCallPoints,
            Result parent, Function<Result, Result> unevaluatedHandler) {
        var argVariants = matchedCallPoints.stream().map(callPoint -> {
            try {
                return evalArguments(dependentComponent, dependentMethod, callPoint, parent, unevaluatedHandler);
            } catch (UnevaluatedVariableException e) {
                log("evalArguments", e);
                return List.<Arguments>of();
            }
        }).flatMap(Collection::stream).filter(Objects::nonNull).collect(toList());

        return !argVariants.isEmpty() ? entry(dependentMethod, argVariants) : null;
    }

    private List<CallPoint> getMatchedCallPoints(CallPoint dependentMethod, String methodName,
                                                 Type[] argumentTypes, Class<?> objectType) {
        return dependentMethod.getCallPoints().stream(
        ).filter(calledMethodInsideDependent -> {
            var match = isMatch(methodName, argumentTypes, objectType, calledMethodInsideDependent);
            var cycled = isMatch(dependentMethod.getMethodName(), dependentMethod.getArgumentTypes(),
                    dependentMethod.getOwnerClass(), calledMethodInsideDependent);
            //exclude cycling
            return match && !cycled;
        }).collect(toList());
    }

    private List<List<Result>> getArgumentVariants(
            Map<Component, Map<CallPoint, List<Arguments>>> methodCallVariants, Variable parent
    ) {
        return methodCallVariants.values().stream()
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .flatMap(e -> {
                    var key = e.getKey();
                    var value = e.getValue();
                    return value.stream();
                }).map(Arguments::getArguments)
                .distinct().collect(toList());
    }

    private boolean isMatch(String expectedMethodName, Type[] expectedArguments, Class<?> objectType,
                            CallPoint calledMethodInsideDependent) {
        var calledMethod = calledMethodInsideDependent.getMethodName();
        var calledMethodArgumentTypes = calledMethodInsideDependent.getArgumentTypes();
        var calledMethodClass = getCalledMethodClass(calledMethodInsideDependent);

        var methodEquals = expectedMethodName.equals(calledMethod);
        var argumentsEqual = Arrays.equals(expectedArguments, calledMethodArgumentTypes);
        var classEquals = calledMethodClass != null && calledMethodClass.isAssignableFrom(objectType);
        return methodEquals && argumentsEqual && classEquals;
    }

    private List<Arguments> evalArguments(Component dependentComponent, CallPoint dependentMethod,
                                    CallPoint calledMethod, Result parent, Function<Result, Result> unevaluatedHandler) {
        var instructionHandle = calledMethod.getInstruction();
        var instruction = instructionHandle.getInstruction();

        var javaClass = dependentMethod.getJavaClass();
        var dependentMethodMethod = dependentMethod.getMethod();
        var constantPoolGen = new ConstantPoolGen(dependentMethodMethod.getConstantPool());
        if (calledMethod.isInvokeDynamic()) {
            var eval = new EvalBytecode(dependentComponent, this.dependencyToDependentMap, constantPoolGen,
                    JmsOperationsUtils.getBootstrapMethods(javaClass),
                    dependentMethodMethod, this.callPointsCache);

            InstructionHandle invokeDynamic = instructionHandle.getPrev();

            var bootstrapMethodAndArguments = getBootstrapMethodAndArguments(
                    (INVOKEDYNAMIC) invokeDynamic.getInstruction(), bootstrapMethods, constantPoolGen);

            Result result = eval.eval(instructionHandle, unevaluatedHandler);
            InstructionHandle lastInstruction = result.getLastInstruction();

            var argumentTypes = ((InvokeInstruction) instruction).getArgumentTypes(eval.constantPoolGen);
            var arguments = eval.evalArguments(instructionHandle, argumentTypes, unevaluatedHandler, parent);

            List<Arguments> collect = arguments.getArguments().stream().map(a -> {
                InstructionHandle firstInstruction = a.getFirstInstruction();
                Result invokeDynamicResult = eval(firstInstruction, unevaluatedHandler);

                InvokeInstruction invokeInstruction = (InvokeInstruction) firstInstruction.getInstruction();
                var argumentTypes1 = invokeInstruction.getArgumentTypes(eval.constantPoolGen);
                var arguments1 = eval.evalArguments(firstInstruction, argumentTypes1, unevaluatedHandler, a);
                var invokeObject = evalInvokeObject(invokeInstruction, arguments1, unevaluatedHandler, a);

                return arguments1;
            }).collect(toList());

            return collect;
//            throw new UnsupportedOperationException("evalArguments "+ instructionString);
        } else {
            var eval = new EvalBytecode(dependentComponent, this.dependencyToDependentMap, constantPoolGen,
                    JmsOperationsUtils.getBootstrapMethods(javaClass),
                    dependentMethodMethod, this.callPointsCache);
            var argumentTypes = ((InvokeInstruction) instruction).getArgumentTypes(eval.constantPoolGen);
            var arguments = eval.evalArguments(instructionHandle, argumentTypes, unevaluatedHandler, parent);
            return List.of(arguments);
        }
    }

    public Arguments evalArguments(InstructionHandle instructionHandle, Type[] argumentTypes,
                                   Function<Result, Result> unevaluatedHandler) {
        return evalArguments(instructionHandle, argumentTypes, unevaluatedHandler, null);
    }

    public Arguments evalArguments(InstructionHandle instructionHandle, Type[] argumentTypes,
                                   Function<Result, Result> unevaluatedHandler, Result parent) {
        var argumentsAmount = argumentTypes.length;
        var values = new Result[argumentsAmount];
        var current = instructionHandle;
        for (int i = argumentsAmount; i > 0; i--) {
            var prev = getPrev(current);
            var eval = eval(prev, unevaluatedHandler, parent);
            var valIndex = i - 1;
            values[valIndex] = eval;
            var lastInstruction = eval.getLastInstruction();
            current = lastInstruction;
        }
        return new Arguments(asList(values), current);
    }

    public InstructionHandle getPrev(InstructionHandle instructionHandle) {
        //log
        //todo multibranch????
        var firstJump = getFirstJumpedAbove(instructionHandle);
        if (firstJump != null) {
            while (true) {
                var nextJump = getFirstJumpedAbove(firstJump);
                if (nextJump != null) {
                    firstJump = nextJump;
                } else {
                    break;
                }
            }
            //todo need call eval(firstJump)
            instructionHandle = firstJump.getPrev();
        } else {
            instructionHandle = instructionHandle.getPrev();
        }
        return instructionHandle;
    }

    private InstructionHandle getFirstJumpedAbove(InstructionHandle instructionHandle) {
        var jumpsFrom = this.jumpTo.get(instructionHandle.getPosition());
        return ofNullable(jumpsFrom).flatMap(Collection::stream)
                .filter(j -> j.getPosition() < instructionHandle.getPosition())
                .findFirst().orElse(null);
    }

    public interface MethodArgumentResolver {
        Object resolve(MethodInfo method, Argument argument);

        @Data
        @FieldDefaults(makeFinal = true, level = PUBLIC)
        class Argument {
            String typeName;
            String name;
            int index;

            @Override
            public String toString() {
                return "arg(" + typeName + "," + name + "," + index + ")";
            }
        }
    }

    public interface MethodReturnResolver {
        Object resolve(MethodInfo method);
    }

    public interface Result {

//        static Const constant(Object value, InstructionHandle firstInstruction, InstructionHandle lastInstruction,
//                              EvalBytecode evalBytecode) {
//            return constant(value, firstInstruction, lastInstruction, evalBytecode, (Result) null);
//        }

        static Const constant(Object value, InstructionHandle lastInstruction, EvalBytecode evalBytecode, Result parent) {
            return constant(value, lastInstruction, lastInstruction, evalBytecode, parent);
        }

        static Const constant(Object value, InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                              EvalBytecode evalBytecode, Result parent) {
            return new Const(value, firstInstruction, lastInstruction, evalBytecode, parent);
        }

        static Delay delay(String description, InstructionHandle instructionHandle,
                           EvalBytecode evalContext, Result parent, DelayFunction delayFunction) {
            return new Delay(evalContext, description, delayFunction, instructionHandle, parent);
        }

        static Variable methodArg(EvalBytecode evalContext, LocalVariable localVariable,
                                  InstructionHandle lastInstruction, Result parent) {
            int startPC = localVariable.getStartPC();
            if (startPC > 0) {
                var componentType = evalContext.getComponentType();
                var method = evalContext.getMethod();
                throw new EvalBytecodeException("argument's variable ust has 0 startPC, " +
                        localVariable.getName() + ", " + componentType.getName() + "." +
                        method.getName() + method.getSignature());
            }
            var type = getType(localVariable.getSignature());
            int index = localVariable.getIndex();
            var name = localVariable.getName();
            return methodArg(evalContext, index, name, type, lastInstruction, parent);
        }

        static Variable methodArg(EvalBytecode evalContext, int index, String name,
                                  Type type, InstructionHandle lastInstruction, Result parent) {
            return new Variable(MethodArg, evalContext, index, name, type, lastInstruction, lastInstruction, parent);
        }

        static Variable variable(EvalBytecode evalContext, LocalVariable localVariable,
                                 InstructionHandle lastInstruction, Result parent) {
            var type = getType(localVariable.getSignature());
            int index = localVariable.getIndex();
            var name = localVariable.getName();
            return variable(evalContext, index, name, type, lastInstruction, parent);
        }

        static Variable variable(EvalBytecode evalContext, int index, String name, Type type,
                                 InstructionHandle lastInstruction, Result parent) {
            return new Variable(LocalVar, evalContext, index, name, type, lastInstruction, lastInstruction, parent);
        }

        static Illegal notAccessible(Object source, InstructionHandle callInstruction, Result parent) {
            return new Illegal(Set.of(notAccessible), source, callInstruction, callInstruction, parent);
        }

        static Illegal notFound(Object source, InstructionHandle callInstruction, Result parent) {
            return new Illegal(Set.of(Status.notFound), source, callInstruction, callInstruction, parent);
        }

        static Result multiple(List<Result> values, InstructionHandle firstInstruction, InstructionHandle lastInstruction, Result parent) {
            return new Multiple(values, firstInstruction, lastInstruction, parent);
        }

        Object getValue(Function<Result, Result> unevaluatedHandler);

        InstructionHandle getFirstInstruction();

        InstructionHandle getLastInstruction();

        interface ContextAware extends Result {
            Method getMethod();

            Component getComponent();
        }

        interface ParentAware<T extends Result> {
            T withParent(Result parent);

            Result getParent();
        }

        @Data
        @FieldDefaults(level = PRIVATE)
        class Const implements Result, ContextAware, ParentAware<Const> {
            @EqualsAndHashCode.Include
            final Object value;
            @EqualsAndHashCode.Include
            final InstructionHandle firstInstruction;
            @EqualsAndHashCode.Include
            final InstructionHandle lastInstruction;
            final EvalBytecode evalContext;
            @With
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            final Result parent;

            @Override
            public Object getValue(Function<Result, Result> unevaluatedHandler) {
                return value;
            }

            @Override
            public String toString() {
                return "const(" + value + ")";
            }

            @Override
            public Method getMethod() {
                return evalContext.getMethod();
            }

            @Override
            public Component getComponent() {
                return evalContext.getComponent();
            }
        }

        @Data
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class Illegal implements Result, ParentAware<Illegal> {
            Set<Status> status;
            Object source;
            InstructionHandle firstInstruction;
            InstructionHandle lastInstruction;
            @With
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            Result parent;

            @Override
            public Object getValue(Function<Result, Result> unevaluatedHandler) {
                if (unevaluatedHandler != null) {
                    return unevaluatedHandler.apply(this).getValue(null);
                }
                throw new IllegalInvokeException(status, source, firstInstruction);
            }

            public enum Status {
                notAccessible, notFound
            }
        }

        @Data
        @FieldDefaults(makeFinal = true, level = PROTECTED)
        class Variable implements ContextAware, ParentAware<Variable> {
            VarType varType;
            EvalBytecode evalContext;
            int index;
            String name;
            Type type;
            InstructionHandle firstInstruction;
            InstructionHandle lastInstruction;
            @With
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            Result parent;

            @Override
            public Object getValue(Function<Result, Result> unevaluatedHandler) {
                if (unevaluatedHandler != null) {
                    return unevaluatedHandler.apply(this).getValue(null);
                }
                throw new UnevaluatedVariableException(this);
            }

            @Override
            public String toString() {
                var methodName = getMethod().getName();
                var className = evalContext.getComponentType().getName();
                return varType.code + "(" + className + "." + methodName + "(" + getType() + " " + getName() + "))";
            }

            @Override
            public Method getMethod() {
                return evalContext.getMethod();
            }

            @Override
            public Component getComponent() {
                return evalContext.getComponent();
            }

            @RequiredArgsConstructor
            public enum VarType {
                MethodArg("methodArg"),
                LocalVar("localVar");

                private final String code;
            }
        }

        @Data
        @AllArgsConstructor
        @RequiredArgsConstructor
        @FieldDefaults(level = PRIVATE)
        class Delay implements ContextAware, ParentAware<Delay> {
            final EvalBytecode evalContext;
            final String description;
            final DelayFunction evaluator;
            final InstructionHandle firstInstruction;
            @With
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            final Result parent;
            InstructionHandle lastInstruction;
            Result result;
            boolean evaluated;
            boolean resolved;

            @Override
            public Object getValue(Function<Result, Result> unevaluatedHandler) {
                return getDelayed(true).getValue(unevaluatedHandler);
            }

            public InstructionHandle getFirstInstruction() {
                return firstInstruction;
            }

            public InstructionHandle getLastInstruction() {
                if (evaluated) {
                    return lastInstruction;
                }
                var delayed = getDelayed(false);
                return delayed.getLastInstruction();
            }

            public Result getDelayed(Boolean resolve) {
                Result result = this.result;
                var evaluate = !resolve;
                if (resolve && !resolved) {
                    result = evaluator.call(this, true);
                    evaluated(result.getLastInstruction());
                    this.result = result;
                    resolved = true;
                } else if (evaluate && !evaluated) {
                    result = evaluator.call(this, false);
                    this.result = result;
                    evaluated(result.getLastInstruction());
                }
                return result;
            }

            public Delay evaluated(InstructionHandle lastInstruction) {
                this.lastInstruction = lastInstruction;
                this.evaluated = true;
                var delay = new Delay(evalContext, description, evaluator, firstInstruction, parent);
                delay.evaluated = true;
                delay.lastInstruction = lastInstruction;
                return delay;
            }

            @Override
            public String toString() {
                var txt = description == null || description.isBlank() ? "" : description + ",";
                return "delay(" + txt + "evaluated:" + evaluated + ", resolved:" + resolved + ", result:" + result + ")";
            }

            @Override
            public Method getMethod() {
                return evalContext.getMethod();
            }

            @Override
            public Component getComponent() {
                return evalContext.getComponent();
            }

            @FunctionalInterface
            public interface DelayFunction {
                Result call(Delay delay, Boolean needResolve);
            }
        }

        @Data
        @Builder(toBuilder = true)
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class Multiple implements Result, ParentAware<Multiple> {
            List<Result> values;
            InstructionHandle firstInstruction;
            InstructionHandle lastInstruction;
            @With
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            Result parent;

            private void checkState() {
                if (values.isEmpty()) {
                    throw new IllegalStateException("unresolved multiple values");
                }
            }

            @Override
            public Object getValue(Function<Result, Result> unevaluatedHandler) {
                throw new IncorrectMultipleResultsInvocationException(this);
            }

            public List<Result> getResults() {
                checkState();
                return values;
            }
        }
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class InvokeObject {
        InstructionHandle firstInstruction;
        InstructionHandle lastInstruction;
        Result object;

    }

    @Data
    private static class CallContext {
        private final Component component;
        private final Method method;
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class Arguments {
        List<Result> arguments;
        InstructionHandle lastArgInstruction;
    }

}
