package io.github.m4gshm.connections.eval.bytecode;

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

import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.eval.bytecode.ArithmeticUtils.computeArithmetic;
import static io.github.m4gshm.connections.eval.bytecode.Eval.CallContext.newCallContext;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeException.newInvalidEvalException;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeException.newUnsupportedEvalException;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.*;
import static io.github.m4gshm.connections.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodHandlerAndArguments;
import static io.github.m4gshm.connections.eval.bytecode.LocalVariableUtils.*;
import static io.github.m4gshm.connections.eval.bytecode.NotInvokedException.Reason.*;
import static io.github.m4gshm.connections.eval.result.Result.*;
import static io.github.m4gshm.connections.eval.result.Variable.VarType.MethodArg;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;
import static java.util.Collections.singletonList;
import static java.util.Map.entry;
import static java.util.stream.Collectors.*;
import static java.util.stream.IntStream.range;
import static java.util.stream.Stream.*;
import static lombok.AccessLevel.PRIVATE;
import static lombok.AccessLevel.PROTECTED;
import static org.apache.bcel.Const.*;

@Slf4j
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@FieldDefaults(makeFinal = true, level = PRIVATE)
@AllArgsConstructor(access = PROTECTED)
public class Eval {
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

    public Eval(@NonNull Component component,
                @NonNull ConstantPoolGen constantPoolGen,
                BootstrapMethods bootstrapMethods,
                @NonNull Method method,
                @NonNull List<List<Result>> argumentVariants) {
        this.component = component;
        this.constantPoolGen = constantPoolGen;
        this.bootstrapMethods = bootstrapMethods;
        this.method = method;
        this.methodCode = method.getCode();
        this.jumpTo = instructionHandleStream(method.getCode()).map(instructionHandle -> {
            var instruction = instructionHandle.getInstruction();
            if (instruction instanceof BranchInstruction) {
                return entry(((BranchInstruction) instruction).getTarget().getPosition(), instructionHandle);
            } else {
                return null;
            }
        }).filter(Objects::nonNull).collect(groupingBy(Entry::getKey, mapping(Entry::getValue, toList())));
        this.arguments = null;
        this.argumentVariants = argumentVariants;
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
            var parentContexts = relations.stream().map(relation -> {
                return getCallContext(relation);
            }).filter(c -> c.current != null).flatMap(c -> {
                return callContext.equals(c.current) ? c.parents.stream() : of(c);
            }).collect(toMap(c -> c.current, c -> c.parents, (l, r) -> {
                return concat(l.stream(), r.stream()).filter(h -> h.current != null).collect(toSet());
            }));
            var parents = parentContexts.entrySet().stream().map(e -> new ContextHierarchy(e.getKey(), e.getValue()))
                    .collect(toSet());
            return new ContextHierarchy(callContext, parents);
        }
        return new ContextHierarchy(callContext, Set.of());
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

    private static List<Object> normalizeClassOfObjects(Collection<Object> values, Class<?> expectedType) {
        return values == null ? null : values.stream().map(object -> normalizeClassOfObjects(object, expectedType))
                .collect(toList());
    }

