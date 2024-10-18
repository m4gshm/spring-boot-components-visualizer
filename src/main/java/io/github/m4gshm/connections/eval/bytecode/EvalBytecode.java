package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.client.JmsOperationsUtils;
import io.github.m4gshm.connections.eval.result.*;
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
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.CallPointsHelper.getCallsHierarchy;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.Utils.classByName;
import static io.github.m4gshm.connections.eval.bytecode.ArithmeticUtils.computeArithmetic;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecode.CallContext.newCallContext;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeException.newInvalidEvalException;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeException.newUnsupportedEvalException;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.*;
import static io.github.m4gshm.connections.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodHandlerAndArguments;
import static io.github.m4gshm.connections.eval.bytecode.LocalVariableUtils.*;
import static io.github.m4gshm.connections.eval.result.Result.*;
import static io.github.m4gshm.connections.eval.result.Variable.VarType.MethodArg;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;
import static java.util.Map.entry;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.ofNullable;
import static lombok.AccessLevel.PRIVATE;
import static lombok.AccessLevel.PROTECTED;
import static org.apache.bcel.Const.*;

@Slf4j
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@FieldDefaults(makeFinal = true, level = PRIVATE)
@AllArgsConstructor(access = PROTECTED)
public class EvalBytecode {
    @Getter
    @EqualsAndHashCode.Include
    Component component;
    @Getter
    @EqualsAndHashCode.Include
    Method method;
    @With
    Map<Integer, Result> arguments;
    @Getter
    List<List<Result>> argumentVariants;

    //for debug
    Code methodCode;

    @Getter
    ConstantPoolGen constantPoolGen;
    @Getter
    BootstrapMethods bootstrapMethods;
    Map<Integer, List<InstructionHandle>> jumpTo;
//    Map<Component, List<Component>> dependencyToDependentMap;
//    Map<Component, List<CallPoint>> callPointsCache;
    Map<CallCacheKey, Result> callCache;

    public EvalBytecode(@NonNull Component component,
                        @NonNull Map<Component, List<Component>> dependencyToDependentMap,
                        @NonNull ConstantPoolGen constantPoolGen,
                        BootstrapMethods bootstrapMethods,
                        @NonNull Method method, Map<Component, List<CallPoint>> callPointsCache,
                        Map<CallCacheKey, Result> callCache) {
        this.component = component;
        this.constantPoolGen = constantPoolGen;
        this.bootstrapMethods = bootstrapMethods;
        this.method = method;
        this.methodCode = method.getCode();
//        this.dependencyToDependentMap = dependencyToDependentMap;
        this.jumpTo = instructionHandleStream(method.getCode()).map(instructionHandle -> {
            var instruction = instructionHandle.getInstruction();
            if (instruction instanceof BranchInstruction) {
                return entry(((BranchInstruction) instruction).getTarget().getPosition(), instructionHandle);
            } else {
                return null;
            }
        }).filter(Objects::nonNull).collect(groupingBy(Entry::getKey, mapping(Entry::getValue, toList())));
//        this.callPointsCache = callPointsCache;
        this.callCache = callCache;
        this.arguments = null;
        this.argumentVariants = computeArgumentVariants(component, method, dependencyToDependentMap, callPointsCache, callCache);
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
                                                 Map<CallContext, Set<CallContext>> childToParentHierarchy) {
        if (variant instanceof Multiple) {
            var multiple = (Multiple) variant;
            //todo
            var first = multiple.getResults().get(0);
            populateArgumentsResults(argumentsResults, first, i, callContexts, childToParentHierarchy);
        } else {
            var wrapped = getWrapped(variant);
            if (wrapped != null) {
                populateArgumentsResults(argumentsResults, wrapped, i, callContexts, childToParentHierarchy);
            } else {
                var contextHierarchy = getCallContext(variant);
                if (contextHierarchy == null) {
                    //log
                } else if (!contextHierarchy.parents.isEmpty()) {
                    for (var parent : contextHierarchy.parents) {
                        populateArgumentsResults(argumentsResults, callContexts, variant, i, parent.current);
                    }
                    populateReverseHierarchy(contextHierarchy, childToParentHierarchy);
                } else {
                    populateArgumentsResults(argumentsResults, callContexts, variant, i, contextHierarchy.current);
                }
            }
        }
    }

    private static void populateReverseHierarchy(ContextHierarchy contextHierarchy,
                                                 Map<CallContext, Set<CallContext>> childToParentHierarchy) {
        var current = contextHierarchy.current;
        if (current != null) {
            for (var parent : contextHierarchy.parents) {
                childToParentHierarchy.computeIfAbsent(parent.current, k -> new LinkedHashSet<>()).add(current);
                populateReverseHierarchy(parent, childToParentHierarchy);
            }
        }
    }

    private static ContextHierarchy getCallContext(Result variant) {
        CallContext callContext;
        var contextAware = (ContextAware) variant;
        callContext = newCallContext(contextAware.getComponent(), contextAware.getMethod(), variant);
        if (variant instanceof RelationsAware) {
            var relationsAware = (RelationsAware) variant;
            var relations = relationsAware.getRelations();
            var parentContexts = relations.stream().map(variant1 -> {
                var contextHierarchy = getCallContext(variant1);
                return contextHierarchy;
            }).filter(c -> c.current != null).flatMap(c -> {
                return callContext.equals(c.current) ? c.parents.stream() : Stream.of(c);
            }).collect(toMap(c -> c.current, c -> c.parents, (l, r) -> {
                var collect = concat(l.stream(), r.stream()).filter(h -> h.current != null).collect(toSet());
                return collect;
            }));
            var parents = parentContexts.entrySet().stream().map(e -> new ContextHierarchy(e.getKey(), e.getValue()))
                    .collect(toSet());
            return new ContextHierarchy(callContext, parents);
        }
        return new ContextHierarchy(callContext, Set.of());
    }

