package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Delay.DelayFunction;
import io.github.m4gshm.connections.client.JmsOperationsUtils;
import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.CallPointsHelper.getCallsHierarchy;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.Utils.classByName;
import static io.github.m4gshm.connections.Utils.toLinkedHashSet;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.CallContext.newCallContext;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.*;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Illegal.Status.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Illegal.Status.notFound;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Illegal.Status.*;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable.VarType.LocalVar;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.notFound;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable.VarType.MethodArg;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeException.newInvalidEvalException;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeException.newUnsupportedEvalException;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.*;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getBootstrapMethodHandlerAndArguments;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.copyOfRange;
import static java.util.Map.entry;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.ofNullable;
import static lombok.AccessLevel.*;
import static org.apache.bcel.Const.*;
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
        invokeArgs.addAll(Arrays.asList(arguments));
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

    public static Result getResult(InstructionHandle instructionHandle,
                                   ConstantPoolGen constantPoolGen, InstructionHandle lastInstruction,
                                   Collection<? extends Result> results, Result prev) {
        if (results.isEmpty()) {
            throw newInvalidEvalException("empty results", instructionHandle.getInstruction(), constantPoolGen);
        }
        if (results.size() > 1) {
            return Result.multiple(new ArrayList<>(results), instructionHandle, lastInstruction, prev);
        }
        return results.iterator().next();
    }

    private static void populateArgumentsResults(List<Result> argumentsResults,
                                                 Map<CallContext, List<Result>[]> callContexts,
                                                 Result variant, int i, CallContext callContext) {
        var results = callContexts.computeIfAbsent(
                callContext, k -> new List[argumentsResults.size()]
        );
        var result = results[i];
        if (result == null) {
            result = new ArrayList<>();
            results[i] = result;
        }
        result.add(variant);
    }

    private static void populateArgumentsResults(List<Result> argumentsResults, @NonNull Result variant, int i,
                                                 Map<CallContext, List<Result>[]> callContexts,
                                                 Map<CallContext, Set<CallContext>> hierarchy) {
        if (variant instanceof Multiple) {
            var multiple = (Multiple) variant;
            //todo
            var first = multiple.getResults().get(0);
            populateArgumentsResults(argumentsResults, first, i, callContexts, hierarchy);
        } else {
            var wrapped = Result.getWrapped(variant);
            if (wrapped != null) {
                populateArgumentsResults(argumentsResults, wrapped, i, callContexts, hierarchy);
            } else {
                var callContexts1 = getCallContext(variant);

                if (!callContexts1.parents.isEmpty()) {
                    for (CallContext callContext : callContexts1.parents) {
                        populateArgumentsResults(argumentsResults, callContexts, variant, i, callContext);
                        if (callContexts1.current != null) {
                            hierarchy.computeIfAbsent(callContext, k -> new LinkedHashSet<>()).add(callContexts1.current);
                        }
                    }
                } else {
                    populateArgumentsResults(argumentsResults, callContexts, variant, i, callContexts1.current);
                }

//                var prev = Result.getPrev(variant);
//                var currentContext = callContexts1.current ;
//                if (prev instanceof PrevAware) {
//                    var prevAware = (PrevAware) prev;
//                    while (prevAware != null) {
//                        if (prev instanceof ContextAware) {
//                            var contextAware = (ContextAware) prevAware;
//                            var parentComponent = contextAware.getComponent();
//                            var parentMethod = contextAware.getMethod();
//
//                            var parentContext = newCallContext(parentComponent, parentMethod);
//                            if (!parentContext.equals(currentContext)) {
//                                hierarchy.putIfAbsent(currentContext, parentContext);
//                                currentContext = parentContext;
//                            } else {
//                                //log
//                            }
//                        }
//                        var prevOfPrev = Result.getPrev((Result) prevAware);
//                        prevAware = prevOfPrev instanceof PrevAware ? (PrevAware) prevOfPrev : null;
//                    }
//                }
//                if (!(callContexts1.current != null || variant instanceof PrevAware)) {
//                    //todo
//                    throw new UnsupportedOperationException(variant + " of arg " + argumentsResults.get(i).toString());
//                }
            }
        }
    }

    private static Contexts getCallContext(Result variant) {

        if (variant instanceof Invoked) {
            var invoked = (Invoked) variant;
            var callContext = newCallContext(invoked.getComponent(), invoked.getMethod());
            var parameters = invoked.getParameters();
            var paramContexts = parameters.stream().map(p -> {
                //todo + parents????
                return getCallContext(p).current;
            }).filter(c -> !c.equals(callContext)).collect(toLinkedHashSet());
            return new Contexts(callContext, paramContexts);
        } else if (variant instanceof ContextAware) {
            var contextAware = (ContextAware) variant;
            return new Contexts(newCallContext(contextAware.getComponent(), contextAware.getMethod()), Set.of());
        } else {
            return null;
        }
    }

    private static void log(String op, UnevaluatedResultException e) {
        var result = e.getResult();
        if (result instanceof Variable) {
            var variable = (Variable) result;
            var evalContext = variable.getEvalContext();
            var variableMethod = evalContext.getMethod();
            log.info("{} is aborted, cannot evaluate variable {}, in method {} {} of {}", op,
                    variable.getName(), variableMethod.getName(),
                    variableMethod.getSignature(), evalContext.getComponentType()
            );
        } else {
            log.info("{} is aborted, cannot evaluate result {}", op, result);
        }
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

    private static Object normalizeClass(Object object, Class<?> expectedType) {
        if (object != null && !expectedType.isAssignableFrom(object.getClass())) {
            if (object instanceof Integer) {
                int integer = (Integer) object;
                if (expectedType == boolean.class || expectedType == Boolean.class) {
                    object = integer != 0;
                } else if (expectedType == byte.class || expectedType == Byte.class) {
                    object = (byte) integer;
                } else if (expectedType == char.class || expectedType == Character.class) {
                    object = (char) integer;
                } else if (expectedType == short.class || expectedType == Short.class) {
                    object = (short) integer;
                }
            }
        }
        return object;
    }

    private static List<Component> getDependentOnThisComponent(
            Map<Component, List<Component>> dependencyToDependentMap, Component component) {
        var dependencies = dependencyToDependentMap.getOrDefault(component, List.of());
        return concat(Stream.of(component),
                dependencies.stream()).collect(toList());
    }

    private static List<Class<?>> asList(Class<?> expectedResultClass) {
        return expectedResultClass != null ? List.of(expectedResultClass) : null;
    }

    private static List<Class<?>> addExpectedResultClass(Class<?> clazz, List<Class<?>> expectedResultClass) {
        return expectedResultClass != null ? concat(expectedResultClass.stream(), Stream.of(clazz)).collect(toList()) : List.of(clazz);
    }

    private static Map<CallContext, List<Result>[]> getCallContexts(List<Result> parameters,
                                                                    List<List<Result>> parameterVariants) {
        var hierarchy = new HashMap<CallContext, Set<CallContext>>();
        var callContexts = new LinkedHashMap<CallContext, List<Result>[]>();
        for (int i1 = 0; i1 < parameters.size(); i1++) {
            var argumentsResult = parameters.get(i1);
            var wrapped = Result.getWrapped(argumentsResult);
            if (wrapped != null) {
                argumentsResult = wrapped;
            }
            if (argumentsResult instanceof Const) {
                var constant = (Const) argumentsResult;
                var callContext1 = newCallContext(constant.getComponent(), constant.getMethod());
                populateArgumentsResults(parameters, callContexts, constant, i1, callContext1);
            } /*else if (argumentsResult.isResolved() && argumentsResult instanceof ContextAware) {
                var contextAware = (ContextAware) argumentsResult;
                var callContext = new CallContext(contextAware.getComponent(), contextAware.getMethod());
                if (argumentsResult instanceof Multiple) {
                    var multiple = (Multiple) argumentsResult;
                    var multipleResults = multiple.getResults();
                    for (var variant : multipleResults) {
                        populateArgumentsResults(parameters, hierarchy, callContexts, variant, i);
                    }
                } else {
                    populateArgumentsResults(parameters, callContexts, argumentsResult, i, callContext);
                }
            } */ else {
                var variants = parameterVariants.get(i1);
                for (var variant : variants) {
                    populateArgumentsResults(parameters, variant, i1, callContexts, hierarchy);
                }
            }
        }
        for (var callContext : callContexts.keySet()) {
            var results = callContexts.get(callContext);
            for (int i = 0; i < results.length; i++) {
                var result = results[i];
                if (result == null) {
                    var parent = hierarchy.get(callContext);
                    var firstParent = parent.iterator().next();
                    var parentResults = callContexts.get(firstParent);
                    if (parentResults != null && parentResults.length > i) {
                        var parentResult = parentResults[i];
                        results[i] = parentResult;
                    }
                }
            }
        }
        return callContexts;
    }

    public static Object[] getValues(List<ParameterValue> parameterValues) {
        return parameterValues.stream().map(pv -> {
            var exception = pv.getException();
            if (exception != null) {
                throw exception;
            }
            return pv.value;
        }).toArray(Object[]::new);
    }

    private static Type getArrayType(Class<?> elementType) {
        return ObjectType.getType(Array.newInstance(elementType, 0).getClass());
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

    public Result eval(InstructionHandle instructionHandle) {
        return eval(instructionHandle, null);
    }

    public Result eval(InstructionHandle instructionHandle, Result parent) {
        var instruction = instructionHandle.getInstruction();
        var consumeStack = instruction.consumeStack(constantPoolGen);
        var instructionText = getInstructionString(instructionHandle, constantPoolGen);
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            var value = ldc.getValue(constantPoolGen);
            return constant(value, instructionHandle, instructionHandle, this, parent);
        } else if (instruction instanceof LDC2_W) {
            var ldc = (LDC2_W) instruction;
            var value = ldc.getValue(constantPoolGen);
            return constant(value, instructionHandle, instructionHandle, this, parent);
        } else if (instruction instanceof LoadInstruction) {
            var aload = (LoadInstruction) instruction;
            var aloadIndex = aload.getIndex();
            var localVariables = getLocalVariables(this.getMethod(), aloadIndex);
            var localVariable = findLocalVariable(instructionHandle, localVariables);

            var name = localVariable != null ? localVariable.getName() : null;
            if ("this".equals(name)) {
                Object value = getObject();
                return constant(value, instructionHandle, instructionHandle, this, parent);
            }

            var aStoreResults = findStoreInstructionResults(instructionHandle, localVariables, aloadIndex, parent);
            if (aStoreResults.size() == 1) {
                return delay(instructionText + " from stored invocation", instructionHandle, this, parent, null,
                        (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                            var storeResult = aStoreResults.get(0);
                            if (needResolve) {
                                var value = storeResult.getValue(expectedResultClass);
                                EvalBytecode evalBytecode = this;
                                Result parent1 = parent;
                                return constant(value, instructionHandle, instructionHandle, evalBytecode, parent1);
                            } else {
                                return thisDelay.evaluated(instructionHandle);
                            }
                        });
            } else if (!aStoreResults.isEmpty()) {
                return delay(instructionText + " from stored invocations", instructionHandle, this, parent, null,
                        (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
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
                return eval(getPrev(instructionHandle), parent);
            }
        } else if (instruction instanceof GETSTATIC) {
            var getStatic = (GETSTATIC) instruction;
            var fieldName = getStatic.getFieldName(constantPoolGen);
            var loadClassType = getStatic.getLoadClassType(constantPoolGen);
            var loadClass = getClassByName(loadClassType.getClassName());
            return getFieldValue(null, loadClass, fieldName, instructionHandle, instructionHandle, this, parent);
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;
            var evalFieldOwnedObject = eval(getPrev(instructionHandle), parent);
            var fieldName = getField.getFieldName(constantPoolGen);
            var lastInstruction = evalFieldOwnedObject.getLastInstruction();
            return getFieldValue(evalFieldOwnedObject, fieldName, instructionHandle, lastInstruction,
                    constantPoolGen, this, parent);
        } else if (instruction instanceof CHECKCAST) {
            return eval(getPrev(instructionHandle), parent);
        } else if (instruction instanceof InvokeInstruction) {
            return evalInvoke(instructionHandle, (InvokeInstruction) instruction, parent);
        } else if (instruction instanceof ANEWARRAY) {
            var anewarray = (ANEWARRAY) instruction;
            var loadClassType = anewarray.getLoadClassType(constantPoolGen);
            var arrayElementType = getClassByName(loadClassType.getClassName());
            return delay(instructionText, instructionHandle, this, parent, getArrayType(arrayElementType),
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        var size = eval(getPrev(instructionHandle), parent);
                        return constant(Array.newInstance(arrayElementType, (int) size.getValue(int.class)),
                                instructionHandle, size.getLastInstruction(), this, thisDelay);
                    });
        } else if (instruction instanceof ConstantPushInstruction) {
            var cpi = (ConstantPushInstruction) instruction;
            var value = cpi.getValue();
            return constant(value, instructionHandle, instructionHandle, this, parent);
        } else if (instruction instanceof ArrayInstruction && instruction instanceof StackConsumer) {
            //AASTORE
            return delay(instructionText, instructionHandle, this, parent, null,
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        var element = eval(getPrev(instructionHandle), thisDelay);
                        var index = eval(getPrev(element.getLastInstruction()), thisDelay);
                        var array = eval(getPrev(index.getLastInstruction()), thisDelay);
                        var lastInstruction = array.getLastInstruction();
                        if (needResolve) {
                            var result = array.getValue(Object[].class);
                            if (result instanceof Object[]) {
                                var indexValue = index.getValue(int.class);
                                ((Object[]) result)[(int) indexValue] = element.getValue(expectedResultClass);
                            } else {
                                throw newInvalidEvalException("expectedResultClass array but was " + result.getClass(), instruction, constantPoolGen);
                            }
                            return constant(result, instructionHandle, lastInstruction, this, thisDelay);
                        } else {
                            return thisDelay.evaluated(lastInstruction);
                        }
                    });
        } else if (instruction instanceof ArrayInstruction && instruction instanceof StackProducer) {
            //AALOAD
            return delay(instructionText, instructionHandle, this, parent, null,
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        var element = eval(getPrev(instructionHandle), thisDelay);
                        var index = eval(getPrev(element.getLastInstruction()), thisDelay);
                        var array = eval(getPrev(index.getLastInstruction()), thisDelay);
                        var result = array.getValue(Object[].class);
                        if (result instanceof Object[]) {
                            var a = (Object[]) result;
                            var i = (int) index.getValue(int.class);
                            var e = a[i];
                            InstructionHandle lastInstruction = array.getLastInstruction();
                            return constant(e, lastInstruction, lastInstruction, this, thisDelay);
                        } else {
                            throw newInvalidEvalException("expectedResultClass array but was " + result.getClass(),
                                    instruction, constantPoolGen);
                        }
                    });
        } else if (instruction instanceof ARRAYLENGTH) {
            return delay(instructionText, instructionHandle, this, parent, ObjectType.getType(int.class),
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        var arrayRef = eval(getPrev(instructionHandle), thisDelay);
                        var results = resolve(arrayRef, expectedResultClass, unevaluatedHandler);
                        return getResult(instructionHandle, constantPoolGen, arrayRef.getLastInstruction(), results, thisDelay);
                    });
        } else if (instruction instanceof NEW) {
            var newInstance = (NEW) instruction;
            var loadClassType = newInstance.getLoadClassType(constantPoolGen);
            return delay(instructionText, instructionHandle, this, parent, loadClassType,
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        var type = getClassByName(loadClassType.getClassName());
                        return instantiateObject(instructionHandle, type, new Class[0], new Object[0], this, thisDelay);
                    });
        } else if (instruction instanceof DUP) {
            return delay(instructionText, instructionHandle, this, parent, null,
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        var prev = instructionHandle.getPrev();
                        var duplicated = eval(prev, thisDelay);
                        return duplicated;
                    });
        } else if (instruction instanceof DUP2) {
//            return eval(getPrev(instructionHandle), unevaluatedHandler);
        } else if (instruction instanceof POP) {
            var onRemove = getPrev(instructionHandle);
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
            return eval(prev, parent);
        } else if (instruction instanceof POP2) {
//            return eval(getPrev(instructionHandle), unevaluatedHandler);
        } else if (instruction instanceof ACONST_NULL) {
            return constant(null, instructionHandle, instructionHandle, this, parent);
        } else if (instruction instanceof IfInstruction) {
            var args = new Result[consumeStack];
            var current = instructionHandle;
            for (var i = consumeStack - 1; i >= 0; --i) {
                current = getPrev(instructionHandle);
                args[i] = eval(current, parent);
            }
            var lastInstruction = args.length > 0 ? args[0].getLastInstruction() : instructionHandle;
            //now only positive scenario
            //todo need evaluate negative branch
            return eval(getPrev(lastInstruction), parent);
        } else if (instruction instanceof ConversionInstruction) {
            //I2L,
            var conv = (ConversionInstruction) instruction;
            var convertTo = conv.getType(constantPoolGen);
            return delay(instructionText, instructionHandle, this, parent, convertTo,
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        if (needResolve) {
                            var convertyToClass = getClassByNameOrNull(convertTo.getClassName());
                            var result = eval(getPrev(instructionHandle), thisDelay);
                            var value = result.getValue(convertyToClass);
                            var number = (Number) value;
                            var converted = convertNumberTo(number, convertTo);
                            return constant(converted, instructionHandle, instructionHandle, this, thisDelay);
                        } else {
                            return thisDelay.evaluated(instructionHandle);
                        }
                    });
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    private List<Result> findStoreInstructionResults(InstructionHandle instructionHandle,
                                                     List<LocalVariable> localVariables, int index,
                                                     Result parent) {
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
                    var storedInLocal = eval(prev, parent);
                    aStoreResults.add(storedInLocal);
                    prev = getPrev(prev);
                }
            }
            prev = getPrev(prev);
        }
        return aStoreResults;
    }

    protected Result evalInvoke(InstructionHandle instructionHandle, InvokeInstruction instruction, Result parent) {
        var instructionText = getInstructionString(instructionHandle, constantPoolGen);
        if (log.isTraceEnabled()) {
            log.trace("eval {}", instructionText);
        }
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var argumentClasses = toClasses(argumentTypes);

        var argumentsAmount = argumentTypes.length;
        if (instruction instanceof INVOKEVIRTUAL || instruction instanceof INVOKEINTERFACE) {
            var invokeObjectClassName = instruction.getClassName(constantPoolGen);
            var type = ObjectType.getInstance(invokeObjectClassName);
            return delay(instructionText, instructionHandle, this, parent, type,
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        var arguments = evalArguments(instructionHandle, argumentsAmount, thisDelay);
                        var invokeObject = evalInvokeObject(instruction, arguments, thisDelay);
                        return needResolve ? callInvokeVirtual(instructionHandle, thisDelay, invokeObject,
                                expectedResultClass, arguments, argumentClasses, unevaluatedHandler,
                                (parameters, lastInstruction) -> {
                                    var paramValues = getValues(parameters);
                                    var object = paramValues[0];
                                    var argValues = copyOfRange(paramValues, 1, paramValues.length);
                                    var objectClass = toClass(invokeObjectClassName);
                                    var result = callMethod(object, objectClass, methodName, argumentClasses,
                                            argValues, instructionHandle, lastInstruction,
                                            constantPoolGen, thisDelay, parameters);
                                    return result;
                                }) : thisDelay.evaluatedInvoke(invokeObject, arguments);
                    });
        } else if (instruction instanceof INVOKEDYNAMIC) {
            var returnType = instruction.getReturnType(constantPoolGen);
            return delay(instructionText, instructionHandle, this, parent, returnType,
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        var arguments = evalArguments(instructionHandle, argumentsAmount, thisDelay);
                        return needResolve ? callInvokeDynamic(instructionHandle, thisDelay, expectedResultClass,
                                arguments, argumentClasses, unevaluatedHandler, (parameters, lastInstruction) -> {
                                    var bootstrapMethodAndArguments = getBootstrapMethodHandlerAndArguments(
                                            (INVOKEDYNAMIC) instruction, bootstrapMethods, constantPoolGen);

                                    return callBootstrapMethod(getValues(parameters), instructionHandle, lastInstruction,
                                            this, bootstrapMethodAndArguments, thisDelay, parameters);
                                }) : thisDelay.evaluatedInvoke(null, arguments);
                    });
        } else if (instruction instanceof INVOKESTATIC) {
            var invokeObjectClassName = instruction.getClassName(constantPoolGen);
            var returnType = instruction.getReturnType(constantPoolGen);
            return delay(instructionText, instructionHandle, this, parent, returnType,
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        var arguments = evalArguments(instructionHandle, argumentsAmount, thisDelay);
                        return needResolve ? callInvokeStatic(instructionHandle, thisDelay, expectedResultClass,
                                arguments, argumentClasses, unevaluatedHandler, (parameters, lastInstruction) -> {
                                    var objectClass = toClass(invokeObjectClassName);
                                    var result = callMethod(null, objectClass, methodName, argumentClasses, getValues(parameters),
                                            instructionHandle, lastInstruction, constantPoolGen, thisDelay, parameters);
                                    return result;
                                }) : thisDelay.evaluatedInvoke(null, arguments);
                    });
        } else if (instruction instanceof INVOKESPECIAL) {
            var invokeObjectClassName = instruction.getClassName(constantPoolGen);
            var returnType = instruction.getReturnType(constantPoolGen);
            return delay(instructionText, instructionHandle, this, parent, returnType,
                    (thisDelay, needResolve, expectedResultClass, unevaluatedHandler) -> {
                        var arguments = evalArguments(instructionHandle, argumentsAmount, thisDelay);
                        var invokeObject = evalInvokeObject(instruction, arguments, thisDelay);
                        return needResolve ? callInvokeSpecial(instructionHandle, thisDelay, expectedResultClass,
                                invokeObject, arguments, argumentClasses, unevaluatedHandler, (parameters, lastInstruction1) -> {
                                    var invokeSpec = (INVOKESPECIAL) instruction;
                                    var lookup = MethodHandles.lookup();
                                    var objectClass = getClassByName(invokeObjectClassName);
                                    var signature = invokeSpec.getSignature(constantPoolGen);
                                    var methodType = fromMethodDescriptorString(signature, objectClass.getClassLoader());
                                    var paramValues = getValues(parameters);
                                    if ("<init>".equals(methodName)) {
                                        return instantiateObject(lastInstruction1, objectClass, argumentClasses, paramValues,
                                                this, thisDelay);
                                    } else {
                                        var privateLookup = InvokeDynamicUtils.getPrivateLookup(objectClass, lookup);
                                        var methodHandle = getMethodHandle(() -> privateLookup.findSpecial(objectClass,
                                                methodName, methodType, objectClass));
                                        return invoke(methodHandle, paramValues, instructionHandle, lastInstruction1, this, thisDelay, parameters);
                                    }
                                }) : thisDelay.evaluatedInvoke(invokeObject, arguments);
                    });
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    public Result callInvokeSpecial(InstructionHandle instructionHandle, Delay current,
                                    List<Class<?>> expectedResultClass,
                                    InvokeObject invokeObject,
                                    EvalArguments arguments, Class<?>[] argumentClasses,
                                    UnevaluatedResolver unevaluatedHandler,
                                    BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var lastInstruction = invokeObject.getLastInstruction();
        var object = invokeObject.getObject();
        var objectClass = object != null ? toClass(instruction.getClassName(constantPoolGen)) : null;
        var parameterVariants = resolveInvokeParameters(instructionHandle, current,
                expectedResultClass, object, objectClass, arguments.getArguments(), argumentClasses, unevaluatedHandler);
        var results = parameterVariants.stream().map(parameterVariant -> {
            return resolveAndInvoke(current, expectedResultClass, parameterVariant, argumentClasses,
                    lastInstruction, unevaluatedHandler, call);
        }).collect(toList());
        return getResult(instructionHandle, constantPoolGen, invokeObject.lastInstruction, results, current);
    }

    public Result callInvokeStatic(InstructionHandle instructionHandle, Delay current,
                                   List<Class<?>> expectedResultClass,
                                   EvalArguments arguments, Class<?>[] argumentClasses,
                                   UnevaluatedResolver unevaluatedHandler,
                                   BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        var lastInstruction = arguments.getLastArgInstruction();
        var parameterVariants = resolveInvokeParameters(instructionHandle, current,
                expectedResultClass, null, null, arguments.getArguments(), argumentClasses, unevaluatedHandler);
        var results = parameterVariants.stream().map(parameterVariant -> {
            return resolveAndInvoke(current, expectedResultClass, parameterVariant, argumentClasses, lastInstruction,
                    unevaluatedHandler, call);
        }).collect(toList());
        return getResult(instructionHandle, constantPoolGen, lastInstruction, results, current);
    }

    public Result callInvokeVirtual(InstructionHandle instructionHandle, Delay current,
                                    InvokeObject invokeObject, List<Class<?>> expectedResultClass,
                                    EvalArguments arguments, Class<?>[] argumentClasses,
                                    UnevaluatedResolver unevaluatedHandler,
                                    BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var objectClass = toClass(instruction.getClassName(constantPoolGen));
        var parameterVariants = resolveInvokeParameters(instructionHandle, current,
                expectedResultClass, invokeObject.getObject(), objectClass,
                arguments.getArguments(), argumentClasses, unevaluatedHandler);
        var parameterClasses = concat(Stream.ofNullable(objectClass), Stream.of(argumentClasses)).toArray(Class[]::new);
        var lastInstruction = invokeObject.lastInstruction;
        var results = parameterVariants.stream().map(parameterVariant -> {
            return resolveAndInvoke(current, expectedResultClass, parameterVariant, parameterClasses, lastInstruction,
                    unevaluatedHandler, call);
        }).collect(toList());
        return getResult(instructionHandle, constantPoolGen, lastInstruction, results, current);
    }

    public Result callInvokeDynamic(InstructionHandle instructionHandle, Delay current,
                                    List<Class<?>> expectedResultClass,
                                    EvalArguments arguments, Class<?>[] argumentClasses,
                                    UnevaluatedResolver unevaluatedHandler,
                                    BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        var lastInstruction = arguments.getLastArgInstruction();
        var parameterVariants = resolveInvokeParameters(instructionHandle, current,
                expectedResultClass, null, null, arguments.getArguments(), argumentClasses, unevaluatedHandler);
        var results = parameterVariants.stream().map(parameterVariant -> {
            return resolveAndInvoke(current, expectedResultClass, parameterVariant, argumentClasses,
                    lastInstruction, unevaluatedHandler, call);
        }).collect(toList());
        return getResult(instructionHandle, constantPoolGen, lastInstruction, results, current);
    }

    private Result resolveAndInvoke(Delay current, List<Class<?>> expectedResultClass,
                                    List<Result> parameters, Class<?>[] parameterClasses,
                                    InstructionHandle lastInstruction, UnevaluatedResolver unevaluatedHandler,
                                    BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        List<ParameterValue> parameterValues;
//        try {
        parameterValues = getParameterValues(parameters, parameterClasses, expectedResultClass, unevaluatedHandler);
//        } catch (UnevaluatedResultException e) {
//            //log
//            if (unevaluatedHandler != null) {
//                return unevaluatedHandler.resolve(current, expectedResultClass, e);
//            }
//            throw e;
//        }

        try {
            return call.apply(parameterValues, lastInstruction);
        } catch (EvalBytecodeException e) {
            //log
            if (unevaluatedHandler != null) {
                return unevaluatedHandler.resolve(current, expectedResultClass, e);
            }
            throw e;
        }
    }

    public InvokeObject evalInvokeObject(InvokeInstruction invokeInstruction, EvalArguments evalArguments, Result parent) {
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
        } else if (invokeInstruction instanceof INVOKESPECIAL || invokeInstruction instanceof INVOKEVIRTUAL
                || invokeInstruction instanceof INVOKEINTERFACE) {
            var prev = getPrev(lastArgInstruction);
            objectCallResult = eval(prev, parent);
            firstInstruction = objectCallResult.getFirstInstruction();
            lastInstruction = objectCallResult.getLastInstruction();
        } else {
            objectCallResult = null;
            firstInstruction = lastArgInstruction;
            lastInstruction = lastArgInstruction;
        }
        return new InvokeObject(firstInstruction, lastInstruction, objectCallResult);
    }

    public List<List<Result>> resolveInvokeParameters(InstructionHandle instructionHandle,
                                                      Delay current, List<Class<?>> expectedResultClass, Result object,
                                                      Class<?> objectClass,
                                                      List<Result> arguments, Class<?>[] argumentClasses,
                                                      UnevaluatedResolver unevaluatedHandler) {
        var instruction = instructionHandle.getInstruction();
        var instructionText = instruction.toString(constantPoolGen.getConstantPool());

        var parameterClasses = new ArrayList<Class<?>>(argumentClasses.length + 1);
        var parameters = new ArrayList<Result>(arguments.size() + 1);
        if (object != null) {
            parameters.add(object);
            parameterClasses.add(objectClass);
        }
        parameters.addAll(arguments);
        parameterClasses.addAll(Arrays.asList(argumentClasses));
        if (parameters.isEmpty()) {
            return List.of(parameters);
        }

        var hasVariable = parameters.stream().anyMatch(p -> p instanceof Variable);
        if (hasVariable) {
            var resolvedAll = new ArrayList<Map<Integer, List<Result>>>();
            var varPerInd = new IdentityHashMap<Variable, Integer>();
            var resolvedNorVars = new HashMap<Integer, List<Result>>();
            for (int i = 0; i < parameters.size(); i++) {
                var parameter = parameters.get(i);
                if (parameter instanceof Variable) {
                    varPerInd.put((Variable) parameter, i);
                } else {
                    var resolved = resolve(parameter, parameterClasses.get(i), unevaluatedHandler);
                    resolvedNorVars.put(i, resolved);
                }
            }

            var variablesPerComponentMethod = varPerInd.keySet().stream()
                    .collect(groupingBy(v -> v.getComponent(), groupingBy(v -> v.getMethod())));

            if (variablesPerComponentMethod.isEmpty()) {
                resolvedAll.add(resolvedNorVars);

                var parameterVariants = resolvedNorVars.values().stream().collect(toList());
                var callContexts = getCallContexts(parameters, parameterVariants);

                var groupedContexts = callContexts.values().stream()
                        .filter(args -> Arrays.stream(args).noneMatch(Objects::isNull))
                        .map(Arrays::asList).distinct().collect(partitioningBy(r -> r.stream()
                                .flatMap(Collection::stream).anyMatch(Result::isResolved)));
                var resolved = groupedContexts.get(true);
                var fullUnresolved = groupedContexts.get(false);
                if (resolved.isEmpty() && fullUnresolved.isEmpty() && !callContexts.isEmpty()) {
                    //todo debug
                    throw new IllegalStateException("bad resolved grouping");
                }

            } else {
                for (var component : variablesPerComponentMethod.keySet()) {
                    var methodListMap = variablesPerComponentMethod.get(component);
                    for (var method : methodListMap.keySet()) {
                        var variablesOfMethod = methodListMap.get(method);
                        var dependentOnThisComponent = getDependentOnThisComponent(
                                this.dependencyToDependentMap, component
                        );

                        var componentType = component.getType();
                        var methodName = method.getName();
                        var argumentTypes = method.getArgumentTypes();
                        var methodCallPoints = getCallPoints(componentType, methodName,
                                argumentTypes, dependentOnThisComponent);

                        var methodArgumentVariants = getEvalCallPointVariants(methodCallPoints, current);

                        var argumentVariants = getArgumentVariants(methodArgumentVariants);

                        List<Map<Result, Result>> variableVariantMappings = argumentVariants.stream().map(variant -> {
                            return variablesOfMethod.stream().map(variable -> {
                                var i = variable.getIndex() - 1;
                                if (i >= variant.size()) {
                                    //logs
                                    return Map.entry((Result) variable, Result.stub(variable, component, method));
                                } else {
                                    return Map.entry((Result) variable, variant.get(i));
                                }
                            }).collect(toMap(e -> e.getKey(), e -> e.getValue()));
                        }).collect(toList());


                        for (Map<Result, Result> variableVariantMapping : variableVariantMappings) {
                            var resolvedVars = new HashMap<Integer, List<Result>>();
                            for (int i = 0; i < parameters.size(); i++) {
                                var parameter = parameters.get(i);
                                var varVariant = variableVariantMapping.get(parameter);
                                if (varVariant != null) {
                                    var resolved = resolve(varVariant, (Class<?>) null, unevaluatedHandler);
                                    resolvedVars.put(i, resolved);
                                }
                            }
                            var resolved = new HashMap<Integer, List<Result>>();
                            resolved.putAll(resolvedNorVars);
                            resolved.putAll(resolvedVars);
                            resolvedAll.add(resolved);
                        }
                    }
                }
            }

            int dimensions = resolvedAll.stream().flatMap(r -> r.values().stream().map(Collection::size)).reduce((l, r) -> l * r).orElse(1);
            var resolvedParamVariants = new ArrayList<List<Result>>();
            for (var resolved : resolvedAll) {
                for (var d = 1; d <= dimensions; d++) {
                    var variantOfParameters = new ArrayList<Result>();
                    for (int i = 0; i < parameters.size(); i++) {
                        var param = parameters.get(i);
                        List<Result> variants = resolved.get(i);
                        var index = d <= variants.size() ? d - 1 : variants.size() % d - 1;
                        variantOfParameters.add(variants.get(index));
                    }
                    resolvedParamVariants.add(variantOfParameters);
                }
            }
            return resolvedParamVariants;
        } else {
            var parameterVariants = new ArrayList<List<Result>>();
            for (int i = 0; i < parameters.size(); i++) {
                var result = parameters.get(i);
                var callArg = new CallArg(instructionText, result, i, instructionHandle);
                var resolve = resolve(callArg, addExpectedResultClass(parameterClasses.get(i), expectedResultClass), unevaluatedHandler);
                parameterVariants.add(resolve);
            }
            var callContexts = getCallContexts(parameters, parameterVariants);

            var groupedContexts = callContexts.values().stream()
                    .filter(args -> Arrays.stream(args).noneMatch(Objects::isNull))
                    .map(Arrays::asList).distinct().collect(partitioningBy(r -> r.stream()
                            .flatMap(Collection::stream).anyMatch(Result::isResolved)));

            var resolved = groupedContexts.get(true);
            var fullUnresolved = groupedContexts.get(false);
            if (resolved.isEmpty() && fullUnresolved.isEmpty() && !callContexts.isEmpty()) {
                //todo debug
                throw new IllegalStateException("bad resolved grouping");
            }

            var result = resolved.isEmpty() ? fullUnresolved : resolved;
            if (result.isEmpty()) {
                throw newInvalidEvalException("no arguments variants of invocation", instruction, constantPoolGen);
            }

            int dimensions = result.stream().flatMap(Collection::stream).map(List::size).reduce((l, r) -> l * r).orElse(1);
            var resolvedParamVariants = new ArrayList<List<Result>>();
            for (var variantOfVariantOfParameters : result) {
                for (var d = 1; d <= dimensions; d++) {
                    var variantOfParameters = new ArrayList<Result>();
                    for (var variantsOfOneArgument : variantOfVariantOfParameters) {
                        var index = d <= variantsOfOneArgument.size() ? d - 1 : variantsOfOneArgument.size() % d - 1;
                        variantOfParameters.add(variantsOfOneArgument.get(index));
                    }
                    resolvedParamVariants.add(variantOfParameters);
                }
            }
            return resolvedParamVariants;
        }
    }

    public List<ParameterValue> getParameterValues(List<Result> parameters,
                                                   Class<?>[] parameterClasses,
                                                   List<Class<?>> expectedResultClass,
                                                   UnevaluatedResolver unevaluatedHandler) {
        var size = parameters.size();
        var values = new ParameterValue[size];
        for (var i = 0; i < size; i++) {
            var expectedResultClass1 = addExpectedResultClass(parameterClasses[i], expectedResultClass);
            var result = parameters.get(i);
            try {
                var value = result.getValue(parameterClasses[i], unevaluatedHandler);
                values[i] = new ParameterValue(result, i, value, null, expectedResultClass1);
            } catch (UnevaluatedResultException e) {
                //log
                if (unevaluatedHandler != null) {

                    var resolved = unevaluatedHandler.resolve(result, expectedResultClass1, e);
                    if (resolved.isResolved()) {
                        var value = resolved.getValue(expectedResultClass1);
                        values[i] = new ParameterValue(result, i, value, null, expectedResultClass1);
                    } else {
                        //log
                        values[i] = new ParameterValue(result, i, null, new UnevaluatedParameterException(e, i), expectedResultClass1);
//                        throw e;
                    }
                } else {
                    values[i] = new ParameterValue(result, i, null, new UnevaluatedParameterException(e, i), expectedResultClass1);
//                    throw e;
                }
            }
        }
        return Arrays.asList(normalizeClasses(values, parameterClasses));
    }

    protected Result callMethod(Object object, Class<?> type, String methodName,
                                Class<?>[] argTypes, Object[] args,
                                InstructionHandle invokeInstruction,
                                InstructionHandle lastInstruction,
                                ConstantPoolGen constantPoolGen, Result parent, List<ParameterValue> parameters) {
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
            //log
            throw new IllegalInvokeException(Set.of(notAccessible), new MethodCallInfo(declaredMethod, object, args), invokeInstruction);
        } catch (InvocationTargetException e) {
            //log
            throw new IllegalInvokeException(Set.of(illegalTarget), new MethodCallInfo(declaredMethod, object, args), invokeInstruction);
        } catch (IllegalArgumentException e) {
            //log
            throw new IllegalInvokeException(Set.of(illegalArgument), new MethodCallInfo(declaredMethod, object, args), invokeInstruction);
        } catch (NullPointerException e) {
            //todo just check the object is null
            throw e;
        }
        if (log.isDebugEnabled()) {
            log.debug("{}, success, method '{}.{}', result: {}, instruction {}", msg, type.getName(), methodName,
                    result, EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
        }

        return invoked(result, invokeInstruction, lastInstruction, this, parent, parameters);
//        return constant(result, invokeInstruction, lastInstruction, this, parent);
    }

    private ParameterValue[] normalizeClasses(ParameterValue[] objects, Class<?>[] objectTypes) {
        for (var i = 0; i < objectTypes.length; i++) {
            var object = objects[i];
            objects[i] = new ParameterValue(object.getParameter(), object.getIndex(),
                    normalizeClass(object.value, objectTypes[i]), object.exception, object.expectedResultClass);
        }
        return objects;
    }

    public List<Result> resolve(Result value, Class<?> expectedResultClass, UnevaluatedResolver unevaluatedHandler) {
        return resolve(value, expectedResultClass != null ? List.of(expectedResultClass) : null, unevaluatedHandler);
    }

    //todo move to Result class
    public List<Result> resolve(Result value, List<Class<?>> expectedResultClass, UnevaluatedResolver unevaluatedHandler) {
        if (value instanceof CallArg) {
            var callArg = (CallArg) value;
            var result = callArg.wrapped();
            var resolved = resolve(result, expectedResultClass, unevaluatedHandler);
            return resolved.stream().map(r -> new CallArg(callArg.description, r, callArg.index,
                    callArg.instructionHandle)).collect(toList());
        } else if (value instanceof Variable && ((Variable) value).varType == MethodArg) {
            var variable = (Variable) value;

            var evalContext = variable.getEvalContext();
            var component = evalContext.getComponent();

            var dependentOnThisComponent = getDependentOnThisComponent(
                    evalContext.dependencyToDependentMap, component
            );

            var componentType = evalContext.getComponentType();
            var evalContextMethod = evalContext.getMethod();
            var methodName = evalContextMethod.getName();
            var argumentTypes = evalContextMethod.getArgumentTypes();
            var methodCallPoints = getCallPoints(componentType, methodName, argumentTypes,
                    dependentOnThisComponent);
            var methodArgumentVariants = getEvalCallPointVariants(methodCallPoints, value);
            var argumentVariants = getArgumentVariants(methodArgumentVariants);

            var valueVariants = argumentVariants.stream().map(variant -> {
                var i = variable.getIndex() - 1;
                if (i >= variant.size()) {
                    //logs
                    return Result.stub(variable, component, evalContextMethod);
                } else {
                    return variant.get(i);
                }
            }).collect(toList());

            if (!valueVariants.isEmpty()) {
                var resolveResult = valueVariants.stream().flatMap(variant -> {
                    if (variant instanceof Result.Stub) {
                        return Stream.of(variant);
                    }
                    try {
                        return resolve(variant, expectedResultClass, unevaluatedHandler).stream();
                    } catch (UnevaluatedResultException e) {
                        log("resolve method argument variant", e);
                        return Stream.of(variant);
                    }
                }).collect(toList());
                return resolveResult;
            } else {
                return List.of(variable);
            }
        } else if (value instanceof Delay) {
            try {
                var delayed = ((Delay) value).getDelayed(true, expectedResultClass, unevaluatedHandler);
                if (delayed instanceof Multiple) {
                    return (List<Result>) ((Multiple) delayed).getValues();
                } else {
                    return List.of(delayed);
                }
                //                return resolve(delayed, expectedResultClass, unevaluatedHandler);
            } catch (UnevaluatedResultException e) {
                log("resolve delay invocation", e);
//                if (e != value) {
//
//                }
//                if (unevaluatedHandler != null) {
//                    return List.of(unevaluatedHandler.resolve(value, e.getResult(), List.of(expectedResultClass)));
//                }
                throw e;
//                return List.of(value);
            }
            //} else if (value instanceof Multiple) {
            //  return ((Multiple) value).getResults().stream().flatMap(v -> resolve(v, expectedResultClass, unevaluatedHandler).stream()).collect(toList());
        } else {
            return List.of(value);
        }
    }

    private Map<Component, Map<CallPoint, List<EvalArguments>>> getEvalCallPointVariants(
            Map<Component, Map<CallPoint, List<CallPoint>>> callPoints, Result parent
    ) {
        return callPoints.entrySet().stream().map(e -> {
            var dependentComponent = e.getKey();
            var callPointListMap = e.getValue();
            var variants = callPointListMap.entrySet().stream().map(ee -> {
                var dependentMethod = ee.getKey();
                var matchedCallPoints = ee.getValue();
                return evalCallPointArgumentVariants(dependentComponent, dependentMethod, matchedCallPoints, parent);
            }).filter(Objects::nonNull).collect(toMap(Entry::getKey, Entry::getValue));
            return entry(dependentComponent, variants);
        }).collect(toMap(Entry::getKey, Entry::getValue));
    }

    private Map<Component, Map<CallPoint, List<CallPoint>>> getCallPoints(
            Class<?> objectType, String methodName, Type[] argumentTypes, List<Component> dependentOnThisComponent) {
        return dependentOnThisComponent.stream().map(dependentComponent -> {
            var callPoints = getCallsHierarchy(dependentComponent, callPointsCache);
            var callersWithVariants = callPoints.stream().map(dependentMethod -> {
                var matchedCallPoints = getMatchedCallPoints(dependentMethod, methodName, argumentTypes, objectType);
                return entry(dependentMethod, matchedCallPoints);
            }).filter(e -> !e.getValue().isEmpty()).collect(toMap(Entry::getKey, Entry::getValue));
            return !callersWithVariants.isEmpty() ? entry(dependentComponent, callersWithVariants) : null;
        }).filter(Objects::nonNull).collect(toMap(Entry::getKey, Entry::getValue));
    }

    private Entry<CallPoint, List<EvalArguments>> evalCallPointArgumentVariants(
            Component dependentComponent, CallPoint dependentMethod, List<CallPoint> matchedCallPoints,
            Result parent) {
        var argVariants = matchedCallPoints.stream().map(callPoint -> {
            try {
                return evalArguments(dependentComponent, dependentMethod, callPoint, parent);
            } catch (UnevaluatedResultException e) {
                log("evalArguments", e);
                return List.<EvalArguments>of();
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
            Map<Component, Map<CallPoint, List<EvalArguments>>> methodCallVariants
    ) {
        return methodCallVariants.values().stream()
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .flatMap(e -> e.getValue().stream()).map(EvalArguments::getArguments)
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

    private List<EvalArguments> evalArguments(Component dependentComponent, CallPoint dependentMethod,
                                              CallPoint calledMethod, Result parent) {
        var instructionHandle = calledMethod.getInstruction();
        var instruction = instructionHandle.getInstruction();

        var javaClass = dependentMethod.getJavaClass();
        var dependentMethodMethod = dependentMethod.getMethod();
        var constantPoolGen = new ConstantPoolGen(dependentMethodMethod.getConstantPool());
        var invokeInstruction = (InvokeInstruction) instruction;
        if (calledMethod.isInvokeDynamic()) {
            var eval = new EvalBytecode(dependentComponent, this.dependencyToDependentMap, constantPoolGen,
                    JmsOperationsUtils.getBootstrapMethods(javaClass), dependentMethodMethod, this.callPointsCache);

            var invokeDynamicArgumentTypes = invokeInstruction.getArgumentTypes(eval.constantPoolGen);
            var referenceKind = calledMethod.getReferenceKind();
            var removeCallObjectArg = referenceKind == REF_invokeSpecial
                    || referenceKind == REF_invokeVirtual
                    || referenceKind == REF_invokeInterface;
            var arguments = eval.evalArguments(instructionHandle, invokeDynamicArgumentTypes.length, parent);
            if (removeCallObjectArg) {
                var withoutCallObject = new ArrayList<>(arguments.getArguments());
                withoutCallObject.remove(0);
                arguments = new EvalArguments(withoutCallObject, arguments.getLastArgInstruction());
            }

            var expectedArgumentTypes = calledMethod.getArgumentTypes();
            int consumedByInvokeDynamicArgumentsAmount = arguments.getArguments().size();
            int functionalInterfaceArgumentsAmount = expectedArgumentTypes.length - consumedByInvokeDynamicArgumentsAmount;
            if (functionalInterfaceArgumentsAmount > 0 && parent instanceof Variable) {
                var parentVariable = (Variable) parent;
                var index = parentVariable.index - 1;
                if (index >= consumedByInvokeDynamicArgumentsAmount) {
                    var stubbedArguments = new ArrayList<>(arguments.getArguments());

                    for (var i = 0; i < functionalInterfaceArgumentsAmount; i++) {
                        stubbedArguments.add(Result.stub(parentVariable, dependentComponent, dependentMethod.getMethod()));
                    }
                    arguments = new EvalArguments(stubbedArguments, arguments.getLastArgInstruction());
                }
            }

            return List.of(arguments);
        } else {
            var eval = new EvalBytecode(dependentComponent, this.dependencyToDependentMap, constantPoolGen,
                    JmsOperationsUtils.getBootstrapMethods(javaClass),
                    dependentMethodMethod, this.callPointsCache);
            var argumentTypes = invokeInstruction.getArgumentTypes(eval.constantPoolGen);
            var arguments = eval.evalArguments(instructionHandle, argumentTypes.length, parent);
            return List.of(arguments);
        }
    }

    public EvalArguments evalArguments(InstructionHandle instructionHandle, int argumentsAmount, Result parent) {
        var values = new Result[argumentsAmount];
        var current = instructionHandle;
        for (int i = argumentsAmount; i > 0; i--) {
            var prev = getPrev(current);
            var eval = eval(prev, parent);
            var valIndex = i - 1;
            values[valIndex] = eval;
            current = eval.getLastInstruction();
        }
        return new EvalArguments(Arrays.asList(values), current);
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

    public interface Result {

        static Invoked invoked(Object value, InstructionHandle invokeInstruction, InstructionHandle lastInstruction,
                               EvalBytecode context, Result parent, List<ParameterValue> parameters) {
            var params = parameters.stream().map(ParameterValue::getParameter).collect(toList());
            return new Invoked(value, invokeInstruction, lastInstruction, context, parent, params);
        }

        static Const constant(Object value, InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                              EvalBytecode evalBytecode, Result parent) {
            return new Const(value, firstInstruction, lastInstruction, evalBytecode, parent);
        }

        static Delay delay(String description, InstructionHandle instructionHandle,
                           EvalBytecode evalContext, Result parent, Type expectedResultType,
                           DelayFunction delayFunction) {
            return new Delay(evalContext, description, delayFunction, instructionHandle, parent, expectedResultType);
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
            return new Illegal(Set.of(notFound), source, callInstruction, callInstruction, parent);
        }

        static Result multiple(List<? extends Result> values, InstructionHandle firstInstruction,
                               InstructionHandle lastInstruction, Result parent) {
            var flatValues = values.stream().flatMap(v -> v instanceof Multiple
                    ? ((Multiple) v).getValues().stream()
                    : Stream.of(v)).distinct().collect(toList());
            if (flatValues.isEmpty()) {
                throw new IllegalArgumentException("unresolved multiple values");
            } else if (flatValues.size() == 1) {
                return flatValues.get(0);
            } else {
                return new Multiple(flatValues, firstInstruction, lastInstruction, parent);
            }
        }


        static Result stub(Variable value, Component component, Method method) {
            return new Stub(method, component, value);
        }

        static Result getPrev(Result result) {
            if (result instanceof PrevAware) return ((PrevAware) result).getPrev();
            var wrapped = getWrapped(result);
            if (wrapped != null) {
                return getPrev(wrapped);
            }
            return null;
        }

        static Result getWrapped(Result result) {
            if (result instanceof Wrapper) {
                var wrapped = ((Wrapper) result).wrapped();
                var touched = new HashSet<Result>();
                touched.add(wrapped);
                while (wrapped instanceof Wrapper) {
                    var result1 = getWrapped(wrapped);
                    if (touched.add(result1)) {
                        wrapped = result1;
                    } else {
                        break;
                    }
                }
                return wrapped;
            }
            return null;
        }

        Object getValue(List<Class<?>> expectedResultClass);

        default Object getValue(UnevaluatedResolver unevaluatedHandler) {
            return getValue((Class<?>) null, unevaluatedHandler);
        }

//        default Object getValue() {
//            return getValue((List<Class<?>>) null);
//        }

        default Object getValue(Class<?> expectedResultClass) {
            return getValue(expectedResultClass != null ? List.of(expectedResultClass) : null, null);
        }

        default Object getValue(Class<?> expectedResultClass, UnevaluatedResolver unevaluatedHandler) {
            return getValue(expectedResultClass != null ? List.of(expectedResultClass) : null, unevaluatedHandler);
        }

        default Object getValue(List<Class<?>> expectedResultClass, UnevaluatedResolver unevaluatedHandler) {
            try {
                return getValue(expectedResultClass);
            } catch (EvalBytecodeException e) {
                if (unevaluatedHandler != null) {
                    var resolved = unevaluatedHandler.resolve(this, expectedResultClass, e);
                    return resolved.getValue(expectedResultClass);
                }
                throw e;
            }
        }

        InstructionHandle getFirstInstruction();

        InstructionHandle getLastInstruction();

        default boolean isResolved() {
            return false;
        }

        interface UnevaluatedResolver {
            Result resolve(Result current, List<Class<?>> expectedResultClass, EvalBytecodeException cause);
        }

        interface ContextAware extends Result {
            Method getMethod();

            Component getComponent();

            default Class<?> getComponentType() {
                return getComponent().getType();
            }
        }

        interface PrevAware {
            Result getPrev();
        }

        interface Wrapper {
            Result wrapped();
        }

        @Data
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class InvokeWithUnresolvedParameters implements Result {
            Delay delay;
            List<ParameterValue> parameterValues;

            @Override
            public Object getValue(List<Class<?>> expectedResultClass) {
                throw new UnevaluatedResultException("invoke with unresolved parameters", this);
            }

            @Override
            public InstructionHandle getFirstInstruction() {
                return delay.getFirstInstruction();
            }

            @Override
            public InstructionHandle getLastInstruction() {
                return delay.getLastInstruction();
            }
        }

        @Data
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class Stub implements Result, ContextAware {
            Method method;
            Component component;
            Variable stubbed;

            @Override
            public Object getValue(List<Class<?>> expectedResultClass) {
                throw new UnevaluatedResultException("stubbed", stubbed);
            }

            @Override
            public InstructionHandle getFirstInstruction() {
                return stubbed.getFirstInstruction();
            }

            @Override
            public InstructionHandle getLastInstruction() {
                return stubbed.getLastInstruction();
            }

        }

        @Data
        @FieldDefaults(level = PRIVATE)
        class Const implements Result, ContextAware, PrevAware {
            @EqualsAndHashCode.Include
            final Object value;
            @EqualsAndHashCode.Include
            final InstructionHandle firstInstruction;
            @EqualsAndHashCode.Include
            final InstructionHandle lastInstruction;
            @EqualsAndHashCode.Include
            final EvalBytecode evalContext;
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            final Result prev;

            @Override
            public String toString() {
                return "const(" + value + ")";
            }

            @Override
            public Method getMethod() {
                return getEvalContext().getMethod();
            }

            @Override
            public Component getComponent() {
                return getEvalContext().getComponent();
            }

            @Override
            public Object getValue(List<Class<?>> expectedResultClass) {
                return value;
            }

            @Override
            public boolean isResolved() {
                return true;
            }
        }

        @Data
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class Illegal implements Result, PrevAware {
            Set<Status> status;
            Object source;
            InstructionHandle firstInstruction;
            InstructionHandle lastInstruction;
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            Result prev;

            @Override
            public Object getValue(List<Class<?>> expectedResultClass) {
                throw new IllegalInvokeException(status, source, firstInstruction);
            }

            public enum Status {
                notAccessible, notFound, stub, illegalArgument, illegalTarget;
            }
        }

        @Data
        @FieldDefaults(makeFinal = true, level = PROTECTED)
        class Variable implements ContextAware, PrevAware {
            VarType varType;
            EvalBytecode evalContext;
            int index;
            String name;
            Type type;
            InstructionHandle firstInstruction;
            InstructionHandle lastInstruction;
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            Result prev;

            @Override
            public Object getValue(List<Class<?>> expectedResultClass) {
                throw new UnevaluatedResultException("unresolved", this);
            }

            @Override
            public String toString() {
                var methodName = getMethod().getName();
                var className = evalContext.getComponentType().getName();
                return varType.code + "(" + className + "." + methodName + "(" + getIndex() + " " + getName() + "))";
            }

            @Override
            public Method getMethod() {
                return evalContext.getMethod();
            }

            @Override
            public Component getComponent() {
                return evalContext.getComponent();
            }

            @Override
            public Class<?> getComponentType() {
                return evalContext.getComponentType();
            }

            @RequiredArgsConstructor
            public enum VarType {
                MethodArg("methodArg"),
                LocalVar("localVar");

                private final String code;
            }
        }

        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class EvaluatedInvoke extends Delay {
            Result object;
            List<Result> arguments;

            public EvaluatedInvoke(EvalBytecode evalContext, String description, DelayFunction evaluator,
                                   InstructionHandle firstInstruction, Result prev, Type expectedResultType,
                                   InstructionHandle lastInstruction,
                                   Result object, List<Result> arguments) {
                super(evalContext, description, evaluator, firstInstruction, prev, expectedResultType,
                        lastInstruction, null, true, false);
                this.object = object;
                this.arguments = arguments;
            }
        }

        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class Invoked extends Const {
            @Getter
            List<Result> parameters;

            public Invoked(Object value, InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                           EvalBytecode evalContext, Result prev, List<Result> parameters) {
                super(value, firstInstruction, lastInstruction, evalContext, prev);
                this.parameters = parameters;
            }

            @Override
            public String toString() {
                return "invoked(" + getValue() + ")";
            }
        }

        @Data
        @AllArgsConstructor
        @RequiredArgsConstructor
        @FieldDefaults(level = PRIVATE)
        class Delay implements ContextAware, PrevAware {
            final EvalBytecode evalContext;
            final String description;
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            final DelayFunction evaluator;
            @Getter
            final InstructionHandle firstInstruction;
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            final Result prev;
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            final Type expectedResultType;
            InstructionHandle lastInstruction;
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            Result result;
            boolean evaluated;
            boolean resolved;

            @Override
            public Object getValue(List<Class<?>> expectedResultClass) {
                var delayed = getDelayed(true, expectedResultClass, null);
                if (delayed == this) {
                    throw new EvalBytecodeException("looped delay 2");
                }
                return delayed.getValue(expectedResultClass);
            }

            public InstructionHandle getLastInstruction() {
                if (evaluated) {
                    return lastInstruction;
                }
                var delayed = getDelayed(false, null, null);
                return delayed.getLastInstruction();
            }

            public Result getDelayed(boolean resolve, List<Class<?>> expectedResultClass, UnevaluatedResolver unevaluatedHandler) {
                var result = this.result;
                var evaluate = !resolve;
                if (resolve && !resolved) {
                    result = evaluator.call(this, true, expectedResultClass, unevaluatedHandler);
                    if (result == this) {
                        throw new EvalBytecodeException("looped delay 1");
                    }
                    this.result = result;
                    this.resolved = true;
                    this.evaluated = true;
                } else if (evaluate && !evaluated) {
                    result = evaluator.call(this, false, expectedResultClass, unevaluatedHandler);
                    this.result = result;
                    this.evaluated = true;
                }
                return result;
            }

            public Delay evaluated(InstructionHandle lastInstruction) {
                this.lastInstruction = lastInstruction;
                var delay = new Delay(evalContext, description, evaluator, firstInstruction, prev, expectedResultType,
                        lastInstruction, null, true, false);
                return delay;
            }

            public EvaluatedInvoke evaluatedInvoke(InvokeObject invokeObject, EvalArguments arguments) {
                var lastInstruction = invokeObject != null ? invokeObject.lastInstruction : arguments.lastArgInstruction;
                var object = invokeObject != null ? invokeObject.getObject() : null;
                this.lastInstruction = lastInstruction;
                var delay = new EvaluatedInvoke(evalContext, description, evaluator, firstInstruction, this,
                        expectedResultType, lastInstruction, object, arguments.getArguments());
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

            @Override
            public Class<?> getComponentType() {
                return evalContext.getComponentType();
            }

            @FunctionalInterface
            public interface DelayFunction {
                Result call(Delay delay, Boolean needResolve, List<Class<?>> expectedResultClass, UnevaluatedResolver unevaluatedHandler);
            }
        }

        @Data
        @Builder(toBuilder = true)
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class Multiple implements Result, PrevAware {
            List<? extends Result> values;
            InstructionHandle firstInstruction;
            InstructionHandle lastInstruction;
            @EqualsAndHashCode.Exclude
            @ToString.Exclude
            Result prev;

            private void checkState() {
                if (values.isEmpty()) {
                    throw new IllegalStateException("unresolved multiple values");
                }
            }

            @Override
            public Object getValue(List<Class<?>> expectedResultClass) {
                throw new IllegalMultipleResultsInvocationException(this);
            }

            public List<? extends Result> getValues() {
                return values;
            }

            public List<? extends Result> getResults() {
                checkState();
                return values;
            }
        }

        @Data
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class CallArg implements Result, Wrapper {
            String description;
            Result result;
            int index;
            InstructionHandle instructionHandle;

            @Override
            public Object getValue(List<Class<?>> expectedResultClass) {
                return result.getValue(expectedResultClass);
            }

            @Override
            public InstructionHandle getFirstInstruction() {
                return instructionHandle;
            }

            @Override
            public InstructionHandle getLastInstruction() {
                return instructionHandle;
            }

            @Override
            public Result wrapped() {
                return result;
            }

            @Override
            public boolean isResolved() {
                return result.isResolved();
            }

            @Override
            public String toString() {
                return "callArgument(i:" + index + "='" + result + "' of '" + description + "')";
            }
        }
    }

    @RequiredArgsConstructor
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    static class Contexts {
        CallContext current;
        Set<CallContext> parents;
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class ParameterValue {
        Result parameter;
        int index;
        Object value;
        UnevaluatedParameterException exception;
        List<Class<?>> expectedResultClass;
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class InvokeObject {
        InstructionHandle firstInstruction;
        InstructionHandle lastInstruction;
        Result object;
    }

    @Data
    static class CallContext {
        private final Component component;
        private final Method method;

        public static CallContext newCallContext(Component component, Method method) {
            return new CallContext(component, method);
        }

        @Override
        public String toString() {
            return component.getName() + ":" + method;
        }
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class EvalArguments {
        List<Result> arguments;
        InstructionHandle lastArgInstruction;

        @Override
        public String toString() {
            return "arguments" + arguments;
        }
    }

}