    private static Object normalizeClassOfObjects(Object value, Class<?> expectedType) {
        if (value != null && !expectedType.isAssignableFrom(value.getClass())) {
            if (value instanceof Integer) {
                int integer = (Integer) value;
                if (expectedType == boolean.class || expectedType == Boolean.class) {
                    value = integer != 0;
                } else if (expectedType == byte.class || expectedType == Byte.class) {
                    value = (byte) integer;
                } else if (expectedType == char.class || expectedType == Character.class) {
                    value = (char) integer;
                } else if (expectedType == short.class || expectedType == Short.class) {
                    value = (short) integer;
                }
            }
        }
        return value;
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

    private static Result call(Delay invoke, InstructionHandle lastInstruction, Resolver resolver,
                               List<List<ParameterValue>> parametersVariants, BiFunction<List<ParameterValue>, InstructionHandle, Result> call,
                               ConstantPoolGen constantPoolGen, Component component, Method method) {

        var values = new ArrayList<Result>();
        var unresolvedVars = new ArrayList<UnresolvedVariableException>();
        var errors = new ArrayList<EvalBytecodeException>();

        for (var parameterValues : parametersVariants) {
            try {
                var apply = call.apply(parameterValues, lastInstruction);
                values.add(apply);
            } catch (UnresolvedVariableException e) {
                unresolvedVars.add(e);
            } catch (EvalBytecodeException e) {
                errors.add(e);
            }
        }

        if (!values.isEmpty()) {
            return collapse(values, invoke.getFirstInstruction(), lastInstruction, constantPoolGen, component, method);
        } else if (!errors.isEmpty()) {
            var e = errors.get(0);
            log.trace("call error of {}", invoke, e);
            return resolver.resolve(invoke, e);
        } else {
            //log
            throw unresolvedVars.isEmpty()
                    ? new NotInvokedException(noCalls, invoke)
                    : new NotInvokedException(unresolvedVariables, unresolvedVars, invoke);

        }

//        if (values.isEmpty()) {
//            throw new NotInvokedException(invoke);
//        } else {
//            return collapse(values, invoke.getFirstInstruction(), lastInstruction, constantPoolGen, component, method);
//        }
//        } catch (NotInvokedException e) {
//            //log
//            throw e;
//        } catch (EvalBytecodeException e) {
//            //log
//            if (resolver == null) {
//                throw e;
//            }
//            return resolver.resolve(invoke, e);
//        }
    }

    private static List<List<List<Result>>> getFullDistinctCallContexts(Map<CallContext, List<Result>[]> callContexts) {
        return callContexts.values().stream()
                .filter(args -> Arrays.stream(args).noneMatch(Objects::isNull))
                .map(Arrays::asList).distinct().collect(toList());
    }

    public static Result callInvokeSpecial(DelayInvoke invoke, Class<?>[] argumentClasses, Eval eval,
                                           boolean throwNoCall, Resolver resolver,
                                           BiFunction<List<ParameterValue>, InstructionHandle, Result> call, Map<CallCacheKey, Result> callCache) {
        return eval.callWithParameterVariants(invoke, argumentClasses, throwNoCall, resolver, call, callCache);
    }

    public static Result callInvokeStatic(DelayInvoke invoke, Class<?>[] argumentClasses, Eval eval,
                                          boolean throwNoCall, Resolver resolver,
                                          BiFunction<List<ParameterValue>, InstructionHandle, Result> call, Map<CallCacheKey, Result> callCache) {
        return eval.callWithParameterVariants(invoke, argumentClasses, throwNoCall, resolver, call, callCache);
    }

    public static Result callInvokeVirtual(InstructionHandle instructionHandle, DelayInvoke invoke,
                                           Class<?>[] argumentClasses, Eval eval, boolean throwNoCall, Resolver resolver,
                                           BiFunction<List<ParameterValue>, InstructionHandle, Result> call, Map<CallCacheKey, Result> callCache) {
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var objectClass = toClass(instruction.getClassName(eval.getConstantPoolGen()));
        var parameterClasses = concat(ofNullable(objectClass), of(argumentClasses)).toArray(Class[]::new);
        return eval.callWithParameterVariants(invoke, parameterClasses, throwNoCall, resolver, call, callCache);
    }

    public static Result callInvokeDynamic(DelayInvoke invoke, Class<?>[] argumentClasses, Eval eval,
                                           boolean throwNoCall, Resolver resolver,
                                           BiFunction<List<ParameterValue>, InstructionHandle, Result> call, Map<CallCacheKey, Result> callCache) {
        return eval.callWithParameterVariants(invoke, argumentClasses, throwNoCall, resolver, call, callCache);
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

    static List<EvalArguments> evalArguments(CallPoint calledMethod, Eval eval, Map<CallCacheKey, Result> callCache) {
        var instructionHandle = calledMethod.getInstruction();
        var instruction = instructionHandle.getInstruction();

        var invokeInstruction = (InvokeInstruction) instruction;
        if (calledMethod.isInvokeDynamic()) {
            var invokeDynamicArgumentTypes = invokeInstruction.getArgumentTypes(eval.getConstantPoolGen());
            var referenceKind = calledMethod.getReferenceKind();
            var removeCallObjectArg = referenceKind == REF_invokeSpecial
                    || referenceKind == REF_invokeVirtual
                    || referenceKind == REF_invokeInterface;
            var arguments = eval.evalArguments(instructionHandle, invokeDynamicArgumentTypes.length, null, callCache);
            if (removeCallObjectArg) {
                var withoutCallObject = new ArrayList<>(arguments.getArguments());
                withoutCallObject.remove(0);
                arguments = new EvalArguments(withoutCallObject, arguments.getLastArgInstruction());
            }
            return List.of(arguments);
        } else {
            var argumentTypes = invokeInstruction.getArgumentTypes(eval.getConstantPoolGen());
            var arguments = eval.evalArguments(instructionHandle, argumentTypes.length, null, callCache);
            return List.of(arguments);
        }
    }

    static Map<CallContext, List<Result>[]> getCallContexts(List<Result> parameters,
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

    private static boolean isSameLevel(Delay unresolved, Component contextComponent) {
        var unresolvedComponent = unresolved.getComponent();
        var components = concat(of(unresolvedComponent), unresolved.getRelations().stream()
                .map(ContextAware::getComponent)).collect(toSet());
        return components.size() == 1 && components.contains(contextComponent);
    }

    private static boolean isSameLevel(Variable unresolved, Component contextComponent) {
        return contextComponent.equals(unresolved.getComponent());
    }

    private Map<Integer, List<Result>> resolveInvokeParameters(DelayInvoke invoke, List<Result> parameters, Resolver resolver) {
        var resolvedParameters = new HashMap<Integer, List<Result>>();
        for (int i = 0; i < parameters.size(); i++) {
            try {
                resolvedParameters.put(i, resolveExpand(parameters.get(i), resolver));
            } catch (NotInvokedException e) {
                //log
                return null;
            }
        }
        return resolvedParameters;
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

    public Result eval(InstructionHandle instructionHandle, Map<CallCacheKey, Result> callCache) {
        return eval(instructionHandle, null, callCache);
    }

    public Result eval(InstructionHandle instructionHandle, Result parent, Map<CallCacheKey, Result> callCache) {
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

            var storeResults = findStoreInstructionResults(instructionHandle, localVariables, loadIndex, parent, callCache);
            if (!storeResults.isEmpty()) {
                var description = instructionText + " from stored invocation";
                var storeInstructions = storeResults.stream()
                        .map(storeResult -> eval(storeResult.getFirstInstruction(), parent, callCache))
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
                return eval(getPrev(instructionHandle), parent, callCache);
            }
        } else if (instruction instanceof GETSTATIC) {
            var getStatic = (GETSTATIC) instruction;
            var fieldName = getStatic.getFieldName(constantPoolGen);
            var loadClassType = getStatic.getLoadClassType(constantPoolGen);
            var loadClass = getClassByName(loadClassType.getClassName());
            return getFieldValue(null, loadClass, fieldName, instructionHandle, instructionHandle, parent, component, method);
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;
            var evalFieldOwnedObject = eval(getPrev(instructionHandle), parent, callCache);
            var fieldName = getField.getFieldName(constantPoolGen);
            var lastInstruction = evalFieldOwnedObject.getLastInstruction();
            return getFieldValue(evalFieldOwnedObject, fieldName, instructionHandle, lastInstruction,
                    constantPoolGen, this, parent);
        } else if (instruction instanceof CHECKCAST) {
            return eval(getPrev(instructionHandle), parent, callCache);
        } else if (instruction instanceof InvokeInstruction) {
            return evalInvoke(instructionHandle, (InvokeInstruction) instruction, parent, callCache);
        } else if (instruction instanceof ANEWARRAY) {
            var anewarray = (ANEWARRAY) instruction;

            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var eval = thisDelay.getEval();
                var loadClassType = anewarray.getLoadClassType(eval.getConstantPoolGen());
                var arrayElementType = getClassByName(loadClassType.getClassName());
                var size = eval(getPrev(instructionHandle), parent, callCache);
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
                var element = eval(getPrev(instructionHandle), thisDelay, callCache);
                var index = eval(getPrev(element.getLastInstruction()), thisDelay, callCache);
                var array = eval(getPrev(index.getLastInstruction()), thisDelay, callCache);
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
                var element = eval(getPrev(instructionHandle), thisDelay, callCache);
                var index = eval(getPrev(element.getLastInstruction()), thisDelay, callCache);
                var array = eval(getPrev(index.getLastInstruction()), thisDelay, callCache);
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
                var arrayRef = eval(getPrev(instructionHandle), thisDelay, callCache);
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
                return instantiateObject(instructionHandle, type, new Class[0], new Object[0], thisDelay, getComponent(), getMethod());
            });
        } else if (instruction instanceof DUP) {
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var prev = instructionHandle.getPrev();
                return eval(prev, thisDelay, callCache);
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
            return eval(prev, parent, callCache);
        } else if (instruction instanceof POP2) {
//            return eval(getPrev(instructionHandle), resolver);
        } else if (instruction instanceof ACONST_NULL) {
            return constant(null, instructionHandle, instructionHandle, component, method, parent);
        } else if (instruction instanceof IfInstruction) {
            var args = new Result[consumeStack];
            var current = instructionHandle;
            for (var i = consumeStack - 1; i >= 0; --i) {
                current = getPrev(instructionHandle);
                args[i] = eval(current, parent, callCache);
            }
            var lastInstruction = args.length > 0 ? args[0].getLastInstruction() : instructionHandle;
            //now only positive scenario
            //todo need evaluate negative branch
            return eval(getPrev(lastInstruction), parent, callCache);
        } else if (instruction instanceof ConversionInstruction) {
            //I2L,
            var conv = (ConversionInstruction) instruction;
            var convertTo = conv.getType(constantPoolGen);
            return delay(instructionText, instructionHandle, this, parent, (thisDelay, needResolve, resolver) -> {
                var convertedValueInstruction = getPrev(instructionHandle);
                var convertedValueResult = eval(convertedValueInstruction, thisDelay, callCache);
                var lastInstruction = convertedValueResult.getLastInstruction();
                if (needResolve) {
                    var values = convertedValueResult.getValue(resolver);
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
                var first = eval(getPrev(instructionHandle), thisDelay, callCache);
                var second = consumeStack == 2 ? eval(getPrev(first.getLastInstruction()), callCache) : null;
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

    public List<Result> findStoreInstructionResults(InstructionHandle instructionHandle, List<LocalVariable> localVariables,
                                                    int index, Result parent, Map<CallCacheKey, Result> callCache) {
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
                    var storedInLocal = eval(prev, parent, callCache);
                    aStoreResults.add(storedInLocal);
                    prev = getPrev(prev);
                }
            }
            prev = getPrev(prev);
        }
        return aStoreResults;
    }

    protected Result evalInvoke(InstructionHandle instructionHandle, InvokeInstruction instruction, Result parent,
                                Map<CallCacheKey, Result> callCache) {
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
            var arguments = evalArguments(instructionHandle, argumentsAmount, null, callCache);
            var invokeObject = evalInvokeObject(instruction, arguments, null, callCache);
            return delayInvoke(instructionHandle, this, parent, invokeObject, arguments, (thisDelay, needResolve, resolver) -> {
                var eval = thisDelay.getEval();
                return needResolve ? callInvokeVirtual(instructionHandle, thisDelay, argumentClasses, eval, true, resolver,
                        (parameters, lastInstruction) -> {
                            var paramValues = getValues(parameters);
                            var object = paramValues[0];
                            var argValues = copyOfRange(paramValues, 1, paramValues.length);
                            var objectClass = toClass(invokeObjectClassName);
                            var result = callMethod(object, objectClass, methodName, argumentClasses,
                                    argValues, instructionHandle, lastInstruction,
                                    constantPoolGen, thisDelay, parameters);
                            return result;
                        }, callCache) : thisDelay;
            });
        } else if (instruction instanceof INVOKEDYNAMIC) {
            var arguments = evalArguments(instructionHandle, argumentsAmount, null, callCache);
            return delayInvoke(instructionHandle, this, parent, null, arguments, (thisDelay, needResolve, resolver) -> {
                var eval = thisDelay.getEval();
                return needResolve ? callInvokeDynamic(thisDelay, argumentClasses, eval, true, resolver, (parameters, lastInstruction) -> {
                    var bootstrapMethodAndArguments = getBootstrapMethodHandlerAndArguments(
                            (INVOKEDYNAMIC) instruction, bootstrapMethods, constantPoolGen);
                    return callBootstrapMethod(getValues(parameters), instructionHandle, lastInstruction,
                            eval, bootstrapMethodAndArguments, parameters);
                }, callCache) : thisDelay;
            });
        } else if (instruction instanceof INVOKESTATIC) {
            var invokeObjectClassName = instruction.getClassName(constantPoolGen);
            var arguments = evalArguments(instructionHandle, argumentsAmount, null, callCache);
            return delayInvoke(instructionHandle, this, parent, null, arguments, (thisDelay, needResolve, resolver) -> {
                var eval = thisDelay.getEval();
                return needResolve ? callInvokeStatic(thisDelay, argumentClasses, eval, true, resolver, (parameters, lastInstruction) -> {
                    var objectClass = toClass(invokeObjectClassName);
                    var result = callMethod(null, objectClass, methodName, argumentClasses, getValues(parameters),
                            instructionHandle, lastInstruction, constantPoolGen, thisDelay, parameters);
                    return result;
                }, callCache) : thisDelay;
            });
        } else if (instruction instanceof INVOKESPECIAL) {
            var invokeObjectClassName = instruction.getClassName(constantPoolGen);
            var arguments = evalArguments(instructionHandle, argumentsAmount, null, callCache);
            var invokeObject = evalInvokeObject(instruction, arguments, null, callCache);
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
                }, callCache) : thisDelay;
            });
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen.getConstantPool());
    }

    private Result callWithParameterVariants(DelayInvoke invoke, Class<?>[] parameterClasses, boolean throwNoCall,
                                             Resolver resolver, BiFunction<List<ParameterValue>, InstructionHandle, Result> call,
                                             Map<CallCacheKey, Result> callCache) throws NotInvokedException {
        var parameters = toParameters(invoke.getObject(), invoke.getArguments());
        var parameterVariants = resolveInvokeParameters(invoke, parameters, resolver, true);
        if (parameterVariants.isEmpty()) {
            throw new NotInvokedException(noParameterVariants, invoke, parameters);
        }
        var lastInstruction = invoke.getLastInstruction();
        var results = callWithParameterVariants(invoke, parameterClasses, resolver, call, callCache,
                parameterVariants, lastInstruction);
        var unresolved = results.get(false).stream().map(p -> p.exception).distinct().collect(toList());
        var resolved = results.get(true).stream().map(p -> p.result).collect(toList());
        if (resolved.isEmpty()) {
            if (throwNoCall) {
                throw unresolved.isEmpty()
                        ? new NotInvokedException(noCalls, invoke)
                        : new NotInvokedException(unresolvedVariables, unresolved, invoke);
            } else {
                return resolveAndInvoke(invoke, parameters, parameterClasses, lastInstruction, resolver, call, callCache);
            }
        }
        return collapse(resolved, invoke.getFirstInstruction(), lastInstruction, getConstantPoolGen(), getComponent(), getMethod());
    }

    private Map<Boolean, List<InvokedResult>> callWithParameterVariants(DelayInvoke invoke, Class<?>[] parameterClasses, Resolver resolver,
                                                                        BiFunction<List<ParameterValue>, InstructionHandle, Result> call,
                                                                        Map<CallCacheKey, Result> callCache, List<List<Result>> parameterVariants,
                                                                        InstructionHandle lastInstruction) {
        return parameterVariants.stream().map(parameterVariant -> {
            try {
                return new InvokedResult(resolveAndInvoke(invoke, parameterVariant, parameterClasses, lastInstruction, resolver, call, callCache), null);
            } catch (UnresolvedVariableException e) {
                //log
                return new InvokedResult(null, e);
            }
        }).collect(partitioningBy(p -> p.exception == null));
    }

    private Result resolveAndInvoke(Delay current, List<Result> parameters, Class<?>[] parameterClasses,
                                    InstructionHandle lastInstruction, Resolver resolver,
                                    BiFunction<List<ParameterValue>, InstructionHandle, Result> call,
                                    Map<CallCacheKey, Result> callCache) {

        var callParameters = resolveCallParameters(parameters, parameterClasses, resolver);

        var key = new CallCacheKey(current, callParameters, lastInstruction.getInstruction());
        if (callCache != null) {
            var cached = callCache.get(key);
            if (cached != null) {
                log.trace("get cached call result, call '{}', result '{}'", key, cached);
                return cached;
            }
        }

        var callResult = call(current, lastInstruction, resolver, callParameters, call, getConstantPoolGen(), getComponent(), getMethod());
        if (callCache != null) {
            log.trace("no cached call result, call '{}', result '{}'", key, callResult);
            callCache.put(key, callResult);
        }
        return callResult;
    }

    private List<List<ParameterValue>> resolveCallParameters(List<Result> parameters, Class<?>[] parameterClasses, Resolver resolver) {
        @Data
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class ParameterVariants {
            Result parameter;
            int index;
            List<Object> values;
            UnresolvedResultException exception;
        }

        var size = parameters.size();
        var variants = new ParameterVariants[size];
        for (var i = 0; i < size; i++) {
            var parameter = parameters.get(i);
            ParameterVariants parameterVariants;
            try {
                var value = singletonList(parameter.getValue());
                parameterVariants = new ParameterVariants(parameter, i, value, null);
            } catch (UnresolvedVariableException e) {
                var unresolved = e.getResult();
                var contextComponent = getComponent();
                var sameLevel = isSameLevel(unresolved, contextComponent);
                if (!sameLevel && resolver != null) {
                    //log
                    var resolved = resolver.resolve(parameter, e);
                    if (resolved.isResolved()) {
                        var resolvedVariants = resolved.getValue(resolver);
                        var normalizedClassVariants = normalizeClassOfObjects(resolvedVariants, parameterClasses[i]);
                        parameterVariants = new ParameterVariants(parameter, i, normalizedClassVariants, null);
                    } else {
                        //log
                        parameterVariants = new ParameterVariants(parameter, i, null, e);
                    }
                } else {
                    throw e;
                }
            }
            variants[i] = parameterVariants;
        }

        var parameterVariants = asList(variants);
        int dimensions = parameterVariants.stream().map(p -> p.values).filter(Objects::nonNull)
                .map(List::size).reduce(1, (l, r) -> l * r);

        return range(1, dimensions + 1).mapToObj(d -> parameterVariants.stream().map(parameterVariant -> {
            var exception = parameterVariant.exception;
            var parameter = parameterVariant.parameter;
            var index = parameterVariant.index;
            if (exception != null) {
                return new ParameterValue(parameter, index, null, exception);
            } else {
                var variantValues = parameterVariant.values;
                var size1 = variantValues.size();
                int variantIndex = (d <= size1 ? d : size1 % d) - 1;
                var value = variantValues.get(variantIndex);
                return new ParameterValue(parameter, index, value, null);
            }
        }).collect(toList())).collect(toList());
    }

    public InvokeObject evalInvokeObject(InvokeInstruction invokeInstruction, EvalArguments evalArguments, Result parent,
                                         Map<CallCacheKey, Result> callCache) {
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
            objectCallResult = eval(prev, parent, callCache);
            firstInstruction = objectCallResult.getFirstInstruction();
            lastInstruction = objectCallResult.getLastInstruction();
        } else {
            objectCallResult = null;
            firstInstruction = lastArgInstruction;
            lastInstruction = lastArgInstruction;
        }
        return new InvokeObject(firstInstruction, lastInstruction, objectCallResult);
    }

    public List<List<Result>> resolveInvokeParameters(DelayInvoke invoke, List<Result> parameters, Resolver resolver,
                                                      boolean resolveUncalledVariants) {
        if (parameters.isEmpty()) {
            return List.of(parameters);
        }

        if (!(this.arguments == null || this.arguments.isEmpty())) {
            var parameterVariants = parameters.stream().map(parameter -> resolveExpand(parameter, resolver)).collect(toList());
            int dimensions = getDimensions(parameterVariants);
            return flatResolvedVariants(dimensions, parameterVariants, parameters);
        } else {
            var resolvedAll = resolveParameters(invoke, parameters, resolver, resolveUncalledVariants);

            var resolvedParamVariants = new ArrayList<List<Result>>();
            for (var resolvedVariantMap : resolvedAll) {
                var parameterVariants = new ArrayList<>(resolvedVariantMap.values());
                int dimensions = getDimensions(parameterVariants);
                if (dimensions <= 3) {
                    resolvedParamVariants.addAll(flatResolvedVariants(dimensions, parameterVariants, parameters));
                } else {
                    //todo need to analyze the branch
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

    private List<Map<Integer, List<Result>>> resolveParameters(DelayInvoke invoke, List<Result> parameters,
                                                               Resolver resolver, boolean resolveUncalledVariants) {
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
                    var resolvedVars = evalWithArguments.resolveInvokeParameters(invoke, parameters, resolver);
                    if (resolvedVars != null) {
                        resolvedAll.add(resolvedVars);
                    }
                }
            }
        }
        return resolvedAll;
    }

    private Map<Integer, Result> getEvalContextArgs(List<Result> arguments, boolean resolveNoCall, Resolver resolver) {
        var evalContextArgs = new HashMap<Integer, Result>();
        for (int i = 0; i < arguments.size(); i++) {
            var value = arguments.get(i);
            Result resolved;
            try {
                resolved = resolve(value, resolver);
            } catch (NotInvokedException e) {
                var unresolved = e.getResult();
                var sameLevel = isSameLevel(unresolved, this.getComponent());
                //log
                if (resolveNoCall && !sameLevel && resolver != null) {
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

    protected Result callMethod(Object object, Class<?> type, String methodName, Class<?>[] argTypes, Object[] args,
                                InstructionHandle invokeInstruction, InstructionHandle lastInstruction,
                                ConstantPoolGen constantPoolGen, Delay invoke, List<ParameterValue> parameters) {
        var msg = "callMethod";
        if (object != null && !type.isAssignableFrom(object.getClass())) {
            log.debug("unexpected callable object type {}, expected {}, object '{}', method {}",
                    object.getClass().getName(), type.getName(), object, methodName);
        }
        var declaredMethod = getDeclaredMethod(type, methodName, argTypes);
        if (declaredMethod == null) {
            log.info("{}, method not found '{}.{}', instruction {}", msg, type.getName(), methodName,
                    EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
            return notFound(methodName, invokeInstruction, invoke);
        } else if (!declaredMethod.trySetAccessible()) {
            log.warn("{}, method is not accessible, method '{}.{}', instruction {}", msg, type.getName(), methodName,
                    EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
            return notAccessible(declaredMethod, invokeInstruction, invoke);
        }
        Object result;
        try {
            result = declaredMethod.invoke(object, args);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            //log

            throw new IllegalInvokeException(e, new MethodCallInfo(declaredMethod, object, args), invokeInstruction, invoke);
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
                    return stub(variable, component, method, resolver);
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
                    } catch (UnresolvedVariableException e) {
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
            } catch (UnresolvedVariableException e) {
                result = resolveOrThrow(value, resolver, e);
            }
        } else {
            result = value;
        }
        return result;
    }

    public EvalArguments evalArguments(InstructionHandle instructionHandle, int argumentsAmount, Result parent,
                                       Map<CallCacheKey, Result> callCache) {
        var values = new Result[argumentsAmount];
        var current = instructionHandle;
        for (int i = argumentsAmount; i > 0; i--) {
            var prev = getPrev(current);
            var eval = eval(prev, parent, callCache);
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
    public static class InvokedResult {
        Result result;
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
}
