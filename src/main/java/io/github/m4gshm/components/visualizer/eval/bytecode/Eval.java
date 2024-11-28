package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.result.*;
import io.github.m4gshm.components.visualizer.model.CallPoint;
import io.github.m4gshm.components.visualizer.model.Component;
import io.github.m4gshm.components.visualizer.model.Component.ComponentKey;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;

import java.lang.Deprecated;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.components.visualizer.Utils.toLinkedHashSet;
import static io.github.m4gshm.components.visualizer.eval.bytecode.ArithmeticUtils.computeArithmetic;
import static io.github.m4gshm.components.visualizer.eval.bytecode.Eval.CallContext.newCallContext;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalException.newInvalidEvalException;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalException.newUnsupportedEvalException;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeBranch.newTree;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodHandlerAndArguments;
import static io.github.m4gshm.components.visualizer.eval.bytecode.LocalVariableUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.NotInvokedException.Reason.*;
import static io.github.m4gshm.components.visualizer.eval.result.Result.*;
import static io.github.m4gshm.components.visualizer.eval.result.Variable.VarType.MethodArg;
import static io.github.m4gshm.components.visualizer.model.Component.ComponentKey.newComponentKey;
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
import static org.springframework.aop.support.AopUtils.getTargetClass;
import static org.springframework.util.Assert.notEmpty;
import static org.springframework.util.Assert.state;

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
    @Getter
    @EqualsAndHashCode.Include
    JavaClass javaClass;
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
    @Getter
    InvokeBranch tree;

    CallCache callCache;

    public Eval(Component component, @NonNull JavaClass javaClass, @NonNull Method method,
                BootstrapMethods bootstrapMethods, @NonNull List<List<Result>> argumentVariants,
                CallCache callCache) {
        this.component = component;
        this.javaClass = javaClass;
        this.constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
        this.bootstrapMethods = bootstrapMethods;
        this.method = method;
        this.callCache = callCache;
        var methodCode = method.getCode();
        this.methodCode = methodCode;
        this.tree = newTree(component.getType(), method);
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
        var contextAware = (ContextAware) variant;
        var callContext = newCallContext(contextAware.getComponentKey(), contextAware.getMethod(), variant);
        if (variant instanceof RelationsAware) {
            var relationsAware = (RelationsAware) variant;
            var relations = relationsAware.getRelations();
            var parentContexts = relations.stream().map(relation -> {
                return getCallContext(relation);
            }).filter(c -> c.current != null).flatMap(c -> {
                return callContext.equals(c.current) ? c.parents.stream() : of(c);
            }).collect(toMap(c -> c.current, c -> c.parents, (l, r) -> {
                return concat(l.stream(), r.stream()).filter(h -> h.current != null).collect(toLinkedHashSet());
            }, LinkedHashMap::new));
            var parents = parentContexts.entrySet().stream().map(e -> new ContextHierarchy(e.getKey(), e.getValue()))
                    .collect(toLinkedHashSet());
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


    private static List<List<Result>> flatResolvedVariants(
            int dimensions, List<List<Result>> parameterVariants, List<Result> parameters) {
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


    private static List<Map<Integer, List<Result>>> flatResolvedVariants(int dimensions, Map<Integer, List<Result>> paramIndexToVariantsMap) {
        var resolvedVariants = new ArrayList<Map<Integer, List<Result>>>();
        for (var d = 1; d <= dimensions; d++) {
            var paramIndexToVariantMap = new HashMap<Integer, List<Result>>();
            for (var index : paramIndexToVariantsMap.keySet()) {
                var variantsOfOneArgument = paramIndexToVariantsMap.get(index);
                var i = d <= variantsOfOneArgument.size() ? d - 1 : variantsOfOneArgument.size() % d - 1;
                Result result = variantsOfOneArgument.get(i);
                paramIndexToVariantMap.put(index, List.of(result));
            }
            resolvedVariants.add(paramIndexToVariantMap);

        }
        return resolvedVariants;
    }

    public static List<Result> expand(Result result) {
        return result instanceof Multiple ? ((Multiple) result).getResults()
                : result instanceof Delay && result.isResolved()
                ? expand(((Delay) result).getResult())
                : List.of(result);
    }

    public static Result collapse(Collection<? extends Result> values, InstructionHandle instructionHandle,
                                  InstructionHandle lastInstruction, ConstantPoolGen constantPool, Eval eval) {
        return collapse(values, instructionHandle, lastInstruction, constantPool.getConstantPool(), eval);
    }

    public static Result collapse(Collection<? extends Result> values, InstructionHandle instructionHandle,
                                  InstructionHandle lastInstruction, ConstantPool constantPool, Eval eval) {
        if (values.isEmpty()) {
            throw newInvalidEvalException("empty results", instructionHandle.getInstruction(), constantPool);
        }
        if (values.size() > 1) {
            return multiple(new ArrayList<>(values), instructionHandle, lastInstruction, eval);
        }
        var first = values.iterator().next();
        return first;
    }

    private static Result resolveOrThrow(Result result, Resolver resolver, UnresolvedResultException e) {
        if (resolver != null) {
            try {
                return resolver.resolve(result, e);
            } catch (NotInvokedException ee) {
                //todo bad case
                //log.error
                throw ee;
            } catch (Exception ee) {
                throw ee;
            }
        } else {
            throw e;
        }
    }

    private static Result call(Delay invoke, InstructionHandle lastInstruction, Resolver resolver,
                               List<List<ParameterValue>> parametersVariants, BiFunction<List<ParameterValue>, InstructionHandle, Result> call,
                               ConstantPoolGen constantPoolGen, Eval eval) throws NotInvokedException {

        var values = new ArrayList<Result>();
        var unresolvedVars = new ArrayList<UnresolvedVariableException>();
        var errors = new ArrayList<EvalException>();

        for (var parameterValues : parametersVariants) {
            try {
                var apply = call.apply(parameterValues, lastInstruction);
                values.add(apply);
            } catch (UnresolvedVariableException e) {
                unresolvedVars.add(e);
            } catch (EvalException e) {
                errors.add(e);
            }
        }

        if (!values.isEmpty()) {
            return collapse(values, invoke.getFirstInstruction(), lastInstruction, constantPoolGen, eval);
        } else if (!errors.isEmpty() && resolver != null) {
            var e = errors.get(0);
            log.trace("call error of {}", invoke, e);
            return resolver.resolve(invoke, e);
        } else {
            //log
            if (unresolvedVars.isEmpty()) {
                throw new NotInvokedException(noCalls, invoke);
            } else {
                throw new NotInvokedException(unresolvedVariables, unresolvedVars, invoke);
            }
        }
    }

    private static List<List<List<Result>>> getFullDistinctCallContexts(Map<CallContext, List<Result>[]> callContexts) {
        return callContexts.values().stream()
                .filter(args -> Arrays.stream(args).noneMatch(Objects::isNull))
                .map(Arrays::asList).distinct().collect(toList());
    }

    public static Result callInvokeSpecial(DelayInvoke invoke, Class<?>[] argumentClasses, Eval eval,
                                           boolean throwNoCall, Resolver resolver,
                                           BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        return eval.callWithParameterVariants(invoke, argumentClasses, throwNoCall, resolver, call);
    }

    public static Result callInvokeStatic(DelayInvoke invoke, Class<?>[] argumentClasses, Eval eval,
                                          boolean throwNoCall, Resolver resolver,
                                          BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        return eval.callWithParameterVariants(invoke, argumentClasses, throwNoCall, resolver, call);
    }

    public static Result callInvokeVirtual(DelayInvoke invoke, Class<?>[] argumentClasses, Eval eval,
                                           boolean throwNoCall, Resolver resolver,
                                           BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        var instruction = (InvokeInstruction) invoke.getFirstInstruction().getInstruction();
        var objectClass = toClass(instruction.getClassName(eval.getConstantPoolGen()));
        var parameterClasses = concat(ofNullable(objectClass), of(argumentClasses)).toArray(Class[]::new);
        return eval.callWithParameterVariants(invoke, parameterClasses, throwNoCall, resolver, call);
    }

    public static Result callInvokeDynamic(DelayInvoke invoke, Class<?>[] argumentClasses, Eval eval,
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

    private static List<Map<Integer, Result>> getEvalContextArgsVariants(int dimensions, Map<Integer, Result> evalContextArgs) {
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

    static List<EvalArguments> evalArguments(CallPoint calledMethod, Eval eval) {
        var instructionHandle = calledMethod.getInstruction();
        var instruction = instructionHandle.getInstruction();

        var invokeInstruction = (InvokeInstruction) instruction;
        if (calledMethod.isInvokeDynamic()) {
            var invokeDynamicArgumentTypes = invokeInstruction.getArgumentTypes(eval.getConstantPoolGen());
            var referenceKind = calledMethod.getReferenceKind();
            var removeCallObjectArg = referenceKind == REF_invokeSpecial
                    || referenceKind == REF_invokeVirtual
                    || referenceKind == REF_invokeInterface;
            var arguments = eval.evalArguments(instructionHandle, invokeDynamicArgumentTypes.length);
            if (removeCallObjectArg) {
                var withoutCallObject = new ArrayList<>(arguments.getArguments());
                withoutCallObject.remove(0);
                arguments = new EvalArguments(withoutCallObject, arguments.getLastArgInstruction());
            }
            return List.of(arguments);
        } else {
            var argumentTypes = invokeInstruction.getArgumentTypes(eval.getConstantPoolGen());
            var arguments = eval.evalArguments(instructionHandle, argumentTypes.length);
            return List.of(arguments);
        }
    }

    static Map<CallContext, List<Result>[]> getCallContexts(List<Result> parameters,
                                                            List<List<Result>> parameterVariants) {
        var childToParentHierarchy = new HashMap<CallContext, Set<CallContext>>();
        var callContexts = new LinkedHashMap<CallContext, List<Result>[]>();
        for (int i = 0; i < parameters.size(); i++) {
            var variants = parameterVariants.get(i);
            for (var variant : variants) {
                populateArgumentsResults(parameters, variant, i, callContexts, childToParentHierarchy);
            }
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

    private static List<List<Result>> getResolvedParameters(List<Result> parameters) {
        var parameterVariants = parameters.stream().map(Eval::expand).collect(toList());
        int dimensions = getDimensions(parameterVariants);
        return flatResolvedVariants(dimensions, parameterVariants, parameters);
    }

    private static List<InstructionHandle> getStoreInstructions(
            InvokeBranch invokeBranch, int variableIndex, int loadInstructionPosition
    ) {
        var instructions = invokeBranch.findInstructions(StoreInstruction.class);
        var storeInstructions = instructions.stream()
                .collect(groupingBy(i -> ((StoreInstruction) i.getInstruction()).getIndex()));
        var storeInstructionsIntoVar = storeInstructions.getOrDefault(variableIndex, List.of());
        return storeInstructionsIntoVar.stream().filter(si -> si.getPosition() < loadInstructionPosition)
                .collect(toList());
    }

    private static List<InstructionHandle> getStoreInstructions(List<InvokeBranch> branches,
                                                                int variableIndex, int loadInstructionPosition) {
        return branches.stream().map(branchPrev -> {
            var storeInstructions = getStoreInstructions(branchPrev, variableIndex, loadInstructionPosition);
            return !storeInstructions.isEmpty() ? storeInstructions : getStoreInstructions(branchPrev.getPrev(),
                    variableIndex, loadInstructionPosition);
        }).flatMap(Collection::stream).collect(toList());
    }

    private static Collection<List<Result>> combineVariants(InvokeBranch branch, Result[] firstVariant, boolean cloned,
                                                            Map<InvokeBranch, Map<Integer, List<Result>>> groupedParams
    ) {
        var popMask = new BitSet(firstVariant.length);
        for (int i = 0; i < firstVariant.length; i++) {
            if (firstVariant[i] != null) {
                popMask.set(i);
            }
        }

        var allVariants = new LinkedHashSet<List<Result>>();
        var paramIndexToVariantsMap = groupedParams.getOrDefault(branch, Map.of());
        int dimensions = getDimensions(paramIndexToVariantsMap.values());
        var maps = flatResolvedVariants(dimensions, paramIndexToVariantsMap);
        for (var map : maps) {
            for (var index : map.keySet()) {
                var variants = map.get(index);
                //todo must be one parameter variant per branch
                state(variants.size() == 1, "variants must be exactly one " + variants);
                var result = variants.stream().findFirst().get();
                var existed = firstVariant[index];
                var exists = existed != null;
                var same = existed == result;
                if (!cloned) {
                    var problem = exists && !same;
//                state(!problem, index + " parameter must be null or the same");
                }
                if (!(cloned && exists && same)) {
                    firstVariant[index] = result;
                    popMask.set(index);
                }
            }

            int cardinality = popMask.cardinality();
            var full = cardinality == firstVariant.length;
            var partial = !full && cardinality > 0;
            var next = branch.getNext();
            int nextSize = next.size();

            if (full) {
                allVariants.add(Arrays.asList(firstVariant));
                firstVariant = firstVariant.clone();
            } else if (partial && nextSize == 0) {
                allVariants.add(Arrays.asList(firstVariant));
                firstVariant = firstVariant.clone();
            }
            for (int i = 0; i < nextSize; i++) {
                var clone = i > 0;
                var nextVariant = clone ? firstVariant.clone() : firstVariant;
                var nextBranch = next.get(i);
                var variants = combineVariants(nextBranch, nextVariant, clone, groupedParams);
                if (!variants.isEmpty()) {
                    allVariants.addAll(variants);
                }
            }
        }
        return allVariants;
    }

    private static Stream<Entry<? extends Class<?>, Entry<Method, Collection<List<Result>>>>> getEntryStream(
            List<Result> parameters, Map<Method, List<InvokeBranch>> methodBranches,
            Map<Method, Map<InvokeBranch, Map<Integer, List<Result>>>> grouped,
            Class<?> aClass) {
        return methodBranches.entrySet().stream().flatMap(ee -> {
            var method1 = ee.getKey();
            var branches = ee.getValue();
            return branches.stream().map(firstBranch -> {
                        var groupedParams = grouped.get(method1);
                        return combineVariants(firstBranch, new Result[parameters.size()], false, groupedParams);
                    }).filter(r -> !r.isEmpty())
                    .map(combineVariants -> entry(method1, combineVariants));
        }).map(combineVariantsPerMethod -> entry(aClass, combineVariantsPerMethod));
    }

    public ComponentKey getComponentKey() {
        return newComponentKey(getComponent());
    }

    private Map<Integer, List<Result>> resolveParameters(List<Result> parameters, Resolver resolver) {
        var resolvedParameters = new HashMap<Integer, List<Result>>();
        for (int i = 0; i < parameters.size(); i++) {
            try {
                var parameterResult = parameters.get(i);
                resolvedParameters.put(i, resolveExpand(parameterResult, resolver));
            } catch (NotInvokedException e) {
                //log
                return null;
            }
        }
        return resolvedParameters;
    }

    public String getComponentName() {
        return Optional.ofNullable(getComponent()).map(Component::getName).orElse(null);
    }

    public Object getObject() {
        return Optional.ofNullable(getComponent()).map(Component::getBean).orElse(null);
    }

    public String getClassName() {
        return javaClass.getClassName();
    }

    @Override
    public String toString() {
        return "Eval{" +
                "componentName='" + getComponentName() + "', " +
                "method='" + EvalUtils.toString(method) + '\'' +
                '}';
    }

    public Result eval(InstructionHandle instructionHandle) {
        return eval(instructionHandle, null);
    }

    @Deprecated
    public Result eval(InstructionHandle instructionHandle, @Deprecated Result parent) {
        var instruction = instructionHandle.getInstruction();

        var consumeStack = instruction.consumeStack(constantPoolGen);
        var instructionText = getInstructionString(instructionHandle, constantPoolGen);
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            var value = ldc.getValue(constantPoolGen);
            if (value instanceof Type) {
                value = getClassByName(((Type) value).getClassName());
            }
            return constant(value, ldc.getType(constantPoolGen), instructionHandle, instructionHandle, this, List.of());
        } else if (instruction instanceof LDC2_W) {
            var ldc = (LDC2_W) instruction;
            var value = ldc.getValue(constantPoolGen);
            return constant(value, ldc.getType(constantPoolGen), instructionHandle, instructionHandle, this, List.of());
        } else if (instruction instanceof LoadInstruction) {
            var loadInstruction = (LoadInstruction) instruction;
            var loadIndex = loadInstruction.getIndex();
            var localVariables = getLocalVariables(getMethod(), loadIndex);
            var localVariable = findLocalVariable(getMethod(), localVariables, instructionHandle);

            var name = localVariable != null ? localVariable.getName() : null;
            //check static method
            if ("this".equals(name)) {
                //todo value == null for static methods
                var value = getObject();
                return constant(value, loadInstruction.getType(constantPoolGen), instructionHandle,
                        instructionHandle, this, List.of());
            }
            var storeResults = getStoreInstructionResults(instructionHandle, loadIndex, parent);
            if (!storeResults.isEmpty()) {
                var description = instructionText + " from stored invocation";
                var storeInstructions = storeResults.stream()
                        .map(storeResult -> eval(storeResult.getFirstInstruction(), parent))
                        .collect(toList());
                var loadInstructionType = loadInstruction.getType(constantPoolGen);
                var first = storeInstructions.get(0);
                var expectedType = Optional.ofNullable(TypeAware.getType(first)).orElse(loadInstructionType);
                return delayLoadFromStored(description, instructionHandle, expectedType, this, parent,
                        storeInstructions, (thisDelay, resolver) -> {
                            var thisDelayStoreInstructions = thisDelay.getStoreInstructions();
                            var resolved = thisDelayStoreInstructions.stream()
                                    .flatMap(storeResult -> expand(this.resolve(storeResult, resolver)).stream())
                                    .collect(toList());
                            return collapse(resolved, instructionHandle, instructionHandle, constantPoolGen, this);
                        });
            }
            if (log.isTraceEnabled()) {
                log.trace("not found store for {} inside of {}.{}({})", name != null ? "'" + name + "' variable" : instructionText,
                        getTargetClass(getComponent()).getName(), getMethod().getName(), stringForLog(getMethod().getArgumentTypes()));
            }
            if (localVariable == null) {
                var argumentType = this.method.getArgumentTypes()[loadIndex - 1];
                return methodArg(this, loadIndex, null, argumentType, instructionHandle, parent);
            } else {
                return methodArg(this, localVariable, instructionHandle, parent);
            }
        } else if (instruction instanceof StoreInstruction) {
            var position = instructionHandle.getPosition();
            var codeException = Arrays.stream(this.method.getCode().getExceptionTable())
                    .filter(et -> et.getHandlerPC() == position)
                    .findFirst().orElse(null);
            if (codeException != null) {
                var catchType = constantPoolGen.getConstantPool().getConstantString(codeException.getCatchType(), CONSTANT_Class);
                var errType = ObjectType.getInstance(catchType);
                var localVarIndex = ((StoreInstruction) instruction).getIndex();
                var localVariable = getLocalVariable(this.method, localVarIndex, instructionHandle);
                return localVariable != null
                        ? variable(this, localVariable, instructionHandle, parent)
                        : variable(this, localVarIndex, null, errType, instructionHandle, parent);
            } else {
                //log
                //removed from stack
                var onSaveResult = evalPrev(instructionHandle, null);
                return evalPrev(onSaveResult);
            }
        } else if (instruction instanceof GETSTATIC) {
            var getStatic = (GETSTATIC) instruction;
            var fieldName = getStatic.getFieldName(constantPoolGen);
            var loadClassType = getStatic.getLoadClassType(constantPoolGen);
            var loadClass = getClassByName(loadClassType.getClassName());
            return getFieldValue(null, loadClass, fieldName, instructionHandle, instructionHandle, parent, this);
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;
            var evalFieldOwnedObject = evalPrev(instructionHandle, parent);
            var fieldName = getField.getFieldName(constantPoolGen);
            var lastInstruction = evalFieldOwnedObject.getLastInstruction();
            var relations = List.of(evalFieldOwnedObject);
            var fieldType = getField.getFieldType(constantPoolGen);
            return delay(instructionText, instructionHandle, lastInstruction, fieldType, this, parent, relations,
                    (thisDelay, unevaluatedHandler) -> {
                        var object = evalFieldOwnedObject.getValue(unevaluatedHandler).get(0);
                        return getFieldValue(getTargetObject(object), getTargetClass(object), fieldName,
                                instructionHandle, lastInstruction, thisDelay, this);
                    });
        } else if (instruction instanceof CHECKCAST) {
            return evalPrev(instructionHandle, parent);
        } else if (instruction instanceof InvokeInstruction) {
            return evalInvoke(instructionHandle, (InvokeInstruction) instruction, parent);
        } else if (instruction instanceof ANEWARRAY) {
            var anewarray = (ANEWARRAY) instruction;
            var size = evalPrev(instructionHandle, parent);
            var lastInstruction = size.getLastInstruction();
            var relations = List.of(size);
            var arrayType = anewarray.getType(constantPoolGen);
            return delay(instructionText, instructionHandle, lastInstruction, arrayType, this, parent, relations,
                    (thisDelay, resolver) -> {
                        var loadClassType = anewarray.getLoadClassType(this.getConstantPoolGen());
                        var arrayElementType = getClassByName(loadClassType.getClassName());
                        Object value = Array.newInstance(arrayElementType, (int) size.getValue());
                        return constant(value, loadClassType, instructionHandle, lastInstruction, this, asList(thisDelay, size));
                    });
        } else if (instruction instanceof ConstantPushInstruction) {
            var cpi = (ConstantPushInstruction) instruction;
            var value = cpi.getValue();
            return constant(value, cpi.getType(constantPoolGen), instructionHandle, instructionHandle,
                    this, List.of());
        } else if (instruction instanceof ArrayInstruction && instruction instanceof StackConsumer) {
            //AASTORE
            var element = evalPrev(instructionHandle, parent);
            var index = evalPrev(element);
            var array = evalPrev(index);
            var lastInstruction = array.getLastInstruction();
            var relations = List.of(element, index, array);
            var arrayType = ((ArrayInstruction) instruction).getType(constantPoolGen);
            return delay(instructionText, instructionHandle, lastInstruction, arrayType, this, parent,
                    relations, (thisDelay, resolver) -> {
                        var result = array.getValue();
                        if (result instanceof Object[]) {
                            var indexValue = index.getValue();
                            var value = element.getValue();
                            ((Object[]) result)[(int) indexValue] = value;
                        } else {
                            throw newInvalidEvalException("expectedResultClass array but was " + result.getClass(),
                                    instruction, constantPoolGen.getConstantPool());
                        }
                        return constant(result, arrayType, instructionHandle, lastInstruction, this, asList(element, index, array)
                        );
                    });
        } else if (instruction instanceof ArrayInstruction && instruction instanceof StackProducer) {
            var element = evalPrev(instructionHandle, parent);
            var index = evalPrev(element);
            var array = evalPrev(index);
            var lastInstruction = array.getLastInstruction();
            var relations = List.of(element, index, array);
            var arrayType = ((ArrayInstruction) instruction).getType(constantPoolGen);
            //AALOAD
            return delay(instructionText, instructionHandle, lastInstruction, arrayType, this, parent,
                    relations, (thisDelay, resolver) -> {
                        var result = array.getValue();
                        if (result instanceof Object[]) {
                            var a = (Object[]) result;
                            var i = (int) index.getValue();
                            var e = a[i];
                            return constant(e, arrayType, lastInstruction, lastInstruction, this,
                                    asList(element, index, array)
                            );
                        } else {
                            throw newInvalidEvalException("expected result class array but was " + result.getClass(),
                                    instruction, constantPoolGen.getConstantPool());
                        }
                    });
        } else if (instruction instanceof ARRAYLENGTH) {
            var arrayRef = evalPrev(instructionHandle, parent);
            var relations = List.of(arrayRef);
            return delay(instructionText, instructionHandle, arrayRef.getLastInstruction(), Type.INT, this,
                    parent, relations, (thisDelay, resolver) -> {
                        return this.resolve(arrayRef, resolver);
                    });
        } else if (instruction instanceof NEW) {
            var newInstance = (NEW) instruction;
            var loadClassType = newInstance.getLoadClassType(constantPoolGen);
            var newInstanceType = newInstance.getType(constantPoolGen);
            return delay(instructionText, instructionHandle, instructionHandle, newInstanceType, this, parent,
                    List.of(), (thisDelay, resolver) -> {
                        var type = getClassByName(loadClassType.getClassName());
                        return instantiateObject(instructionHandle, type, new Class[0], new Object[0], thisDelay, this);
                    });
        } else if (instruction instanceof DUP) {
            var dup = evalPrev(instructionHandle, null);
            return duplicate(instructionHandle, dup.getLastInstruction(), dup, this);
        } else if (instruction instanceof DUP2) {
//            return evalPrev(instructionHandle, resolver);
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
            var prev = getPrev(onRemove);
            return eval(prev, parent);
        } else if (instruction instanceof POP2) {
//            return evalPrev(instructionHandle, resolver);
        } else if (instruction instanceof ACONST_NULL) {
            return constant(null, ((ACONST_NULL) instruction).getType(constantPoolGen), instructionHandle,
                    instructionHandle, this, asList(parent)
            );
        } else if (instruction instanceof IfInstruction) {
            var args = new Result[consumeStack];
            var current = instructionHandle;
            for (var i = consumeStack - 1; i >= 0; --i) {
                current = getPrev(instructionHandle);
                args[i] = eval(current, parent);
            }
            var prev = args.length > 0 ? evalPrev(args[0]) : evalPrev(instructionHandle, parent);
            //now only positive scenario
            //todo need evaluate negative branch
            return prev;
        } else if (instruction instanceof ConversionInstruction) {
            //I2L,
            var conv = (ConversionInstruction) instruction;
            var convertTo = conv.getType(constantPoolGen);
            var convertedValueResult = evalPrev(instructionHandle, parent);
            var lastInstruction = convertedValueResult.getLastInstruction();
            var relations = List.of(convertedValueResult);
            return delay(instructionText, instructionHandle, lastInstruction, convertTo, this, parent, relations,
                    (thisDelay, resolver) -> {
                        var values = convertedValueResult.getValue(resolver);
                        var results = values.stream()
                                .map(value -> (Number) value)
                                .map(number -> convertNumberTo(number, convertTo))
                                .map(converted -> constant(converted, convertTo, instructionHandle,
                                        lastInstruction, this, singletonList(thisDelay)
                                ))
                                .collect(toList());
                        return collapse(results, instructionHandle, lastInstruction, getConstantPoolGen(),
                                this);
                    });
        } else if (instruction instanceof ArithmeticInstruction) {
            var arith = (ArithmeticInstruction) instruction;
            var first = evalPrev(instructionHandle, parent);
            var second = consumeStack == 2 ? evalPrev(first) : null;
            var lastInstruction = second != null ? second.getLastInstruction() : first.getLastInstruction();
            var relations = second != null ? List.of(first, second) : List.of(first);
            var arithType = arith.getType(constantPoolGen);
            return delay(instructionText, instructionHandle, lastInstruction, arithType, this, parent, relations,
                    (thisDelay, resolver) -> {
                        try {
                            var computed = computeArithmetic(arith, first, second);
                            return constant(computed, arithType, instructionHandle, lastInstruction, this, asList(first, second)
                            );
                        } catch (UnresolvedResultException e) {
                            return resolveOrThrow(thisDelay, resolver, e);
                        }
                    });
        } else if (instruction instanceof ARETURN) {
            return eval(getPrev(instructionHandle), parent);
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen.getConstantPool());
    }

    public List<Result> getStoreInstructionResults(InstructionHandle instructionHandle, int index, Result parent) {
        var branches = tree.findNextBranchContains(instructionHandle.getPosition());
        var storeInstructions = getStoreInstructions(branches, index, instructionHandle.getPosition());
        var results = storeInstructions.stream()
                .map(storeInstrHandle -> eval(getPrev(storeInstrHandle), parent))
                .collect(toList());
        return results;
    }

    public DelayInvoke evalInvoke(InstructionHandle instructionHandle, InvokeInstruction instruction, Result parent) {
        if (log.isTraceEnabled()) {
            log.trace("eval {}", getInstructionString(instructionHandle, constantPoolGen));
        }
        if (instruction instanceof INVOKEVIRTUAL || instruction instanceof INVOKEINTERFACE) {
            return evalInvokeVirtual(instructionHandle, instruction, parent);
        } else if (instruction instanceof INVOKEDYNAMIC) {
            return evalInvokeDynamic(instructionHandle, instruction, parent);
        } else if (instruction instanceof INVOKESTATIC) {
            return evalInvokeStatic(instructionHandle, instruction, parent);
        } else if (instruction instanceof INVOKESPECIAL) {
            return evalInvokeSpecial(instructionHandle, instruction, parent, this.constantPoolGen);
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen.getConstantPool());
    }

    private DelayInvoke evalInvokeSpecial(InstructionHandle instructionHandle, InvokeInstruction instruction,
                                          Result parent, ConstantPoolGen constantPoolGen) {
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);

        var arguments = evalArguments(instructionHandle, argumentTypes.length);
        var invokeObject = evalInvokeObject(instruction, arguments);
        var invokeSpec = (INVOKESPECIAL) instruction;
        var loadClassType = invokeSpec.getLoadClassType(constantPoolGen);
        return delayInvoke(instructionHandle, loadClassType, this, parent, invokeObject, methodName,
                arguments, (thisDelay, resolver) -> {
                    var argumentClasses = toClasses(argumentTypes);
                    return callInvokeSpecial(thisDelay, argumentClasses, this, true, resolver,
                            (parameters, lastInstruction) -> {
                                var lookup = MethodHandles.lookup();
                                var objectClass = getClassByName(instruction.getClassName(constantPoolGen));
                                var signature = invokeSpec.getSignature(constantPoolGen);
                                var methodType = fromMethodDescriptorString(signature, objectClass.getClassLoader());
                                var paramValues = getValues(parameters);
                                var component = getComponent();
                                var method = getMethod();
                                if ("<init>".equals(methodName)) {
                                    return instantiateObject(lastInstruction, objectClass, argumentClasses, paramValues,
                                            thisDelay, this);
                                } else {
                                    var privateLookup = InvokeDynamicUtils.getPrivateLookup(objectClass, lookup);
                                    var methodHandle = getMethodHandle(() -> privateLookup.findSpecial(objectClass,
                                            methodName, methodType, objectClass));
                                    return invoke(methodHandle, paramValues, instructionHandle, lastInstruction,
                                            loadClassType, this, parameters);
                                }
                            });
                });
    }

    private DelayInvoke evalInvokeStatic(InstructionHandle instructionHandle,
                                         InvokeInstruction instruction, Result parent) {
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var invokeResultType = instruction.getType(constantPoolGen);
        var invokeObjectClassName = instruction.getClassName(constantPoolGen);
        var arguments = evalArguments(instructionHandle, argumentTypes.length);
        return delayInvoke(instructionHandle, invokeResultType, this, parent, null, methodName,
                arguments, (thisDelay, resolver) -> {
                    var argumentClasses = toClasses(argumentTypes);
                    return callInvokeStatic(thisDelay, argumentClasses, this, true,
                            resolver, (parameters, lastInstruction) -> {
                                var objectClass = toClass(invokeObjectClassName);
                                return callMethod(null, objectClass, methodName, argumentClasses,
                                        getValues(parameters), instructionHandle, lastInstruction,
                                        invokeResultType, constantPoolGen, thisDelay, parameters, this);
                            });
                });
    }

    private DelayInvoke evalInvokeDynamic(InstructionHandle instructionHandle, InvokeInstruction instruction, Result parent) {
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var invokeResultType = instruction.getType(constantPoolGen);
        var arguments = evalArguments(instructionHandle, argumentTypes.length);
        return delayInvoke(instructionHandle, invokeResultType, this, parent, null, null,
                arguments, (thisDelay, resolver) -> {
                    var argumentClasses = toClasses(argumentTypes);
                    return callInvokeDynamic(thisDelay, argumentClasses, this, true, resolver,
                            (parameters, lastInstruction) -> {
                                var bootstrapMethodAndArguments = getBootstrapMethodHandlerAndArguments(
                                        (INVOKEDYNAMIC) instruction, bootstrapMethods, constantPoolGen);
                                var handler = bootstrapMethodAndArguments.getHandler();
                                var methodArguments = bootstrapMethodAndArguments.getBootstrapMethodArguments();
                                var callSite = getCallSite(handler, methodArguments);
                                return callBootstrapMethod(getValues(parameters), instructionHandle, lastInstruction,
                                        invokeResultType, this, parameters, callSite);
                            });
                });
    }

    private DelayInvoke evalInvokeVirtual(InstructionHandle instructionHandle, InvokeInstruction instruction, Result parent) {
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var invokeResultType = instruction.getType(constantPoolGen);
        var arguments = evalArguments(instructionHandle, argumentTypes.length);
        var invokeObject = evalInvokeObject(instruction, arguments);
        return delayInvoke(instructionHandle, invokeResultType, this, parent, invokeObject, methodName,
                arguments, (thisDelay, resolver) -> {
                    var argumentClasses = toClasses(argumentTypes);
                    return callInvokeVirtual(thisDelay, argumentClasses, this, true, resolver,
                            (parameters, lastInstruction) -> {
                                var paramValues = getValues(parameters);
                                var object = paramValues[0];
                                var argValues = copyOfRange(paramValues, 1, paramValues.length);
                                var objectClass = toClass(instruction.getClassName(constantPoolGen));
                                return callMethod(object, objectClass, instruction.getMethodName(constantPoolGen),
                                        argumentClasses, argValues, instructionHandle, lastInstruction,
                                        invokeResultType, constantPoolGen, thisDelay, parameters, this);
                            });
                });
    }

    private Result callWithParameterVariants(DelayInvoke invoke, Class<?>[] parameterClasses, boolean throwNoCall,
                                             Resolver resolver, BiFunction<List<ParameterValue>, InstructionHandle, Result> call
    ) throws NotInvokedException {
        var parameters = toParameters(invoke.getObject(), invoke.getArguments());
        var parameterVariants = resolveInvokeParameters(invoke, parameters, resolver);
        if (parameterVariants.isEmpty()) {
            throw new NotInvokedException(noParameterVariants, invoke, parameters);
        }
        var lastInstruction = invoke.getLastInstruction();
        var results = callWithParameterVariants(invoke, parameterClasses, resolver, call,
                parameterVariants, lastInstruction);
        var unresolved = results.get(false).stream().map(p -> p.exception).collect(toList());
        var resolved = results.get(true).stream().map(p -> p.result).collect(toList());
        if (resolved.isEmpty()) {
            if (throwNoCall) {
                if (unresolved.isEmpty()) {
                    throw new NotInvokedException(noCalls, invoke);
                } else {
                    throw new NotInvokedException(unresolvedVariables, unresolved, invoke);
                }
            } else {
                return resolveAndInvoke(invoke, parameters, parameterClasses, lastInstruction, resolver, call);
            }
        }
        return collapse(resolved, invoke.getFirstInstruction(), lastInstruction, getConstantPoolGen(), this);
    }

    private Map<Boolean, List<InvokedResult>> callWithParameterVariants(
            DelayInvoke invoke, Class<?>[] parameterClasses, Resolver resolver,
            BiFunction<List<ParameterValue>, InstructionHandle, Result> call,
            Collection<List<Result>> parameterVariants, InstructionHandle lastInstruction) {
        return parameterVariants.stream().map(parameterVariant -> {
            try {
                var result = resolveAndInvoke(invoke, parameterVariant, parameterClasses, lastInstruction,
                        resolver, call);
                return new InvokedResult(result, null);
            } catch (EvalException /*UnresolvedVariableException*/ e) {
                //log
                return new InvokedResult(null, e);
            }
        }).collect(partitioningBy(p -> p.exception == null));
    }

    private Result resolveAndInvoke(Delay current, List<Result> parameters, Class<?>[] parameterClasses,
                                    InstructionHandle lastInstruction, Resolver resolver,
                                    BiFunction<List<ParameterValue>, InstructionHandle, Result> call) {
        var callParameters = resolveCallParameters(current, parameters, parameterClasses, resolver);
        var key = new CallCacheKey(current, callParameters, lastInstruction.getInstruction());
        if (callCache != null) {
            var cached = callCache.get(key);
            if (cached != null) {
                log.trace("get cached call result, call '{}', result '{}'", key, cached);
                return cached;
            }
        }

        var callResult = call(current, lastInstruction, resolver, callParameters, call, getConstantPoolGen(), this);
        if (callCache != null) {
            log.trace("no cached call result, call '{}', result '{}'", key, callResult);
            callCache.put(key, callResult);
        }
        return callResult;
    }

    private List<List<ParameterValue>> resolveCallParameters(Delay current, List<Result> parameters,
                                                             Class<?>[] parameterClasses, Resolver resolver) {
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

    //todo need to migrates on evalPrev in place of getPrev
    public InvokeObject evalInvokeObject(InvokeInstruction invokeInstruction, EvalArguments evalArguments) {
        final InstructionHandle firstInstruction, lastInstruction;
        final Result objectCallResult;
        var lastArgInstruction = evalArguments.getLastArgInstruction();
        var methodName = invokeInstruction.getMethodName(constantPoolGen);
        if (invokeInstruction instanceof INVOKESPECIAL && "<init>".equals(methodName)) {
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
        } else if (invokeInstruction instanceof INVOKESPECIAL
                || invokeInstruction instanceof INVOKEVIRTUAL
                || invokeInstruction instanceof INVOKEINTERFACE
        ) {
            objectCallResult = evalPrev(lastArgInstruction, null);
            firstInstruction = objectCallResult.getFirstInstruction();
            lastInstruction = objectCallResult.getLastInstruction();
        } else {
            objectCallResult = null;
            firstInstruction = lastArgInstruction;
            lastInstruction = lastArgInstruction;
        }
        return new InvokeObject(firstInstruction, lastInstruction, objectCallResult);
    }

    public Collection<List<Result>> resolveInvokeParameters(DelayInvoke invoke, List<Result> parameters, Resolver resolver) {
        if (parameters.isEmpty()) {
            return getResolvedParameters(parameters);
        }

        var allResolved = parameters.stream().allMatch(Result::isResolved);
//        if (allResolved) {
//            return getResolvedParameters(parameters);
//        }

        if (!(this.arguments == null || this.arguments.isEmpty())) {
            if (allResolved) {
                return getResolvedParameters(parameters);
            }
            //inside a call point
            var parameterVariants = parameters.stream().map(parameter -> resolveExpand(parameter, resolver)).collect(toList());
            int dimensions = getDimensions(parameterVariants);
            return flatResolvedVariants(dimensions, parameterVariants, parameters);
        } else {
            var noArgumentVariants = this.argumentVariants.isEmpty();
            if (allResolved) {
                if (noArgumentVariants) {
                    return getResolvedParameters(parameters);
                }
            }
            if (noArgumentVariants) {
                //not callpoints, try to resolve by the resolver
                //todo experiment
                var parameterVariants = parameters.stream().map(parameter -> resolveExpand(parameter, resolver)).collect(toList());
                var dimensions = getDimensions(parameterVariants);
                return flatResolvedVariants(dimensions, parameterVariants, parameters);
            } else {

                var resolvedAll = resolveParametersWithContextArgumentVariants(parameters, resolver);
                var resolvedParamVariants = new ArrayList<List<Result>>();
                for (var resolvedVariantMap : resolvedAll) {
                    var parameterVariants = new ArrayList<>(resolvedVariantMap.values());
                    int dimensions = getDimensions(parameterVariants);
                    if (dimensions <= 3) {
                        resolvedParamVariants.addAll(flatResolvedVariants(dimensions, parameterVariants, parameters));
                    } else {
                        var roots = new HashMap<Class<?>, Map<Method, List<InvokeBranch>>>();
                        var grouped = groupParamsByBranch(parameterVariants, roots);

                        var aClass1 = this.getComponent().getType();
//                        var thisComponentMethodBranches = roots.remove(aClass1);
//
//                        var internal = getEntryStream(parameters, thisComponentMethodBranches, grouped.get(aClass1), aClass1)
//                                .collect(groupingBy(Entry::getKey,
//                                        groupingBy(e1 -> e1.getValue().getKey(),
//                                                mapping(rt -> rt.getValue().getValue(), toList())))
//                                );

                        var external = roots.entrySet().stream().flatMap(rootClassMethod -> {
                            var aClass = rootClassMethod.getKey();
                            var methodBranches = rootClassMethod.getValue();
                            return getEntryStream(parameters, methodBranches, grouped.get(aClass), aClass);
                        }).collect(groupingBy(Entry::getKey,
                                groupingBy(e1 -> e1.getValue().getKey(),
                                        mapping(rt -> rt.getValue().getValue(), toList())))
                        );

                        var combinedVariants = roots.entrySet().stream()
                                .flatMap(e -> {
                                    var aClass = e.getKey();
                                    var cc = e.getValue();
                                    var values = cc.entrySet();
                                    return values.stream().flatMap(t -> {
                                        var method = t.getKey();
                                        var branches = t.getValue();
                                        return branches.stream().flatMap(firstBranch -> {
                                            var methodMapMap = grouped.get(aClass);
                                            var groupedParams = methodMapMap.get(method);
                                            return combineVariants(firstBranch, new Result[parameters.size()], false, groupedParams).stream();
                                        }).filter(r -> !r.isEmpty());
                                    });
                                })
                                .collect(toLinkedHashSet());
                        resolvedParamVariants.addAll(combinedVariants);
                    }
                }
                return resolvedParamVariants;
            }
        }
    }

    private Map<Class<?>, Map<Method, Map<InvokeBranch, Map<Integer, List<Result>>>>> groupParamsByBranch(
            List<List<Result>> parameterVariants, Map<Class<?>, Map<Method, List<InvokeBranch>>> roots
    ) {
        var grouped = new HashMap<Class<?>, Map<Method, Map<InvokeBranch, Map<Integer, List<Result>>>>>();
        for (int index = 0; index < parameterVariants.size(); index++) {
            var parameterVariant = parameterVariants.get(index);
            for (var parameter : parameterVariant) {
                var eval = parameter.getEval();
                var root = eval.getTree();
                var aClass = eval.getComponent().getType();
                var method = eval.getMethod();
                roots.computeIfAbsent(aClass, k -> new HashMap<>()).computeIfAbsent(method, k -> new ArrayList<>()).add(root);
                populateBranches(parameter, grouped, aClass, method, index, root.findNextBranchContains(parameter.getFirstInstruction().getPosition()));
            }
        }
        for (var aClass : grouped.keySet()) {
            var methods = grouped.get(aClass);
            for (var method : methods.keySet()) {
                var branches = methods.get(method);
                for (var branch : branches.keySet()) {
                    var params = branches.get(branch);
                    for (var index : new ArrayList<>(params.keySet())) {
                        var variants = params.get(index);
                        if (variants.size() > 1) {
                            for (var variant : variants) {
                                if (variant instanceof RelationsAware) {
                                    var relAware = (RelationsAware) variant;
                                    var relations = relAware.getRelations();
                                    for (var relation : relations) {
                                        var eval = relation.getEval();
                                        var root = eval.getTree();
                                        var aClass1 = eval.getComponent().getType();
                                        var method1 = eval.getMethod();

                                        roots.computeIfAbsent(aClass1, k -> new HashMap<>())
                                                .computeIfAbsent(method1, k -> new ArrayList<>())
                                                .add(root);

                                        var branches1 = root.findNextBranchContains(
                                                        relation.getFirstInstruction().getPosition()).stream().
                                                filter(b -> !b.equals(branch))
                                                .collect(toList());

                                        if (!branches1.isEmpty()) {
                                            params.remove(index);
                                            populateBranches(variant, grouped, aClass1, method1, index, branches1);
                                        }
                                    }

                                } else {
                                    throw new UnsupportedOperationException("TODO");
                                }
                            }
                        }
                    }
                }
            }
        }
        return grouped;
    }

    private void populateBranches(Result parameter, HashMap<Class<?>,
                                          Map<Method, Map<InvokeBranch, Map<Integer, List<Result>>>>> grouped,
                                  Class<?> aClass, Method method, int index, List<InvokeBranch> branches) {

        notEmpty(branches, "no branches for parameter " + parameter + " in method " + this.getMethod().getName());

        for (var branch : branches) {
            grouped.computeIfAbsent(aClass, k -> new HashMap<>()).computeIfAbsent(method, k -> new HashMap<>())
                    .computeIfAbsent(branch, k -> new HashMap<>())
                    .computeIfAbsent(index, k -> new ArrayList<>())
                    .add(parameter);
        }
    }

    private List<Map<Integer, List<Result>>> resolveParametersWithContextArgumentVariants(List<Result> parameters, Resolver resolver) {
        var resolvedAll = new ArrayList<Map<Integer, List<Result>>>();
        for (var arguments : this.argumentVariants) {
            var evalContextArgs = getEvalContextArgs(arguments, resolver);
            if (evalContextArgs != null) {
                var dimensions = evalContextArgs.values().stream()
                        .map(r -> r instanceof Multiple ? ((Multiple) r).getResults().size() : 1)
                        .reduce(1, (l, r) -> l * r);
                var evalContextArgsVariants = getEvalContextArgsVariants(dimensions, evalContextArgs);
                var compressedEvalContextArgsVariants = compress(evalContextArgsVariants, evalContextArgs);
                for (var variant : compressedEvalContextArgsVariants) {
                    var evalWithArguments = this.withArguments(variant);
                    var resolvedVars = evalWithArguments.resolveParameters(parameters, resolver);
                    if (resolvedVars != null) {
                        resolvedAll.add(resolvedVars);
                    }
                }
            }
        }
        return resolvedAll;
    }

    private List<Map<Integer, Result>> compress(List<Map<Integer, Result>> evalContextArgsVariants, Map<Integer, Result> evalContextArgs) {
        return evalContextArgsVariants.stream().distinct().collect(toList());
    }

    private Map<Integer, Result> getEvalContextArgs(List<Result> arguments, Resolver resolver) {
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
                if (!sameLevel && resolver != null) {
                    resolved = resolver.resolve(value, e);
                } else {
                    return null;
                }
            } catch (EvalException e) {
                //log
                return null;
            }
            evalContextArgs.put(i + 1, resolved);
        }
        return evalContextArgs;
    }

    protected Result callMethod(Object object, Class<?> objectClass, String methodName,
                                Class<?>[] argTypes, Object[] args,
                                InstructionHandle invokeInstruction, InstructionHandle lastInstruction,
                                Type expectedType, ConstantPoolGen constantPoolGen, Delay invoke,
                                List<ParameterValue> parameters, Eval eval) {
        var msg = "callMethod";
        if (object != null && !objectClass.isAssignableFrom(object.getClass())) {
            log.debug("unexpected callable object objectClass {}, expected {}, object '{}', method {}",
                    object.getClass().getName(), objectClass.getName(), object, methodName);
        }
        var declaredMethod = getDeclaredMethod(objectClass, methodName, argTypes);
        if (declaredMethod == null) {
            log.info("{}, method not found '{}.{}', instruction {}", msg, objectClass.getName(), methodName,
                    EvalUtils.toString(invokeInstruction, constantPoolGen));
            return notFound(methodName, invokeInstruction, invoke, eval);
        } else if (!declaredMethod.trySetAccessible()) {
            log.warn("{}, method is not accessible, method '{}.{}', instruction {}", msg, objectClass.getName(), methodName,
                    EvalUtils.toString(invokeInstruction, constantPoolGen));
            return notAccessible(declaredMethod, invokeInstruction, invoke, eval);
        }
        Object result;
        try {
            result = declaredMethod.invoke(object, args);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            //log
            throw new IllegalInvokeException(e, new MethodInvokeContext(declaredMethod, object, args), invokeInstruction, invoke);
        }
        if (log.isDebugEnabled()) {
            log.debug("{}, success, method '{}.{}', result: {}, instruction {}", msg, objectClass.getName(), methodName,
                    result, EvalUtils.toString(invokeInstruction, constantPoolGen));
        }
        return invoked(result, expectedType, invokeInstruction, lastInstruction, this, parameters);
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

            var eval = variable.getEval();
            var argumentVariants = eval.getArgumentVariants();

            var valueVariants = argumentVariants.stream().map(variant -> {
                var i = index - 1;
                if (i >= variant.size()) {
                    //logs
                    return stub(variable, resolver);
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
                result = collapse(resolvedVariants, variable.getFirstInstruction(), variable.getLastInstruction(), constantPoolGen, eval);
            } else {
                if (resolver != null) {
                    result = resolver.resolve(value, null);
                } else {
                    result = variable;
                }
            }
        } else if (value instanceof Delay) {
            try {
                var delay = (Delay) value;
                var delayComponentKey = delay.getComponentKey();
                var delayMethod = delay.getMethod();
                var componentKey = getComponentKey();
                if (componentKey.equals(delayComponentKey) && method.equals(delayMethod) && this.arguments != null) {
                    delay = delay.withEval(this);
                }
                result = delay.getDelayed(resolver);
            } catch (UnresolvedVariableException e) {
                result = resolveOrThrow(value, resolver, e);
            }
        } else if (value instanceof Duplicate) {
            result = resolve(((Duplicate) value).getOnDuplicate(), resolver);
        } else {
            result = value;
        }
        return result;
    }

    public EvalArguments evalArguments(InstructionHandle instructionHandle, int argumentsAmount) {
        var values = new Result[argumentsAmount];
        var current = instructionHandle;
        for (int i = argumentsAmount; i > 0; i--) {
            var eval = evalPrev(current, null);
            var valIndex = i - 1;
            values[valIndex] = eval;
            current = eval.getLastInstruction();
        }
        return new EvalArguments(asList(values), current);
    }

    public InstructionHandle getPrev(InstructionHandle instructionHandle) {
        var prevs = getPrevious(instructionHandle);
        return !prevs.isEmpty() ? prevs.get(0) : null;
    }

    public List<InstructionHandle> getPrevious(InstructionHandle instructionHandle) {
        var branches = tree.findNextBranchContains(instructionHandle.getPosition());
        state(!branches.isEmpty(), "no branch for instruction " + instructionHandle.getInstruction());
        return branches.stream()
                .flatMap(b -> b.getPrevInstructions(instructionHandle).stream())
                .map(prev -> {
                    return prev.getInstruction() instanceof BranchInstruction ? prev.getPrev() : prev;
                }).distinct().collect(toList());
    }

    @Deprecated
    public Result evalPrev(InstructionHandle instructionHandle, @Deprecated Result parent) {
        return eval(getPrev(instructionHandle), parent);
    }

    public Result evalPrev(Result result) {
        if (result instanceof Duplicate) {
            return ((Duplicate) result).getOnDuplicate();
        } else {
            return eval(getPrev(result.getLastInstruction()));
        }
    }

    public Eval withArguments2(int firstIndex, List<Result> arguments) {
        var argumentsMap = new LinkedHashMap<Integer, Result>();
        for (int i = 0; i < arguments.size(); i++) {
            argumentsMap.put(i + firstIndex, arguments.get(i));
        }
        return this.withArguments(argumentsMap);
    }

    public interface CallCache {

        static CallCache noCallCache() {
            return new CallCache() {
                @Override
                public Result get(CallCacheKey key) {
                    return null;
                }

                @Override
                public void put(CallCacheKey key, Result result) {

                }
            };
        }

        static CallCache newCallCache(final Map<CallCacheKey, Result> map) {
            return new CallCache() {
                @Override
                public Result get(CallCacheKey key) {
                    return map.get(key);
                }

                @Override
                public void put(CallCacheKey key, Result result) {
                    map.put(key, result);
                }
            };
        }

        Result get(CallCacheKey key);

        void put(CallCacheKey key, Result result);
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
        EvalException exception;
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
        ComponentKey component;
        Method method;
        @EqualsAndHashCode.Exclude
        Result result;

        public static CallContext newCallContext(ComponentKey componentKey, Method method, Result result) {
            return new CallContext(componentKey, method, result);
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
        //todo incompatibility with Duplicated result
        @Deprecated
        InstructionHandle lastArgInstruction;

        @Override
        public String toString() {
            return "arguments" + arguments;
        }
    }
}