    private static void log(String op, UnresolvedResultException e) {
        var result = e.getResult();
        if (result instanceof Variable) {
            var variable = (Variable) result;
            var evalContext = variable.getEvalContext();
            var variableMethod = evalContext.getMethod();
            log.info("{} is aborted, cannot evaluate variable {}, in method {} {} of {}", op,
                    variable.getName(), variableMethod.getName(),
                    variableMethod.getSignature(), evalContext.getComponent().getType()
            );
        } else {
            log.info("{} is aborted, cannot evaluate result {}", op, result);
        }
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

    private static List<Object> normalizeClass(Collection<Object> objects, Class<?> expectedType) {
        return objects == null
                ? null
                : objects.stream().map(object -> normalizeClass(object, expectedType)).collect(toList());
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

    private static Map<CallContext, List<Result>[]> getCallContexts(List<Result> parameters,
                                                                    List<List<Result>> parameterVariants) {
        var childToParentHierarchy = new HashMap<CallContext, Set<CallContext>>();
        var callContexts = new LinkedHashMap<CallContext, List<Result>[]>();
        for (int i1 = 0; i1 < parameters.size(); i1++) {
//            var argumentsResult = parameters.get(i1);
//            var wrapped = getWrapped(argumentsResult);
//            if (wrapped != null) {
//                argumentsResult = wrapped;
//            }
//            if (argumentsResult instanceof Constant) {
//                var constant = (Constant) argumentsResult;
//                var callContext1 = newCallContext(constant.getComponent(), constant.getMethod(), constant);
//                populateArgumentsResults(parameters, callContexts, constant, i1, callContext1);
//            } else {
            var variants = parameterVariants.get(i1);
            for (var variant : variants) {
                populateArgumentsResults(parameters, variant, i1, callContexts, childToParentHierarchy);
            }
//            }
        }
        var ignore = new HashSet<CallContext>();
        for (var callContext : callContexts.keySet()) {
            var results = callContexts.get(callContext);
            for (int i = 0; i < results.length; i++) {
                var result = results[i];
                if (result == null) {
                    var variants = parameterVariants.get(i);
                    if (variants.size() == 1) {
                        results[i] = variants;
                    } else {
                        var parent = childToParentHierarchy.get(callContext);
                        var firstParent = parent != null ? parent.iterator().next() : null;
                        var parentResults = firstParent != null ? callContexts.get(firstParent) : null;
                        if (parentResults != null && parentResults.length > i) {
                            var parentResult = parentResults[i];
                            results[i] = parentResult;
                        } else {
                            //log
//                            throw new IllegalStateException("no parent callContext for multiple variants of parameter "
//                                    + i + ", " + parameters.get(i));
                            ignore.add(callContext);
                        }
                    }
                }
            }
        }
        for (var callContext : ignore) {
            callContexts.remove(callContext);
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

    private static <T> int getDimensions(Collection<List<T>> variantOfVariantOfParameters) {
        return variantOfVariantOfParameters.stream().map(List::size).reduce((l, r) -> l * r).orElse(1);
    }

    private static List<List<Result>> flatResolvedVariants(int dimensions, List<List<Result>> parameterVariants,
                                                           List<Result> parameters) {
        var resolvedVariants = new ArrayList<List<Result>>();
        for (var d = 1; d <= dimensions; d++) {
            var variantOfParameters = new ArrayList<Result>();
            for (var variantsOfOneArgument : parameterVariants) {
                var index = d <= variantsOfOneArgument.size() ? d - 1 : variantsOfOneArgument.size() % d - 1;
                variantOfParameters.add(variantsOfOneArgument.get(index));
            }
            if (variantOfParameters.size() != parameters.size()) {
                throw new IllegalStateException("Expected " + parameters.size() + " parameters but got " +
                        variantOfParameters.size() + ", parameters: " + parameters);
            }
            resolvedVariants.add(variantOfParameters);
        }
        return resolvedVariants;
    }

    public static List<Result> expand(Result result) {
        return result instanceof Multiple ? ((Multiple) result).getResults() : List.of(result);
    }

    public static Result collapse(Collection<? extends Result> values, InstructionHandle instructionHandle,
                                  InstructionHandle lastInstruction, ConstantPoolGen constantPool,
                                  Component component, Method method) {
        return collapse(values, instructionHandle, lastInstruction, constantPool.getConstantPool(), component, method);
    }

    public static Result collapse(Collection<? extends Result> values, InstructionHandle instructionHandle,
                                  InstructionHandle lastInstruction, ConstantPool constantPool, Component component, Method method) {
        if (values.isEmpty()) {
            throw newInvalidEvalException("empty results", instructionHandle.getInstruction(), constantPool);
        }
        if (values.size() > 1) {
            return multiple(new ArrayList<>(values), instructionHandle, lastInstruction, component, method);
        }
        var first = values.iterator().next();
        return first;
    }

    private static Result resolveOrThrow(Result result, Resolver resolver, UnresolvedResultException e) {
        if (resolver != null) {
            return resolver.resolve(result, e);
        } else {
            throw e;
        }
    }

    private static List<List<ParameterValue>> getCallParameters(List<ParameterVariants> parameterVariants) {
        var callParameters = new ArrayList<List<ParameterValue>>();
        int dimensions = parameterVariants.stream().map(p -> p.values).filter(Objects::nonNull)
                .map(List::size).reduce(1, (l, r) -> l * r);
        for (var d = 1; d <= dimensions; d++) {
            var parameterValues = new ArrayList<ParameterValue>();
            for (var parameterVariant : parameterVariants) {
                ParameterValue parameterValue;
                var exception = parameterVariant.exception;
                if (exception != null) {
                    parameterValue = new ParameterValue(parameterVariant.parameter, parameterVariant.index, null, exception);
                } else {
                    var values = parameterVariant.values;
                    var size = values.size();
                    int index = (d <= size ? d : size % d) - 1;
                    var value = values.get(index);
                    parameterValue = new ParameterValue(parameterVariant.parameter, parameterVariant.index, value, null);
                }
                parameterValues.add(parameterValue);
            }
            callParameters.add(parameterValues);
        }
        return callParameters;
    }

    private static Result call(Delay current, InstructionHandle lastInstruction, Resolver resolver,
                               List<List<ParameterValue>> callParameters, BiFunction<List<ParameterValue>, InstructionHandle, Result> call,
                               ConstantPoolGen constantPoolGen, Component component, Method method) {
        try {
            //log
            var values = callParameters.stream().map(cp -> {
                return call.apply(cp, lastInstruction);
            }).collect(toList());
            return collapse(values, current.getFirstInstruction(), lastInstruction,
                    constantPoolGen, component, method);
        } catch (EvalBytecodeException e) {
            //log
            if (resolver == null) {
                throw e;
            }
            return resolver.resolve(current, e);
        }
    }

    private static List<List<List<Result>>> getFullDistinctCallContexts(Map<CallContext, List<Result>[]> callContexts) {
        return callContexts.values().stream()
                .filter(args -> Arrays.stream(args).noneMatch(Objects::isNull))
                .map(Arrays::asList).distinct().collect(toList());
    }

    public static Result callInvokeSpecial(DelayInvoke invoke, Class<?>[] argumentClasses, EvalBytecode eval,
                                           boolean throwNoCall, Resolver resolver,
                                           BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        return eval.callWithParameterVariants(invoke, argumentClasses, throwNoCall, resolver, call);
    }

    public static Result callInvokeStatic(DelayInvoke invoke, Class<?>[] argumentClasses, EvalBytecode eval,
                                          boolean throwNoCall, Resolver resolver,
                                          BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        return eval.callWithParameterVariants(invoke, argumentClasses, throwNoCall, resolver, call);
    }

    public static Result callInvokeVirtual(InstructionHandle instructionHandle, DelayInvoke invoke,
                                           Class<?>[] argumentClasses, EvalBytecode eval, boolean throwNoCall, Resolver resolver,
                                           BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var objectClass = toClass(instruction.getClassName(eval.getConstantPoolGen()));
        var parameterClasses = concat(ofNullable(objectClass), Stream.of(argumentClasses)).toArray(Class[]::new);
        return eval.callWithParameterVariants(invoke, parameterClasses, throwNoCall, resolver, call);
    }

    public static Result callInvokeDynamic(DelayInvoke invoke, Class<?>[] argumentClasses, EvalBytecode eval,
                                           boolean throwNoCall, Resolver resolver,
                                           BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        return eval.callWithParameterVariants(invoke, argumentClasses, throwNoCall, resolver, call);
    }

    public static List<Result> toParameters(Result object, List<Result> arguments) {
        var parameters = new ArrayList<Result>(arguments.size() + 1);
        if (object != null) {
            parameters.add(object);
        }
        parameters.addAll(arguments);
        return parameters;
    }

    private static List<Map<Integer, Result>> getEvalContextArgsVariants(int dimensions,
                                                                         Map<Integer, Result> evalContextArgs) {
        var evalContextArgsVariants = new ArrayList<Map<Integer, Result>>();
        for (var d = 1; d <= dimensions; d++) {
            var variant = new HashMap<Integer, Result>();
            for (var paramIndex : evalContextArgs.keySet()) {
                var arg = evalContextArgs.get(paramIndex);
                if (arg instanceof Multiple) {
                    var multiple = (Multiple) arg;
                    var results = multiple.getResults();
                    var index = d <= results.size() ? d - 1 : results.size() % d - 1;
                    var result = results.get(index);
                    variant.put(paramIndex, result);
                } else {
                    variant.put(paramIndex, arg);
                }
            }
            evalContextArgsVariants.add(variant);
        }
        return evalContextArgsVariants;
    }

    private static HashMap<Integer, List<Result>> getResolvedVars1(List<Result> parameters, Resolver resolver, EvalBytecode evalWithArguments) {
        var resolvedVars1 = new HashMap<Integer, List<Result>>();
        for (int i = 0; i < parameters.size(); i++) {
            var parameter = parameters.get(i);
            try {
                if (parameter instanceof Delay) {
                    parameter = ((Delay) parameter).withEval(evalWithArguments);
                }
                var results = evalWithArguments.resolveExpand(parameter, resolver);
                resolvedVars1.put(i, results);
            } catch (NoCallException e) {
                //log
                if (resolver != null) {
                    resolvedVars1.put(i, expand(resolver.resolve(parameter, e)));
                } else {
                    resolvedVars1 = null;
                    break;
                }
            }
        }
        return resolvedVars1;
    }

    private static List<CallPoint> getMatchedCallPoints(CallPoint dependentMethod, String methodName,
                                                        Type[] argumentTypes, Class<?> objectType) {
        return dependentMethod.getCallPoints().stream().filter(calledMethodInsideDependent -> {
            var match = isMatch(methodName, argumentTypes, objectType, calledMethodInsideDependent);
            var cycled = isMatch(dependentMethod.getMethodName(), dependentMethod.getArgumentTypes(),
                    dependentMethod.getOwnerClass(), calledMethodInsideDependent);
            //exclude cycling
            return match && !cycled;
        }).collect(toList());
    }

    private static boolean isMatch(String expectedMethodName, Type[] expectedArguments, Class<?> objectType,
                                   CallPoint calledMethodInsideDependent) {
        var calledMethod = calledMethodInsideDependent.getMethodName();
        var calledMethodArgumentTypes = calledMethodInsideDependent.getArgumentTypes();
        var calledMethodClass = getCalledMethodClass(calledMethodInsideDependent);

        var methodEquals = expectedMethodName.equals(calledMethod);
        var argumentsEqual = Arrays.equals(expectedArguments, calledMethodArgumentTypes);
        var classEquals = calledMethodClass != null && calledMethodClass.isAssignableFrom(objectType);
        return methodEquals && argumentsEqual && classEquals;
    }

    public String getComponentName() {
        return getComponent().getName();
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

    public Result eval(InstructionHandle instructionHandle) {
        return eval(instructionHandle, null);
    }

    public Result eval(InstructionHandle instructionHandle, Result parent) {
        var instruction = instructionHandle.getInstruction();
        var consumeStack = instruction.consumeStack(constantPoolGen);
        var instructionText = getInstructionString(instructionHandle, constantPoolGen);
        var component = getComponent();
        var method = getMethod();
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            var value = ldc.getValue(constantPoolGen);
            return constant(value, instructionHandle, instructionHandle, component, method, parent);
        } else if (instruction instanceof LDC2_W) {
            var ldc = (LDC2_W) instruction;
            var value = ldc.getValue(constantPoolGen);
            return constant(value, instructionHandle, instructionHandle, component, method, parent);
        } else if (instruction instanceof LoadInstruction) {
            var loadInstruction = (LoadInstruction) instruction;
            var loadIndex = loadInstruction.getIndex();
            var localVariables = getLocalVariables(method, loadIndex);
            var localVariable = findLocalVariable(method, localVariables, instructionHandle);

            var name = localVariable != null ? localVariable.getName() : null;
            if ("this".equals(name)) {
                var value = getObject();
                return constant(value, instructionHandle, instructionHandle, component, method, parent);
            }

            var storeResults = findStoreInstructionResults(instructionHandle, localVariables, loadIndex, parent);
            if (!storeResults.isEmpty()) {
                var description = instructionText + " from stored invocation";
                var storeInstructions = storeResults.stream()
                        .map(storeResult -> eval(storeResult.getFirstInstruction(), parent))
                        .collect(toList());
                return delayLoadFromStored(description, instructionHandle, this, parent, storeInstructions,
                        (thisDelay, needResolve, resolver) -> {
                            if (needResolve) {
                                var eval = thisDelay.getEval();
                                var resolved = thisDelay.getStoreInstructions().stream()
                                        .flatMap(storeResult -> expand(eval.resolve(storeResult, resolver)).stream())
                                        .collect(toList());
                                return collapse(resolved, instructionHandle, instructionHandle, constantPoolGen,
                                        component, method);
                            } else {
                                return thisDelay;
                            }
                        });
            }
            if (log.isDebugEnabled()) {
                log.debug("not found store for {}", instructionText);
            }
//            var evaluatedMethodArg = this.arguments.get(loadIndex);
//            if (evaluatedMethodArg != null) {
//                log
//                return evaluatedMethodArg;
            /*} else */
            if (localVariable == null) {
                var argumentType = this.method.getArgumentTypes()[loadIndex - 1];
                return methodArg(this, loadIndex, null, argumentType, instructionHandle, parent);
            } else {
                return methodArg(this, localVariable, instructionHandle, parent);
            }
        } else if (instruction instanceof StoreInstruction) {
            int position = instructionHandle.getPosition();
            var codeException = Arrays.stream(this.method.getCode().getExceptionTable())
                    .filter(et -> et.getHandlerPC() == position)
                    .findFirst().orElse(null);
            if (codeException != null) {
                var catchType = constantPoolGen.getConstantPool().getConstantString(
                        codeException.getCatchType(), CONSTANT_Class);
                var errType = ObjectType.getInstance(catchType);
                var localVarIndex = ((StoreInstruction) instruction).getIndex();
                var localVariable = getLocalVariable(this.method, localVarIndex, instructionHandle);
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
            return getFieldValue(null, loadClass, fieldName, instructionHandle, instructionHandle, parent, component, method);
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

            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var loadClassType = anewarray.getLoadClassType(thisDelay.getEval().constantPoolGen);
                var arrayElementType = getClassByName(loadClassType.getClassName());
                var size = eval(getPrev(instructionHandle), parent);
                return constant(Array.newInstance(arrayElementType, (int) size.getValue()),
                        instructionHandle, size.getLastInstruction(), component, getMethod(), thisDelay, size);
            });
        } else if (instruction instanceof ConstantPushInstruction) {
            var cpi = (ConstantPushInstruction) instruction;
            var value = cpi.getValue();
            return constant(value, instructionHandle, instructionHandle, component, method, parent);
        } else if (instruction instanceof ArrayInstruction && instruction instanceof StackConsumer) {
            //AASTORE
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var element = eval(getPrev(instructionHandle), thisDelay);
                var index = eval(getPrev(element.getLastInstruction()), thisDelay);
                var array = eval(getPrev(index.getLastInstruction()), thisDelay);
                var lastInstruction = array.getLastInstruction();
                if (needResolve) {
                    var result = array.getValue();
                    if (result instanceof Object[]) {
                        var indexValue = index.getValue();
                        var value = element.getValue();
                        ((Object[]) result)[(int) indexValue] = value;
                    } else {
                        throw newInvalidEvalException("expectedResultClass array but was " + result.getClass(),
                                instruction, constantPoolGen.getConstantPool());
                    }
                    return constant(result, instructionHandle, lastInstruction, component, getMethod(), element, index, array);
                } else {
                    return thisDelay.evaluated(lastInstruction);
                }
            });
        } else if (instruction instanceof ArrayInstruction && instruction instanceof StackProducer) {
            //AALOAD
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var element = eval(getPrev(instructionHandle), thisDelay);
                var index = eval(getPrev(element.getLastInstruction()), thisDelay);
                var array = eval(getPrev(index.getLastInstruction()), thisDelay);
                var lastInstruction = array.getLastInstruction();
                if (needResolve) {
                    var result = array.getValue();
                    if (result instanceof Object[]) {
                        var a = (Object[]) result;
                        var i = (int) index.getValue();
                        var e = a[i];
                        return constant(e, lastInstruction, lastInstruction,
                                component, getMethod(), element, index, array);
                    } else {
                        throw newInvalidEvalException("expected result class array but was " + result.getClass(),
                                instruction, constantPoolGen.getConstantPool());
                    }
                } else {
                    return thisDelay.evaluated(lastInstruction);
                }
            });
        } else if (instruction instanceof ARRAYLENGTH) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var arrayRef = eval(getPrev(instructionHandle), thisDelay);
                if (needResolve) {
                    return thisDelay.getEval().resolve(arrayRef, resolver);
                } else {
                    return thisDelay.evaluated(arrayRef.getLastInstruction());
                }
            });
        } else if (instruction instanceof NEW) {
            var newInstance = (NEW) instruction;
            var loadClassType = newInstance.getLoadClassType(constantPoolGen);
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var type = getClassByName(loadClassType.getClassName());
                return instantiateObject(instructionHandle, type, new Class[0], new Object[0], thisDelay, thisDelay.getEval().getComponent(), thisDelay.getEval().getMethod());
            });
        } else if (instruction instanceof DUP) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var prev = instructionHandle.getPrev();
                return eval(prev, thisDelay);
            });
        } else if (instruction instanceof DUP2) {
//            return eval(getPrev(instructionHandle), resolver);
        } else if (instruction instanceof POP) {
            var onRemove = getPrev(instructionHandle);
            //log removed
//            var prev = onRemove.getLastInstruction().getPrev();
            //todo on remove must produce stack
            var onRemoveInstruction = onRemove.getInstruction();
            var stackProducer = onRemoveInstruction instanceof StackProducer;
            if (!stackProducer) {
                throw newInvalidEvalException("pop stack variable must be produced by prev instruction",
                        onRemoveInstruction, constantPoolGen.getConstantPool());
            }
            var prev = onRemove.getPrev();
            return eval(prev, parent);
        } else if (instruction instanceof POP2) {
//            return eval(getPrev(instructionHandle), resolver);
        } else if (instruction instanceof ACONST_NULL) {
            return constant(null, instructionHandle, instructionHandle, component, method, parent);
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
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var convertedValueInstruction = getPrev(instructionHandle);
                var convertedValueResult = eval(convertedValueInstruction, thisDelay);
                var lastInstruction = convertedValueResult.getLastInstruction();
                if (needResolve) {
                    var values = convertedValueResult.getValue(resolver, thisDelay.getEval());
                    var results = values.stream()
                            .map(value -> (Number) value)
                            .map(number -> convertNumberTo(number, convertTo))
                            .map(converted -> constant(converted, instructionHandle, lastInstruction,
                                    getComponent(), getMethod(), thisDelay))
                            .collect(toList());
                    return collapse(results, instructionHandle, lastInstruction, getConstantPoolGen(), getComponent(), getMethod());
                } else {
                    return thisDelay.evaluated(lastInstruction);
                }
            });
        } else if (instruction instanceof ArithmeticInstruction) {
            var arith = (ArithmeticInstruction) instruction;
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var first = eval(getPrev(instructionHandle), thisDelay);
                var second = consumeStack == 2 ? eval(getPrev(first.getLastInstruction())) : null;
                var lastInstruction = second != null ? second.getLastInstruction() : first.getLastInstruction();
                if (needResolve) {
                    try {
                        var computed = computeArithmetic(arith, first, second);
                        return constant(computed, instructionHandle, lastInstruction, component, getMethod(), first, second);
                    } catch (UnresolvedResultException e) {
                        return resolveOrThrow(thisDelay, resolver, e);
                    }
                } else {
                    return thisDelay.evaluated(lastInstruction);
                }
            });
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen.getConstantPool());
    }

    public List<Result> findStoreInstructionResults(InstructionHandle instructionHandle,
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
            var arguments = evalArguments(instructionHandle, argumentsAmount, null);
            var invokeObject = evalInvokeObject(instruction, arguments, null);
            return delayInvoke(instructionHandle, this, parent, invokeObject, arguments, (thisDelay, needResolve, resolver) -> {
                return needResolve ? callInvokeVirtual(instructionHandle, thisDelay, argumentClasses, thisDelay.getEval(), true, resolver, (parameters, lastInstruction) -> {
                    var paramValues = getValues(parameters);
                    var object = paramValues[0];
                    var argValues = copyOfRange(paramValues, 1, paramValues.length);
                    var objectClass = toClass(invokeObjectClassName);
                    var result = callMethod(object, objectClass, methodName, argumentClasses,
                            argValues, instructionHandle, lastInstruction,
                            constantPoolGen, thisDelay, parameters);
                    return result;
                }) : thisDelay;
            });
        } else if (instruction instanceof INVOKEDYNAMIC) {
            var arguments = evalArguments(instructionHandle, argumentsAmount, null);
            return delayInvoke(instructionHandle, this, parent, null, arguments, (thisDelay, needResolve, resolver) -> {
                return needResolve ? callInvokeDynamic(thisDelay, argumentClasses, thisDelay.getEval(), true, resolver, (parameters, lastInstruction) -> {
                    var bootstrapMethodAndArguments = getBootstrapMethodHandlerAndArguments(
                            (INVOKEDYNAMIC) instruction, bootstrapMethods, constantPoolGen);
                    return callBootstrapMethod(getValues(parameters), instructionHandle, lastInstruction,
                            thisDelay.getEval(), bootstrapMethodAndArguments, parameters);
                }) : thisDelay;
            });
        } else if (instruction instanceof INVOKESTATIC) {
            var invokeObjectClassName = instruction.getClassName(constantPoolGen);
            var arguments = evalArguments(instructionHandle, argumentsAmount, null);
            return delayInvoke(instructionHandle, this, parent, null, arguments, (thisDelay, needResolve, resolver) -> {
                return needResolve ? callInvokeStatic(thisDelay, argumentClasses, thisDelay.getEval(), true, resolver, (parameters, lastInstruction) -> {
                    var objectClass = toClass(invokeObjectClassName);
                    var result = callMethod(null, objectClass, methodName, argumentClasses, getValues(parameters),
                            instructionHandle, lastInstruction, constantPoolGen, thisDelay, parameters);
                    return result;
                }) : thisDelay;
            });
        } else if (instruction instanceof INVOKESPECIAL) {
            var invokeObjectClassName = instruction.getClassName(constantPoolGen);
            var arguments = evalArguments(instructionHandle, argumentsAmount, null);
            var invokeObject = evalInvokeObject(instruction, arguments, null);
            return delayInvoke(instructionHandle, this, parent, invokeObject, arguments, (thisDelay, needResolve, resolver) -> {
                return needResolve ? callInvokeSpecial(thisDelay, argumentClasses, this, true, resolver, (parameters, lastInstruction) -> {
                    var invokeSpec = (INVOKESPECIAL) instruction;
                    var lookup = MethodHandles.lookup();
                    var objectClass = getClassByName(invokeObjectClassName);
                    var signature = invokeSpec.getSignature(constantPoolGen);
                    var methodType = fromMethodDescriptorString(signature, objectClass.getClassLoader());
                    var paramValues = getValues(parameters);
                    var component = getComponent();
                    var method = getMethod();
                    if ("<init>".equals(methodName)) {
                        return instantiateObject(lastInstruction, objectClass, argumentClasses, paramValues, thisDelay,
                                component, method);
                    } else {
                        var privateLookup = InvokeDynamicUtils.getPrivateLookup(objectClass, lookup);
                        var methodHandle = getMethodHandle(() -> privateLookup.findSpecial(objectClass,
                                methodName, methodType, objectClass));
                        return invoke(methodHandle, paramValues, instructionHandle, lastInstruction,
                                parameters, component, method);
                    }
                }) : thisDelay;
            });
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen.getConstantPool());
    }

    private Result callWithParameterVariants(DelayInvoke invoke, Class<?>[] parameterClasses, boolean throwNoCall,
                                             Resolver resolver, BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        var argumentsArguments = invoke.getArguments();
        var parameters = toParameters(invoke.getObject(), argumentsArguments);
        var parameterVariants = resolveInvokeParameters(parameters, resolver, true, invoke);
        var lastInstruction = invoke.getLastInstruction();
        var results = parameterVariants.stream().map(parameterVariant -> {
            return resolveAndInvoke(invoke, parameterVariant, parameterClasses, lastInstruction, resolver, call);
        }).collect(toList());
        if (results.isEmpty()) {
            if (throwNoCall) {
                throw new NoCallException(invoke);
            } else {
                return resolveAndInvoke(invoke, parameters, parameterClasses, lastInstruction, resolver, call);
            }
        }
        return collapse(results, invoke.getFirstInstruction(), lastInstruction, getConstantPoolGen(), getComponent(), getMethod());
    }

    private Result resolveAndInvoke(Delay current, List<Result> parameters, Class<?>[] parameterClasses,
                                    InstructionHandle lastInstruction, Resolver resolver,
                                    BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {

        var parameterVariants = getParameterValues(parameters, parameterClasses, resolver);
        var callParameters = getCallParameters(parameterVariants);

        var key = new CallCacheKey(current, callParameters, lastInstruction.getInstruction());
        var cached = callCache.get(key);
        if (cached != null) {
            log.trace("get cached call result, call '{}', result '{}'", key, cached);
            return cached;
        }

        var callResult = call(current, lastInstruction, resolver, callParameters, call, getConstantPoolGen(),
                getComponent(), getMethod());
        log.trace("no cached call result, call '{}', result '{}'", key, callResult);
        callCache.put(key, callResult);
        return callResult;
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

    public List<List<Result>> resolveInvokeParameters(List<Result> parameters, Resolver resolver,
                                                      boolean resolveUncalledVariants, Result parent) {
        if (parameters.isEmpty()) {
            return List.of(parameters);
        }

        if (!(this.arguments == null || this.arguments.isEmpty())) {
            var parameterVariants = parameters.stream().map(parameter -> this.resolveExpand(parameter, resolver)).collect(toList());
            int dimensions = getDimensions(parameterVariants);
            return flatResolvedVariants(dimensions, parameterVariants, parameters);
        } else {
            var resolvedAll = new ArrayList<Map<Integer, List<Result>>>();
            var argumentVariants = getArgumentVariants();
            for (var arguments : argumentVariants) {
                var evalContextArgs = getEvalContextArgs(arguments, resolveUncalledVariants, resolver);
                if (evalContextArgs != null) {
                    var dimensions = evalContextArgs.values().stream()
                            .map(r -> r instanceof Multiple ? ((Multiple) r).getResults().size() : 1)
                            .reduce(1, (l, r) -> l * r);
                    var evalContextArgsVariants = getEvalContextArgsVariants(dimensions, evalContextArgs);
                    for (var variant : evalContextArgsVariants) {
                        var evalWithArguments = this.withArguments(variant);
                        var resolvedVars1 = getResolvedVars1(parameters, resolver, evalWithArguments);
                        if (resolvedVars1 != null) {
                            resolvedAll.add(resolvedVars1);
                        }
                    }
                }
            }

            var resolvedParamVariants = new ArrayList<List<Result>>();
            for (var resolvedVariantMap : resolvedAll) {
                var parameterVariants = new ArrayList<>(resolvedVariantMap.values());
                int dimensions = getDimensions(parameterVariants);
                if (dimensions <= 3) {
                    resolvedParamVariants.addAll(flatResolvedVariants(dimensions, parameterVariants, parameters));
                } else {
                    var callContexts = getCallContexts(parameters, parameterVariants);
                    var result = getFullDistinctCallContexts(callContexts);
                    if (result.isEmpty()) {
//                        var callContexts2 = getCallContexts(parameters, parameterVariants);
                        //log WARN todo
                        //no common call contexts ????
                        resolvedParamVariants.addAll(flatResolvedVariants(1, parameterVariants, parameters));
                    } else {
                        for (var variantOfVariantOfParameters : result) {
                            resolvedParamVariants.addAll(flatResolvedVariants(
                                    getDimensions(variantOfVariantOfParameters),
                                    variantOfVariantOfParameters, parameters)
                            );
                        }
                    }
                }
            }
            return resolvedParamVariants;
        }
    }

    private Map<Integer, Result> getEvalContextArgs(List<Result> arguments, boolean resolveNoCall, Resolver resolver) {
        var evalContextArgs = new HashMap<Integer, Result>();
        for (int i = 0; i < arguments.size(); i++) {
            var value = arguments.get(i);
            Result resolved;
            try {
                resolved = resolve(value, resolver);
            } catch (NoCallException e) {
                //log
                if (resolveNoCall) {
                    resolved = resolver.resolve(value, e);
                } else {
                    return null;
                }
            } catch (EvalBytecodeException e) {
                //log
                return null;
            }
            evalContextArgs.put(i + 1, resolved);
        }
        return evalContextArgs;
    }

    public List<ParameterVariants> getParameterValues(List<Result> parameters, Class<?>[] parameterClasses, Resolver resolver) {
        var size = parameters.size();
        var values = new ParameterVariants[size];
        for (var i = 0; i < size; i++) {
            var result = parameters.get(i);
            try {
                var value = result.getValue(resolver, this);
                values[i] = new ParameterVariants(result, i, value, null);
            } catch (UnresolvedResultException e) {
                //log
                if (resolver != null) {
                    var resolved = resolver.resolve(result, e);
                    if (resolved.isResolved()) {
                        var variants = resolved.getValue(resolver, this);
                        values[i] = new ParameterVariants(result, i, variants, null);
                    } else {
                        //log
                        values[i] = new ParameterVariants(result, i, null, e);
                    }
                } else {
                    values[i] = new ParameterVariants(result, i, null, e);
                }
            }
        }
        return asList(normalizeClasses(values, parameterClasses));
    }

    protected Result callMethod(Object object, Class<?> type, String methodName, Class<?>[] argTypes, Object[] args,
                                InstructionHandle invokeInstruction, InstructionHandle lastInstruction,
                                ConstantPoolGen constantPoolGen, Delay source, List<ParameterValue> parameters) {
        var msg = "callMethod";
        if (object != null && !type.isAssignableFrom(object.getClass())) {
            log.debug("unexpected callable object type {}, expected {}, object '{}', method {}",
                    object.getClass().getName(), type.getName(), object, methodName);
        }
        var declaredMethod = getDeclaredMethod(type, methodName, argTypes);
        if (declaredMethod == null) {
            log.info("{}, method not found '{}.{}', instruction {}", msg, type.getName(), methodName,
                    EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
            return notFound(methodName, invokeInstruction, source);
        } else if (!declaredMethod.trySetAccessible()) {
            log.warn("{}, method is not accessible, method '{}.{}', instruction {}", msg, type.getName(), methodName,
                    EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
            return notAccessible(declaredMethod, invokeInstruction, source);
        }
        Object result;
        try {
            result = declaredMethod.invoke(object, args);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            //log
            throw new IllegalInvokeException(source, invokeInstruction, e);
        } catch (Exception e) {
            //log
            throw e;
        }
        if (log.isDebugEnabled()) {
            log.debug("{}, success, method '{}.{}', result: {}, instruction {}", msg, type.getName(), methodName,
                    result, EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
        }
        return invoked(result, invokeInstruction, lastInstruction, this.getComponent(), this.getMethod(), parameters);
    }

    private ParameterVariants[] normalizeClasses(ParameterVariants[] objects, Class<?>[] objectTypes) {
        for (var i = 0; i < objectTypes.length; i++) {
            var object = objects[i];
            objects[i] = new ParameterVariants(object.getParameter(), object.getIndex(),
                    normalizeClass(object.values, objectTypes[i]), object.exception);
        }
        return objects;
    }

    public List<Result> resolveExpand(Result value, Resolver resolver) {
        return expand(resolve(value, resolver));
    }

    public Result resolve(Result value, Resolver resolver) {
        Result result;
        if (value instanceof Variable && ((Variable) value).getVarType() == MethodArg) {
            var variable = (Variable) value;

            var index = variable.getIndex();
            var argResult = this.arguments != null ? this.arguments.get(index) : null;

            if (argResult != null) {
                //log
                return argResult;
            }

            var eval = variable.getEvalContext();
            var component = eval.getComponent();
            var method = eval.getMethod();

            var argumentVariants = eval.getArgumentVariants();

            var valueVariants = argumentVariants.stream().map(variant -> {
                var i = index - 1;
                if (i >= variant.size()) {
                    //logs
                    return stub(variable, component, method, resolver, this);
                } else {
                    return variant.get(i);
                }
            }).collect(toList());

            if (!valueVariants.isEmpty()) {
                var resolvedVariants = valueVariants.stream().map(variant -> {
                    if (variant instanceof Stub) {
                        return variant;
                    }
                    try {
                        return resolve(variant, resolver);
                    } catch (UnresolvedResultException e) {
                        return resolveOrThrow(value, resolver, e);
                    }
                }).collect(toList());
                result = collapse(resolvedVariants, variable.getFirstInstruction(), variable.getLastInstruction(),
                        constantPoolGen, component, method);
            } else {
                //todo may be need to resolve here by resolver
                result = variable;
            }
        } else if (value instanceof Delay) {
            try {
                var delay = (Delay) value;
                var delayComponent = delay.getComponent();
                var delayMethod = delay.getMethod();
                if (component.equals(delayComponent) && method.equals(delayMethod) && this.arguments != null) {
                    delay = delay.withEval(this);
                }
                result = delay.getDelayed(true, resolver);
            } catch (UnresolvedResultException e) {
                result = resolveOrThrow(value, resolver, e);
            }
        } else {
            result = value;
        }
        return result;
    }

//    private List<List<Result>> getArgumentVariants() {
//        return this.argumentVariants;
////        Component component = getComponent();
////        var method = getMethod();
////        return computeArgumentVariants(component, method, this.dependencyToDependentMap, this.callPointsCache, this.callCache);
//    }

    private static List<List<Result>> computeArgumentVariants(Component component, Method method, Map<Component, List<Component>> dependencyToDependentMap, Map<Component, List<CallPoint>> callPointsCache, Map<CallCacheKey, Result> callCache) {
        var componentType = component.getType();
        var methodName = method.getName();
        var argumentTypes = method.getArgumentTypes();
        var dependentOnThisComponent = getDependentOnThisComponent(dependencyToDependentMap, component);
        var methodCallPoints = getCallPoints(componentType, methodName, argumentTypes, dependentOnThisComponent, callPointsCache);
        var methodArgumentVariants = getEvalCallPointVariants(methodCallPoints, null, dependencyToDependentMap, callPointsCache, callCache);
        return methodArgumentVariants.values().stream()
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .flatMap(e -> e.getValue().stream()).map(EvalArguments::getArguments)
                .distinct().collect(toList());
    }

    private static Map<Component, Map<CallPoint, List<EvalArguments>>> getEvalCallPointVariants(
            Map<Component, Map<CallPoint, List<CallPoint>>> callPoints, Result parent, Map<Component, List<Component>> dependencyToDependentMap1, Map<Component, List<CallPoint>> callPointsCache, Map<CallCacheKey, Result> callCache1
    ) {
        return callPoints.entrySet().stream().map(e -> {
            var dependentComponent = e.getKey();
            var callPointListMap = e.getValue();
            var variants = callPointListMap.entrySet().stream().map(ee -> {
                var callPoint = ee.getKey();
                var matchedCallPoints = ee.getValue();
                var eval = newEval(dependentComponent, callPoint, dependencyToDependentMap1, callPointsCache, callCache1);
                return evalCallPointArgumentVariants(
                                dependentComponent, callPoint, matchedCallPoints, parent, eval);
                    }
            ).filter(Objects::nonNull).collect(toMap(Entry::getKey, Entry::getValue));
            return entry(dependentComponent, variants);
        }).collect(toMap(Entry::getKey, Entry::getValue));
    }

    private static Map<Component, Map<CallPoint, List<CallPoint>>> getCallPoints(
            Class<?> objectType, String methodName, Type[] argumentTypes, List<Component> dependentOnThisComponent, Map<Component, List<CallPoint>> callPointsCache) {
        return dependentOnThisComponent.stream().map(dependentComponent -> {
            var callPoints = getCallsHierarchy(dependentComponent, callPointsCache);
            var callersWithVariants = callPoints.stream().map(dependentMethod -> {
                var matchedCallPoints = getMatchedCallPoints(dependentMethod, methodName, argumentTypes, objectType);
                return entry(dependentMethod, matchedCallPoints);
            }).filter(e -> !e.getValue().isEmpty()).collect(toMap(Entry::getKey, Entry::getValue));
            return !callersWithVariants.isEmpty() ? entry(dependentComponent, callersWithVariants) : null;
        }).filter(Objects::nonNull).collect(toMap(Entry::getKey, Entry::getValue));
    }

    private static Entry<CallPoint, List<EvalArguments>> evalCallPointArgumentVariants(
            Component dependentComponent, CallPoint dependentMethod, List<CallPoint> matchedCallPoints, Result parent, EvalBytecode eval
    ) {
        var argVariants = matchedCallPoints.stream().map(callPoint -> {
            try {
                return evalArguments(dependentComponent, dependentMethod, callPoint, parent, eval);
            } catch (UnresolvedResultException e) {
                log("evalCallPointArgumentVariants", e);
                return List.<EvalArguments>of();
            }
        }).flatMap(Collection::stream).filter(Objects::nonNull).collect(toList());
        return !argVariants.isEmpty() ? entry(dependentMethod, argVariants) : null;
    }

    private static List<EvalArguments> evalArguments(Component dependentComponent, CallPoint dependentMethod,
                                               CallPoint calledMethod, Result parent, EvalBytecode eval) {
        var instructionHandle = calledMethod.getInstruction();
        var instruction = instructionHandle.getInstruction();

        var invokeInstruction = (InvokeInstruction) instruction;
        if (calledMethod.isInvokeDynamic()) {
            var invokeDynamicArgumentTypes = invokeInstruction.getArgumentTypes(eval.getConstantPoolGen());
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
                var index = parentVariable.getIndex() - 1;
                if (index >= consumedByInvokeDynamicArgumentsAmount) {
                    var stubbedArguments = new ArrayList<>(arguments.getArguments());

                    for (var i = 0; i < functionalInterfaceArgumentsAmount; i++) {
                        stubbedArguments.add(stub(parentVariable, dependentComponent, dependentMethod.getMethod(), null, eval));
                    }
                    arguments = new EvalArguments(stubbedArguments, arguments.getLastArgInstruction());
                }
            }

            return List.of(arguments);
        } else {
            var argumentTypes = invokeInstruction.getArgumentTypes(eval.getConstantPoolGen());
            var arguments = eval.evalArguments(instructionHandle, argumentTypes.length, parent);
            return List.of(arguments);
        }
    }

    private static EvalBytecode newEval(Component dependentComponent, CallPoint dependentMethod, Map<Component, List<Component>> dependencyToDependentMap1, Map<Component, List<CallPoint>> callPointsCache1, Map<CallCacheKey, Result> callCache1) {
        return new EvalBytecode(dependentComponent, dependencyToDependentMap1, new ConstantPoolGen(dependentMethod.getMethod().getConstantPool()),
                JmsOperationsUtils.getBootstrapMethods(dependentMethod.getJavaClass()), dependentMethod.getMethod(),
                callPointsCache1, callCache1);
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
        return new EvalArguments(asList(values), current);
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
        @FieldDefaults(makeFinal = true, level = PRIVATE)
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

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class ParameterValue {
        Result parameter;
        int index;
        Object value;
        UnresolvedResultException exception;
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class ParameterVariants {
        Result parameter;
        int index;
        List<Object> values;
        UnresolvedResultException exception;
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class InvokeObject {
        InstructionHandle firstInstruction;
        InstructionHandle lastInstruction;
        Result object;
    }

    @Data
    @RequiredArgsConstructor
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    static class ContextHierarchy {
        CallContext current;
        Set<ContextHierarchy> parents;
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    static class CallContext {
        Component component;
        Method method;
        @EqualsAndHashCode.Exclude
        Result result;

        public static CallContext newCallContext(Component component, Method method, Result result) {
            return new CallContext(component, method, result);
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

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class CallCacheKey {
        Delay call;
        List<List<ParameterValue>> parametersVariants;
        Instruction lastInstruction;
    }
}
