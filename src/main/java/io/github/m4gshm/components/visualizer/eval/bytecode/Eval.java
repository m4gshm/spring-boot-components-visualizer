package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.result.*;
import io.github.m4gshm.components.visualizer.model.CallPoint;
import io.github.m4gshm.components.visualizer.model.Component;
import io.github.m4gshm.components.visualizer.model.Component.ComponentKey;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.components.visualizer.Utils.toLinkedHashSet;
import static io.github.m4gshm.components.visualizer.eval.bytecode.ArithmeticUtils.computeArithmetic;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalException.newInvalidEvalException;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalException.newUnsupportedEvalException;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodHandlerAndArguments;
import static io.github.m4gshm.components.visualizer.eval.bytecode.LocalVariableUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.NotInvokedException.Reason.*;
import static io.github.m4gshm.components.visualizer.eval.result.Result.*;
import static io.github.m4gshm.components.visualizer.eval.result.TypeAware.getType;
import static io.github.m4gshm.components.visualizer.eval.result.Variable.VarType.MethodArg;
import static io.github.m4gshm.components.visualizer.model.Component.ComponentKey.newComponentKey;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.lang.reflect.Modifier.STATIC;
import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.*;
import static java.util.stream.IntStream.range;
import static java.util.stream.Stream.*;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.bcel.Const.*;
import static org.springframework.aop.support.AopUtils.getTargetClass;
import static org.springframework.util.Assert.notEmpty;
import static org.springframework.util.Assert.state;

@Slf4j
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@FieldDefaults(makeFinal = true, level = PRIVATE)
//@AllArgsConstructor(access = PROTECTED)
public class Eval {
    private static final AtomicInteger counter = new AtomicInteger();
    @Getter
    @EqualsAndHashCode.Include
    Component component;
    @Getter
    @EqualsAndHashCode.Include
    Method method;
    @Getter
    @EqualsAndHashCode.Include
    JavaClass javaClass;
    @Getter
    Map<Integer, Result> arguments;
    @Getter
    Collection<Map<Integer, Result>> argumentVariants;
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
                BootstrapMethods bootstrapMethods, CallCache callCache, Collection<Map<Integer, Result>> argumentVariants,
                Map<Integer, Result> arguments, InvokeBranch tree) {
        this.component = component;
        this.javaClass = javaClass;
        this.constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
        this.bootstrapMethods = bootstrapMethods;
        this.method = method;
        this.callCache = callCache;
        this.methodCode = method.getCode();
        this.tree = tree;
        this.arguments = arguments;
        this.argumentVariants = argumentVariants;
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
        return getDimensions(variantOfVariantOfParameters.stream());
    }

    private static int getDimensions(List<ParameterVariants> parameterVariants) {
        return getDimensions(parameterVariants.stream().map(p -> p.values).filter(Objects::nonNull));
    }

    private static <T> Integer getDimensions(Stream<List<T>> stream) {
        return stream.parallel().map(Collection::size).reduce(1, (l, r) -> l * r);
    }

    private static List<List<Result>> flatResolvedVariants(
            int dimensions, Collection<List<Result>> parameterVariants, Collection<Result> parameters) {
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

    private static List<Map<Integer, List<Result>>> flatResolvedParamsVariants(
            int dimensions, Map<Integer, List<Result>> paramIndexToVariantsMap
    ) {
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
        return result instanceof Multiple ? ((Multiple) result).getResults() : List.of(result);
    }

    public static Result collapse(Collection<? extends Result> values, Eval eval) {
        state(!values.isEmpty(), "no results for collapse");
        return values.size() > 1
                ? multiple(values.stream().map(Eval::expand).flatMap(Collection::stream).collect(toList()), eval)
                : values.iterator().next();
    }

    private static Result resolveOrThrow(Result result, Resolver resolver, EvalException e, Eval eval) {
        if (resolver != null) {
            return resolver.resolve(result, e, eval);
        } else {
            throw e;
        }
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

    private static boolean isSameLevel(Variable unresolved, Component contextComponent) {
        return contextComponent.equals(unresolved.getComponent());
    }

    private static List<List<Result>> getResolvedParameters(List<Result> parameters) {
        var parameterVariants = parameters.stream().map(Eval::expand).collect(toList());
        int dimensions = getDimensions(parameterVariants);
        return flatResolvedVariants(dimensions, parameterVariants, parameters);
    }

    private static InstructionHandle getLastStoreInstructionOfBranch(
            InvokeBranch invokeBranch, int variableIndex, int loadInstructionPosition
    ) {
        var instructions = invokeBranch.findInstructions(StoreInstruction.class);
        var storeInstructions = instructions.stream()
                .collect(groupingBy(i -> ((StoreInstruction) i.getInstruction()).getIndex()));
        var storeInstructionsIntoVar = storeInstructions.getOrDefault(variableIndex, List.of());
        var last = storeInstructionsIntoVar.stream()
                .filter(si -> si.getPosition() <= loadInstructionPosition)
                .reduce((l, r) -> r).orElse(null);
        return last;
    }

    private static Set<InstructionHandle> getLastStoreInstructionPerBranch(List<InvokeBranch> branches,
                                                                           int variableIndex, int loadInstructionPosition) {
        return branches.stream()/*.parallel()*/.flatMap(branch -> {
            var storeInstruction = getLastStoreInstructionOfBranch(branch, variableIndex, loadInstructionPosition);
            var prev = branch.getPrev();
            var nextStore = getLastStoreInstructionPerBranch(prev, variableIndex, loadInstructionPosition);
            return concat(Stream.ofNullable(storeInstruction), nextStore.stream());
        }).collect(toLinkedHashSet());
    }

    private static List<Result[]> populateVariants(Result[] firstVariant, boolean canOverwrite,
                                                   Map<Integer, List<Result>> resolvedVariant, BitSet popMask) {
        var results = new ArrayList<Result[]>();
        results.add(firstVariant);
        for (var paramIndex : resolvedVariant.keySet()) {
            var variants = resolvedVariant.get(paramIndex);
            //todo must be one parameter variant per branch
            state(variants.size() == 1, "variants must be exactly one " + variants);
            var result = variants.stream().findFirst().get();
            var existed = firstVariant[paramIndex];
            var exists = existed != null;
            var same = existed == result;
            if (!exists || canOverwrite) {
                for (var variant : results) {
                    variant[paramIndex] = result;
                }
                popMask.set(paramIndex);
            } else if (!same) {
                var nextVariant = firstVariant.clone();
                nextVariant[paramIndex] = result;
                results.add(nextVariant);
                popMask.set(paramIndex);
            }
        }
        return results;
    }

    private static BitSet initPopulationMask(Result[] firstVariant) {
        var popMask = new BitSet(firstVariant.length);
        for (int i = 0; i < firstVariant.length; i++) {
            if (firstVariant[i] != null) {
                popMask.set(i);
            }
        }
        return popMask;
    }

    protected static Set<Map<Integer, Result>> resolveArgumentVariants(Component component, @NonNull Method method,
                                                                       List<EvalArguments> argumentVariants,
                                                                       boolean isStatic, Resolver resolver) {
        return argumentVariants.stream()/*.parallel()*/.map(arguments -> {
            var evalContextArgsVariants = resolveEvalContextArgsVariants(component, method, arguments, isStatic, resolver);
            return evalContextArgsVariants;
        }).flatMap(Collection::stream).collect(toLinkedHashSet());
    }

    private static Set<Map<Integer, Result>> resolveEvalContextArgsVariants(Component component, @NonNull Method method,
                                                                            EvalArguments arguments, boolean isStatic,
                                                                            Resolver resolver) {
        var argVariants = resolveEvalContextArgs(arguments, isStatic, resolver).stream().flatMap(evalContextArgs -> {
            int dimensions = evalContextArgs.values().stream()/*.parallel()*/
                    .map(r -> r instanceof Multiple ? ((Multiple) r).getResults().size() : 1)
                    .reduce(1, (l, r) -> l * r);
            if (dimensions == 1) {
                return of(evalContextArgs);
            } else {
                var evalContextArgsVariants = flatArgVariants(evalContextArgs, dimensions);
                return evalContextArgsVariants.stream();
            }
        }).collect(toLinkedHashSet());
        return argVariants;
    }

    private static Set<Map<Integer, Result>> flatArgVariants(Map<Integer, Result> evalContextArgs, int dimensions) {
        var evalContextArgsVariants = new LinkedHashSet<Map<Integer, Result>>();
        for (var d = 1; d <= dimensions; d++) {
            var variant = new LinkedHashMap<Integer, Result>();
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

    private static Set<Map<Integer, Result>> resolveEvalContextArgs(EvalArguments arguments, boolean isStatic, Resolver resolver) {
        return arguments.getArguments().isEmpty() ? Set.of() : arguments.getEval().withArgumentsStream().map(eval -> {
            var evalContextArgs = new HashMap<Integer, Result>();
            var args = arguments.getArguments();
            for (int i = 0; i < args.size(); i++) {
                var argument = args.get(i);
                Result resolved;
                try {
                    resolved = eval.resolve(argument, resolver);
                } catch (NotInvokedException e) {
                    if (resolver != null)
                        resolved = resolver.resolve(argument, e, eval);
                    else {
                        //log
                        return null;
                    }
                } catch (EvalException e) {
                    //log
                    return null;
                }
                evalContextArgs.put(isStatic ? i : i + 1, resolved);
            }
            return evalContextArgs;
        }).collect(toLinkedHashSet());
    }

    private static List<Result> resolveStoreInstructions(DelayLoadFromStore delay, Eval eval, Resolver resolver) {
        return delay.getStoreInstructions().stream()/*.parallel()*/
                .flatMap(storeResult -> resolveExpand(storeResult, eval, resolver).stream())
                .collect(toList());
    }

    private static List<Result> resolveExpand(Result storeResult, Eval eval, Resolver resolver) {
        return expand(eval.resolve(storeResult, resolver));
    }

    private static List<List<Result>> getWithoutNulls(Collection<List<Result>> combinedVariants) {
        var withoutNulls = combinedVariants.stream().filter(ll -> {
            return !ll.contains(null);
        }).collect(toList());
        if (withoutNulls.size() != combinedVariants.size()) {
            //log
        }
        return withoutNulls;
    }

    List<EvalArguments> evalArguments(CallPoint calledMethod) {
        var instructionHandle = calledMethod.getInstruction();
        var instruction = instructionHandle.getInstruction();

        var invokeInstruction = (InvokeInstruction) instruction;
        var constantPoolGen = this.getConstantPoolGen();
        if (calledMethod.isInvokeDynamic()) {
            var invokeDynamicArgumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
            var referenceKind = calledMethod.getReferenceKind();
            var removeCallObjectArg = referenceKind == REF_invokeSpecial
                    || referenceKind == REF_invokeVirtual
                    || referenceKind == REF_invokeInterface;
            var arguments = this.evalArguments(instructionHandle, invokeDynamicArgumentTypes.length);
            if (removeCallObjectArg) {
                arguments = arguments.stream().map(a -> {
                    var withoutCallObject = new ArrayList<>(a.getArguments());
                    withoutCallObject.remove(0);
                    return new EvalArguments(a.getEval(), withoutCallObject, a.getLastInstructions());
                }).collect(toList());
            }
            return arguments;
        } else {
            var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
            return evalArguments(instructionHandle, argumentTypes.length);
        }
    }

    private List<List<Result>> combineVariants(DelayInvoke invoke, List<Result> parameters,
                                               Set<InvokeBranch> thisMethodsBranches,
                                               Map<InvokeBranch, Map<Integer, List<Result>>> groupedParamsByThisMethodBranch) {
        var results = ofNullable(thisMethodsBranches).flatMap(Collection::stream).flatMap(methodBranch -> {
            var lists = combineVariants(invoke, methodBranch, new Result[parameters.size()], false, groupedParamsByThisMethodBranch);
            return lists.stream();
        }).collect(toList());
        return results;
    }

    private Set<List<Result>> combineVariants(DelayInvoke invoke, InvokeBranch branch, Result[] firstVariant,
                                              boolean canOverwrite, Map<InvokeBranch, Map<Integer, List<Result>>> groupedParamsByBranch
    ) {
        var popMask = initPopulationMask(firstVariant);
//        int cardinality = popMask.cardinality();
//        var full = cardinality == firstVariant.length;
//        if (full) {
//            return Set.of(Arrays.asList(firstVariant));
//        }

        var allVariants = new LinkedHashSet<List<Result>>();
        var paramIndexToVariantsMap = groupedParamsByBranch.getOrDefault(branch, Map.of());
        int dimensions = getDimensions(paramIndexToVariantsMap.values());
        var resolvedParamsVariants = flatResolvedParamsVariants(dimensions, paramIndexToVariantsMap);
        for (var resolvedParamsVariant : resolvedParamsVariants) {
            var variants = populateVariants(firstVariant, canOverwrite, resolvedParamsVariant, popMask);

//            cardinality = popMask.cardinality();
//            full = cardinality == firstVariant.length;
//            var partial = !full && cardinality > 0;


//            if (full) {
//            allVariants.addAll(variants.stream().map(a -> new ArrayList<>(Arrays.asList(a))).collect(toLinkedHashSet()));
//            } else if (partial && nextSize == 0) {
            allVariants.addAll(variants.stream().map(a -> new ArrayList<>(Arrays.asList(a))).collect(toLinkedHashSet()));
//            }
            var next = branch.getNext();
            int nextSize = next.size();
            for (int i = 0; i < nextSize; i++) {
                var invokeBranch = next.get(i);
                boolean firstBranch = i == 0;
                var nextVariants = variants.stream().flatMap(v -> combineVariants(invoke, invokeBranch, v.clone(),
                                true, groupedParamsByBranch).stream())
                        .collect(toLinkedHashSet());
//                var nextVariants = combineVariants(invoke, invokeBranch, firstVariant, groupedParamsByBranch);
                allVariants.addAll(nextVariants);
            }
        }
        return allVariants;
    }

    public ComponentKey getComponentKey() {
        return newComponentKey(getComponent());
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
            var storeResults = getStoreInstructionResults(instructionHandle, loadIndex);
            if (!storeResults.isEmpty()) {
                var description = instructionText + " from stored invocation";
                var storeInstructions = storeResults.stream()
                        .flatMap(storeResult -> storeResult.getFirstInstructions().stream())
                        .map(this::eval)
                        .collect(toList());
                var loadInstructionType = loadInstruction.getType(constantPoolGen);
                var first = storeInstructions.get(0);
                var expectedType = Optional.ofNullable(getType(first)).orElse(loadInstructionType);
                return delayLoadFromStored(description, instructionHandle, expectedType, this,
                        storeInstructions, (thisDelay, eval, resolver) -> {
                            state(this.equals(eval), "unexpected eval");
                            var resolved = resolveStoreInstructions(thisDelay, eval, resolver);
                            return collapse(resolved, this);
                        });
            }
            if (log.isTraceEnabled()) {
                log.trace("not found store for {} inside of {}.{}({})", name != null ? "'" + name + "' variable" : instructionText,
                        getTargetClass(getComponent()).getName(), getMethod().getName(), stringForLog(getMethod().getArgumentTypes()));
            }
            if (localVariable == null) {
                var argumentType = this.method.getArgumentTypes()[loadIndex - 1];
                return methodArg(this, loadIndex, null, argumentType, instructionHandle);
            } else {
                return methodArg(this, localVariable, instructionHandle);
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
                        ? variable(this, localVariable, instructionHandle)
                        : variable(this, localVarIndex, null, errType, instructionHandle);
            } else {
                //log
                //removed from stack
                var onSaveResult = evalPrev(instructionHandle);
                return evalPrev(onSaveResult);
            }
        } else if (instruction instanceof GETSTATIC) {
            var getStatic = (GETSTATIC) instruction;
            var fieldName = getStatic.getFieldName(constantPoolGen);
            var loadClassType = getStatic.getLoadClassType(constantPoolGen);
            var loadClass = getClassByName(loadClassType.getClassName());
            return getFieldValue(null, loadClass, fieldName, instructionHandle, instructionHandle, null, this);
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;
            var evalFieldOwnedObject = evalPrev(instructionHandle);
            var fieldName = getField.getFieldName(constantPoolGen);
            var lastInstruction = evalFieldOwnedObject.getLastInstruction();
            var relations = List.of(evalFieldOwnedObject);
            var fieldType = getField.getFieldType(constantPoolGen);
            return delay(instructionText, instructionHandle, lastInstruction, fieldType, this, relations,
                    (thisDelay, eval, unevaluatedHandler) -> {
                        //todo: must be touched all, not only the first one
                        var object = evalFieldOwnedObject.getValue(unevaluatedHandler, eval).get(0);
                        return getFieldValue(getTargetObject(object), getTargetClass(object), fieldName,
                                instructionHandle, lastInstruction, thisDelay, this);
                    });
        } else if (instruction instanceof CHECKCAST) {
            return evalPrev(instructionHandle);
        } else if (instruction instanceof InvokeInstruction) {
            return collapse(evalInvokes(instructionHandle, (InvokeInstruction) instruction), this);
        } else if (instruction instanceof ANEWARRAY) {
            var anewarray = (ANEWARRAY) instruction;
            var size = evalPrev(instructionHandle);
            var lastInstruction = size.getLastInstruction();
            var relations = List.of(size);
            var arrayType = anewarray.getType(constantPoolGen);
            return delay(instructionText, instructionHandle, lastInstruction, arrayType, this, relations,
                    (thisDelay, eval, resolver) -> {
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
            var element = evalPrev(instructionHandle);
            var index = evalPrev(element);
            var array = evalPrev(index);
            var lastInstruction = array.getLastInstruction();
            var relations = List.of(element, index, array);
            var arrayType = ((ArrayInstruction) instruction).getType(constantPoolGen);
            return delay(instructionText, instructionHandle, lastInstruction, arrayType, this,
                    relations, (thisDelay, eval, resolver) -> {
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
            var element = evalPrev(instructionHandle);
            var index = evalPrev(element);
            var array = evalPrev(index);
            var lastInstruction = array.getLastInstruction();
            var relations = List.of(element, index, array);
            var arrayType = ((ArrayInstruction) instruction).getType(constantPoolGen);
            //AALOAD
            return delay(instructionText, instructionHandle, lastInstruction, arrayType, this,
                    relations, (thisDelay, eval, resolver) -> {
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
            var arrayRef = evalPrev(instructionHandle);
            var relations = List.of(arrayRef);
            return delay(instructionText, instructionHandle, arrayRef.getLastInstruction(), Type.INT, this,
                    relations, (thisDelay, eval, resolver) -> {
                        state(this.equals(eval), "unexpected eval");
                        state(this.equals(eval), "unexpected eval");
                        return eval.resolve(arrayRef, resolver);
                    });
        } else if (instruction instanceof NEW) {
            var newInstance = (NEW) instruction;
            var loadClassType = newInstance.getLoadClassType(constantPoolGen);
            var newInstanceType = newInstance.getType(constantPoolGen);
            return delay(instructionText, instructionHandle, instructionHandle, newInstanceType, this,
                    List.of(), (thisDelay, eval, resolver) -> {
                        var type = getClassByName(loadClassType.getClassName());
                        return instantiateObject(instructionHandle, type, new Class[0], new Object[0], thisDelay, this);
                    });
        } else if (instruction instanceof DUP) {
            var dup = evalPrev(instructionHandle);
            return duplicate(instructionHandle, dup.getLastInstructions(), dup);
        } else if (instruction instanceof DUP2) {
//            return evalPrev(instructionHandle, resolver);
        } else if (instruction instanceof POP) {
            var onRemoves = getPrevs(instructionHandle);
            var results = onRemoves.stream().map(onRemove -> {
                //log removed
//            var prev = onRemove.getLastInstruction().getPrev();
                //todo on remove must produce stack
                var onRemoveInstruction = onRemove.getInstruction();
                var stackProducer = onRemoveInstruction instanceof StackProducer;
                if (!stackProducer) {
                    throw newInvalidEvalException("pop stack variable must be produced by prev instruction",
                            onRemoveInstruction, constantPoolGen.getConstantPool());
                }
                return evalPrev(onRemove);
            }).collect(toList());
            return collapse(results, this);
        } else if (instruction instanceof POP2) {
//            return evalPrev(instructionHandle, resolver);
        } else if (instruction instanceof ACONST_NULL) {
            return constant(null, ((ACONST_NULL) instruction).getType(constantPoolGen), instructionHandle,
                    instructionHandle, this, List.of()
            );
        } else if (instruction instanceof IfInstruction) {
            var args = new Result[consumeStack];
            for (var i = consumeStack - 1; i >= 0; --i) {
                args[i] = evalPrev(instructionHandle);
            }
            var prev = args.length > 0 ? evalPrev(args[0]) : evalPrev(instructionHandle);
            //now only positive scenario
            //todo need evaluate negative branch
            return prev;
        } else if (instruction instanceof ConversionInstruction) {
            //I2L,
            var conv = (ConversionInstruction) instruction;
            var convertTo = conv.getType(constantPoolGen);
            var convertedValueResult = evalPrev(instructionHandle);
            var lastInstruction = convertedValueResult.getLastInstruction();
            var relations = List.of(convertedValueResult);
            return delay(instructionText, instructionHandle, lastInstruction, convertTo, this, relations,
                    (thisDelay, eval, resolver) -> {
                        var values = convertedValueResult.getValue(resolver, eval);
                        var results = values.stream()
                                .map(value -> (Number) value)
                                .map(number -> convertNumberTo(number, convertTo))
                                .map(converted -> constant(converted, convertTo, instructionHandle,
                                        lastInstruction, this, singletonList(thisDelay)
                                ))
                                .collect(toList());
                        return collapse(results,
                                this);
                    });
        } else if (instruction instanceof ArithmeticInstruction) {
            var arith = (ArithmeticInstruction) instruction;
            var first = evalPrev(instructionHandle);
            var second = consumeStack == 2 ? evalPrev(first) : null;
            var lastInstruction = second != null ? second.getLastInstruction() : first.getLastInstruction();
            var relations = second != null ? List.of(first, second) : List.of(first);
            var arithType = arith.getType(constantPoolGen);
            return delay(instructionText, instructionHandle, lastInstruction, arithType, this, relations,
                    (thisDelay, eval, resolver) -> {
                        try {
                            var computed = computeArithmetic(arith, first, second, resolver, eval);
                            return constant(computed, arithType, instructionHandle, lastInstruction, this, asList(first, second));
                        } catch (EvalException e) {
                            return resolveOrThrow(thisDelay, resolver, e, eval);
                        }
                    });
        } else if (instruction instanceof ARETURN) {
            return evalPrev(instructionHandle);
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen.getConstantPool());
    }

    public Collection<Result> getStoreInstructionResults(InstructionHandle instructionHandle, int index) {
        var branches = tree.findNextBranchContains(instructionHandle.getPosition());
        var storeInstructions = getLastStoreInstructionPerBranch(branches, index, instructionHandle.getPosition());
        var results = storeInstructions.stream()
                .map(storeInstrHandle -> evalPrev(storeInstrHandle))
                .collect(toLinkedHashSet());
        return results;
    }

    public List<DelayInvoke> evalInvokes(InstructionHandle instructionHandle, InvokeInstruction instruction) {
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var evalArguments = evalArguments(instructionHandle, argumentTypes.length);

        return evalArguments.stream().flatMap(arguments -> {
            if (log.isTraceEnabled()) {
                log.trace("eval {}", getInstructionString(instructionHandle, constantPoolGen));
            }
            if (instruction instanceof INVOKEVIRTUAL || instruction instanceof INVOKEINTERFACE) {
                var evalInvokeObjects = evalInvokeObject(instruction, arguments);
                return evalInvokeObjects.stream().map(evalInvokeObject -> evalInvokeVirtual(
                        instructionHandle, instruction, evalInvokeObject, argumentTypes, arguments));
            } else if (instruction instanceof INVOKEDYNAMIC) {
                return Stream.of(evalInvokeDynamic(instructionHandle, instruction, argumentTypes, arguments));
            } else if (instruction instanceof INVOKESTATIC) {
                return Stream.of(evalInvokeStatic(instructionHandle, instruction, argumentTypes, arguments));
            } else if (instruction instanceof INVOKESPECIAL) {
                var evalInvokeObjects = evalInvokeObject(instruction, arguments);
                return evalInvokeObjects.stream().map(evalInvokeObject->evalInvokeSpecial(
                        instructionHandle, instruction, this.constantPoolGen, evalInvokeObject, argumentTypes, arguments));
            }
            throw newUnsupportedEvalException(instruction, constantPoolGen.getConstantPool());
        }).collect(toList());
    }

    private DelayInvoke evalInvokeSpecial(InstructionHandle instructionHandle, InvokeInstruction instruction,
                                          ConstantPoolGen constantPoolGen, EvalInvokeObject invokeObject,
                                          Type[] argumentTypes, EvalArguments arguments) {
        var methodName = instruction.getMethodName(constantPoolGen);
        var invokeSpec = (INVOKESPECIAL) instruction;
        var loadClassType = invokeSpec.getLoadClassType(constantPoolGen);
        var className = instruction.getClassName(constantPoolGen);
        return delayInvoke(instructionHandle, loadClassType, this, invokeObject, className, methodName,
                arguments, (thisDelay, eval, resolver) -> {
                    var argumentClasses = toClasses(argumentTypes);
                    return callInvokeSpecial(thisDelay, argumentClasses, eval, true, resolver,
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
                                         InvokeInstruction instruction, Type[] argumentTypes, EvalArguments arguments) {
        var methodName = instruction.getMethodName(constantPoolGen);
        var invokeResultType = instruction.getType(constantPoolGen);
        var className = instruction.getClassName(constantPoolGen);
        return delayInvoke(instructionHandle, invokeResultType, this, null,
                className, methodName, arguments, (thisDelay, eval, resolver) -> {
                    var argumentClasses = toClasses(argumentTypes);
                    return callInvokeStatic(thisDelay, argumentClasses, eval, true,
                            resolver, (parameters, lastInstruction) -> {
                                var objectClass = toClass(className);
                                return callMethod(null, objectClass, methodName, argumentClasses,
                                        getValues(parameters), instructionHandle, lastInstruction,
                                        invokeResultType, constantPoolGen, thisDelay, parameters, this);
                            });
                });
    }

    private DelayInvoke evalInvokeDynamic(InstructionHandle instructionHandle, InvokeInstruction instruction,
                                          Type[] argumentTypes, EvalArguments arguments) {
        var invokeResultType = instruction.getType(constantPoolGen);
        var bootstrapMethodAndArguments = getBootstrapMethodHandlerAndArguments(
                (INVOKEDYNAMIC) instruction, bootstrapMethods, constantPoolGen);
        var sourceMethodInfo = bootstrapMethodAndArguments.getSourceMethodInfo();
        var className = sourceMethodInfo != null ? sourceMethodInfo.getClassName() : null;
        var methodName = sourceMethodInfo != null ? sourceMethodInfo.getName() : null;
        return delayInvoke(instructionHandle, invokeResultType, this, null, className, methodName,
                arguments, (thisDelay, eval, resolver) -> {
                    var argumentClasses = toClasses(argumentTypes);
                    return callInvokeDynamic(thisDelay, argumentClasses, eval, true, resolver,
                            (parameters, lastInstruction) -> {
                                var handler = bootstrapMethodAndArguments.getHandler();
                                var methodArguments = bootstrapMethodAndArguments.getBootstrapMethodArguments();
                                var callSite = getCallSite(handler, methodArguments);
                                return callBootstrapMethod(getValues(parameters), instructionHandle, lastInstruction,
                                        invokeResultType, this, parameters, callSite);
                            });
                });
    }

    private DelayInvoke evalInvokeVirtual(InstructionHandle instructionHandle, InvokeInstruction instruction,
                                          EvalInvokeObject invokeObject, Type[] argumentTypes, EvalArguments arguments) {
        var methodName = instruction.getMethodName(constantPoolGen);
        var invokeResultType = instruction.getType(constantPoolGen);
        var className = instruction.getClassName(constantPoolGen);
        return delayInvoke(instructionHandle, invokeResultType, this, invokeObject, className, methodName,
                arguments, (thisDelay, eval, resolver) -> {
                    var argumentClasses = toClasses(argumentTypes);
                    return callInvokeVirtual(thisDelay, argumentClasses, eval, true, resolver,
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
        var parameterVariants = resolveInvokeParameters(invoke, resolver);
        if (parameterVariants.isEmpty()) {
            throw new NotInvokedException(noParameterVariants, invoke, invoke.getParameters());
        }
        var lastInstruction = invoke.getLastInstruction();
        var results = callWithParameterVariants(invoke, parameterClasses, resolver, call,
                parameterVariants, lastInstruction);
        var unresolved = results.get(false).stream().map(p -> p.exception).collect(toList());
        var resolved = results.get(true).stream().map(p -> p.result).collect(toList());
        if (resolved.isEmpty() && throwNoCall) {
            throw new NotInvokedException(noCalls, unresolved, invoke);
        }
        return collapse(resolved, this);
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
//        var synthetic = resolver.getClass().isSynthetic();
//        assertFalse(synthetic, "unsupported synthetic resolver class for caching: " + resolver.getClass());
        var key = new CallCacheKey(current, callParameters, lastInstruction.getInstruction());
        if (callCache != null) {
            var cached = callCache.get(key);
            if (cached != null) {
                log.trace("get cached call result, call '{}', result '{}'", key, cached);
                return cached;
            }
        }

        Result callResult;
        try {
            callResult = call(current, lastInstruction, resolver, callParameters, call);
            if (callCache != null) {
                log.trace("no cached call result, call '{}', result '{}'", key, callResult);
                var exists = callCache.get(key);
                if (exists == null || callResult != exists) {
                    if (exists instanceof Multiple) {
                        var multiple = (Multiple) exists;
                        var results = new ArrayList<>(multiple.getResults());
                        results.add(callResult);
                        callResult = multiple(results, multiple.getEval());
                    } else if (exists != null && !exists.equals(callResult)) {
                        callResult = multiple(List.of(exists, callResult), callResult.getEval());
                    }
                    callCache.put(key, callResult);
                }
            }
        } catch (RuntimeException e) {
            if (callCache != null) {
                log.trace("cache error call result, call '{}', error '{}'", key, e.getMessage(), e);
                callCache.put(key, e);
            }
            throw e;
        }
        return callResult;
    }

    private Result call(Delay invoke, InstructionHandle lastInstruction, Resolver resolver,
                        List<List<ParameterValue>> parametersVariants,
                        BiFunction<List<ParameterValue>, InstructionHandle, Result> call
    ) throws NotInvokedException {

        var values = new ArrayList<Result>();
        var unresolvedVars = new ArrayList<UnresolvedVariableException>();
        var errors = new ArrayList<EvalException>();

        for (var parameterValues : parametersVariants) {
            try {
                var apply = call.apply(parameterValues, lastInstruction);
                values.add(apply);
            } catch (UnresolvedVariableException e) {
                unresolvedVars.add(e);
                errors.add(e);
            } catch (EvalException e) {
                errors.add(e);
            }
        }

        if (!values.isEmpty()) {
            return collapse(values, this);
        } else {
            var unresolvedVarsEmpty = unresolvedVars.isEmpty();
            var reason = unresolvedVarsEmpty ? badEval : unresolvedVariables;
            var notCallException = new NotInvokedException(reason, errors, invoke);
            if (!errors.isEmpty() && resolver != null) {
                log.trace("call error of {}", invoke, notCallException);
                return resolver.resolve(invoke, notCallException, this);
            } else {
                //log
                throw notCallException;
            }
        }
    }

    private List<List<ParameterValue>> resolveCallParameters(Delay current, List<Result> parameters,
                                                             Class<?>[] parameterClasses, Resolver resolver) {
        var size = parameters.size();
        var variants = new ParameterVariants[size];
        for (var i = 0; i < size; i++) {
            var parameter = parameters.get(i);
            ParameterVariants parameterVariants;
            try {
                var value = parameter.getValue(resolver);
                parameterVariants = new ParameterVariants(parameter, i, value, null);
            } catch (UnresolvedVariableException e) {
                var unresolved = e.getResult();
                var contextComponent = getComponent();
                var sameLevel = isSameLevel(unresolved, contextComponent);
                if (!sameLevel && resolver != null) {
                    //log
                    parameterVariants = resolveParameter(i, parameter, parameterClasses[i], e, resolver);
                } else {
                    throw e;
                }
            } catch (EvalException e) {
                if (resolver != null) {
                    var resultException = new UnresolvedResultException("unresolved parameter by resolver", e, parameter);
                    parameterVariants = resolveParameter(i, parameter, parameterClasses[i], resultException, resolver);
                } else {
                    throw e;
                }
            }
            variants[i] = parameterVariants;
        }

        var parameterVariants = asList(variants);
        int dimensions = getDimensions(parameterVariants);

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

    private ParameterVariants resolveParameter(int i, Result parameter, Class<?> parameterClass,
                                               UnresolvedResultException e, Resolver resolver) {
        ParameterVariants parameterVariants;
        var resolved = resolver.resolve(parameter, e, this);
        if (resolved.isResolved()) {
            var resolvedVariants = resolved.getValue(resolver);
            var normalizedClassVariants = normalizeClassOfObjects(resolvedVariants, parameterClass);
            parameterVariants = new ParameterVariants(parameter, i, normalizedClassVariants, null);
        } else {
            //log
            parameterVariants = new ParameterVariants(parameter, i, null, e);
        }
        return parameterVariants;
    }

    //todo need to migrates on evalPrev in place of getPrev
    public List<EvalInvokeObject> evalInvokeObject(InvokeInstruction invokeInstruction, EvalArguments evalArguments) {
        final InstructionHandle firstInstruction, lastInstruction;
//        final List<Result> objectCallResults;
        var lastArgInstructions = evalArguments.getLastInstructions();
        var methodName = invokeInstruction.getMethodName(constantPoolGen);
        if (invokeInstruction instanceof INVOKESPECIAL && "<init>".equals(methodName)) {
            var lastArgInstructionsVariants = lastArgInstructions.stream()
                    .map(lastArgInstruction -> getPrevs(lastArgInstruction).stream()
                            .flatMap(prev -> prev.getInstruction() instanceof DUP ? getPrevs(prev).stream() : Stream.of(prev))
                            .map(prev -> prev.getInstruction() instanceof NEW ? prev : lastArgInstruction).collect(toList()))
                    .collect(toList());
            return lastArgInstructionsVariants.stream().map(lastArgInstructions1 ->
                    new EvalInvokeObject(lastArgInstructions1, lastArgInstructions1)).collect(toList());
        } else if (invokeInstruction instanceof INVOKESPECIAL
                || invokeInstruction instanceof INVOKEVIRTUAL
                || invokeInstruction instanceof INVOKEINTERFACE
        ) {
            var objectCallResults = lastArgInstructions.stream().map(this::evalPrev).collect(toList());
            return objectCallResults.stream().map(objectCallResult -> new EvalInvokeObject(objectCallResult)).collect(toList());
        } else {
            var objectCallResults = lastArgInstructions.stream().map(this::evalPrev).collect(toList());
            return objectCallResults.stream().map(objectCallResult -> new EvalInvokeObject(lastArgInstructions, lastArgInstructions)).collect(toList());
        }
    }

    public Collection<List<Result>> resolveInvokeParameters(DelayInvoke invoke, Resolver resolver) {
        var parameters = invoke.getParameters();

        if (parameters.isEmpty()) {
            return getResolvedParameters(parameters);
        }
        var allResolved = parameters.stream().allMatch(Result::isResolved);
        if (allResolved) {
            return getResolvedParameters(parameters);
        }

        //inside a call point
        var parameterVariants = parameters.stream().map(parameter -> {
            return resolveExpand(parameter, resolver);
        }).collect(toList());

        int dimensions = getDimensions(parameterVariants);
        switch (dimensions) {
            case 1:
            case 2:
            case 3:
            case 5:
            case 7:
            case 11:
            case 13:
                List<List<Result>> groupedVariants1;
                if ("exchange".equals(invoke.getMethodName())) {
                    groupedVariants1 = groupByInvokeBranchResolvedParamVariants(invoke, parameterVariants, parameters);
                }
                var resolvedVariants = flatResolvedVariants(dimensions, parameterVariants, parameters);
                return resolvedVariants;
            default:
                var groupedVariants = groupByInvokeBranchResolvedParamVariants(invoke, parameterVariants, parameters);
                return groupedVariants;
        }
    }

    private List<List<Result>> groupByInvokeBranchResolvedParamVariants(DelayInvoke invoke, List<List<Result>> parameterVariants, List<Result> parameters) {
        var resolvedParamVariants = new ArrayList<List<Result>>();
        var roots = new HashMap<Class<?>, Map<Method, Set<InvokeBranch>>>();
        var groupedParamsByClassMethodBranch = groupParamsByClassMethodBranch(parameterVariants, roots);

        var component = this.getComponent();

        var aClass1 = component.getType();
        var method = this.getMethod();

        var externalRoots = new HashMap<>(roots);

        var thisClassMethods = externalRoots.get(aClass1);
        var thisMethodsBranches = thisClassMethods != null ? thisClassMethods.remove(method) : null;
        if (thisClassMethods != null && thisClassMethods.isEmpty()) {
            externalRoots.remove(aClass1);
        }

        var groupedParamsByThisClassMethodBranch = groupedParamsByClassMethodBranch.getOrDefault(aClass1, Map.of());
        var groupedParamsByThisMethodBranch = groupedParamsByThisClassMethodBranch.getOrDefault(method, Map.of());

        var results = combineVariants(invoke, parameters, thisMethodsBranches, groupedParamsByThisMethodBranch);

        if (externalRoots.isEmpty()) {
            var withoutNulls = getWithoutNulls(results);
            resolvedParamVariants.addAll(withoutNulls);
        } else {
            if (results.isEmpty()) {
                //no results it this method
                results = List.of(asList(new Result[parameters.size()]));
            }

            for (var externalClass : externalRoots.keySet()) {
                var externalMethodsBranches = externalRoots.get(externalClass);
                for (var externalMethod : externalMethodsBranches.keySet()) {
                    var externalMethodBranches = externalMethodsBranches.get(externalMethod);
                    for (var externalBranch : externalMethodBranches) {
                        results = results instanceof LinkedList ? results : new LinkedList<List<Result>>(results);
                        for (var iterator = results.listIterator(); iterator.hasNext(); ) {
                            var firstVariant = iterator.next();
                            var firstVariant1 = firstVariant.toArray(new Result[0]);
                            var groupedParamsByMethodBranch = groupedParamsByClassMethodBranch.get(externalClass);
                            var groupedParamsByBranch = groupedParamsByMethodBranch.get(externalMethod);
                            var resultsVariants = combineVariants(invoke, externalBranch, firstVariant1, false, groupedParamsByBranch);
                            if (!resultsVariants.isEmpty()) {
                                iterator.remove();
                                for (var resultsVariant : resultsVariants) {
                                    iterator.add(resultsVariant);
                                }
                            }
                        }
                    }
                }
            }

            var withoutNulls = getWithoutNulls(results);
//            resolvedParamVariants.addAll(withoutNulls);

//            var combinedVariants = results.stream().flatMap(firstVariant -> {
//                return externalRoots.entrySet().stream().flatMap(e -> {
//                    var aClass = e.getKey();
//                    var methodsBranches = e.getValue();
//                    return methodsBranches.entrySet().stream().flatMap(methodBranches -> {
//                        var firstVariant1 = firstVariant.toArray(new Result[0]);
//                        var groupedParamsByMethodBranch = groupedParamsByClassMethodBranch.get(aClass);
//                        var groupedParamsByBranch = groupedParamsByMethodBranch.get(methodBranches.getKey());
//                        Stream<List<Result>> listStream = combineVariants(methodBranches.getValue(), firstVariant1, groupedParamsByBranch);
//                        return listStream;
//                    });
//                });
//            }).collect(toLinkedHashSet());
//
//            var withoutNulls2 = getWithoutNulls(combinedVariants);

            resolvedParamVariants.addAll(withoutNulls);
        }
        return resolvedParamVariants;
    }

    private Map<Class<?>, Map<Method, Map<InvokeBranch, Map<Integer, List<Result>>>>> groupParamsByClassMethodBranch(
            List<List<Result>> parameterVariants, Map<Class<?>, Map<Method, Set<InvokeBranch>>> roots
    ) {
        var grouped = new HashMap<Class<?>, Map<Method, Map<InvokeBranch, Map<Integer, List<Result>>>>>();
        for (int index = 0; index < parameterVariants.size(); index++) {
            var parameterVariant = parameterVariants.get(index);
            for (var parameter : parameterVariant) {
                var eval = parameter.getEval();
//                state(this.equals(eval), "unexpected eval");
                var root = eval.getTree();
                var aClass = eval.getComponent().getType();
                var method = eval.getMethod();
                roots.computeIfAbsent(aClass, k -> new HashMap<>()).computeIfAbsent(method, k -> new LinkedHashSet<>()).add(root);
                populateBranches(parameter, grouped, aClass, method, index, root.findNextBranchContains(parameter.getFirstInstruction().getPosition()));
            }
        }
        for (var aClass : new ArrayList<>(grouped.keySet())) {
            var methods = grouped.get(aClass);
            for (var method : methods.keySet()) {
                var branches = methods.get(method);
                for (var branch : new ArrayList<>(branches.keySet())) {
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
//                                        state(this.equals(eval), "unexpected eval");
                                        var root = eval.getTree();
                                        var aClass1 = eval.getComponent().getType();
                                        var method1 = eval.getMethod();
                                        roots.computeIfAbsent(aClass1, k -> new HashMap<>())
                                                .computeIfAbsent(method1, k -> new LinkedHashSet<>())
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
        var isStatic = (declaredMethod.getModifiers() & STATIC) > 0;
        if (!isStatic && object == null) {
            throw new IllegalInvokeException(new NullPointerException("callable object is null"),
                    new MethodInvokeContext(declaredMethod, object, args), invokeInstruction, invoke);
        }
        Object result;
        try {
            result = declaredMethod.invoke(object, args);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            //log
            throw new IllegalInvokeException(e, new MethodInvokeContext(declaredMethod, object, args), invokeInstruction, invoke);
        } catch (InvocationTargetException e) {
            //log
            throw new IllegalInvokeException(e.getCause(), new MethodInvokeContext(declaredMethod, object, args), invokeInstruction, invoke);
        } catch (NullPointerException e) {
            throw e;
        }
        if (log.isDebugEnabled()) {
            log.debug("{}, success, method '{}.{}', result: {}, instruction {}", msg, objectClass.getName(), methodName,
                    result, EvalUtils.toString(invokeInstruction, constantPoolGen));
        }
        return invoked(result, expectedType, invokeInstruction, lastInstruction, null, this, parameters);
    }

    public List<Result> resolveExpand(Result value, Resolver resolver) {
        return expand(resolve(value, resolver));
    }

    public Result resolve(Result value, Resolver resolver) {
        var arguments = this.getArguments();
        if (arguments == null) {
            //resolve for all arg variants
            var results = withArgumentsStream().map(eval -> {
                var resolvedVariant = eval.resolve(value, resolver);
                return resolvedVariant;
            }).collect(toList());
            return collapse(results, this);
        } else {
            //resolve for the current arg variant
            Result result;
            if (value instanceof Variable && ((Variable) value).getVarType() == MethodArg) {
                var variable = (Variable) value;
                var varIndex = variable.getIndex();
                var argResult = arguments.get(varIndex);
                if (argResult != null) {
                    state(argResult.isResolved(), "arg must be resolved," + varIndex + ", " + argResult);
                    //log
                    return argResult;
                } else {
                    return stub(variable, resolver, this);
                }
            } else if (value instanceof Delay) {
                try {
                    var delay = (Delay) value;
                    var delayEval = delay.getEval();
                    state(this.equals(delayEval), "unexpected eval");
                    result = delay.getDelayed(this, resolver);
                } catch (UnresolvedVariableException e) {
                    result = resolveOrThrow(value, resolver, e, this);
                }
            } else if (value instanceof Duplicate) {
                result = resolve(((Duplicate) value).getOnDuplicate(), resolver);
            } else if (value instanceof Multiple) {
                var results = ((Multiple) value).getResults().stream()
                        .map(r -> resolve(r, resolver))
                        .collect(toList());
                result = multiple(results, this);
            } else {
                result = value;
            }
            return result;
        }
    }

    public Stream<Eval> withArgumentsStream() {
        var argumentVariants = getArgumentVariants();
        return argumentVariants.isEmpty()
                ? Stream.of(this.withArguments(Map.of()))
                : argumentVariants.stream()/*.parallel()*/.map(this::withArguments);
    }

    private Eval withArguments(Map<Integer, Result> arguments) {
        return new Eval(component, javaClass, method, bootstrapMethods, callCache, argumentVariants, arguments, tree);
    }

    public Eval withArguments(int firstIndex, List<Result> arguments) {
        var argumentsMap = new LinkedHashMap<Integer, Result>();
        for (int i = 0; i < arguments.size(); i++) {
            argumentsMap.put(i + firstIndex, arguments.get(i));
        }
        return this.withArguments(argumentsMap);
    }

    public Eval withArgumentVariants(Set<Map<Integer, Result>> argumentVariants) {
        return new Eval(component, javaClass, method, bootstrapMethods, callCache, argumentVariants, null, tree);
    }

    public List<EvalArguments> evalArguments(InstructionHandle instructionHandle, int argumentsAmount) {
        return evalArguments(instructionHandle, argumentsAmount, new Result[argumentsAmount]);
    }

    private List<EvalArguments> evalArguments(InstructionHandle instructionHandle, int argumentsAmount, Result[] values) {
        var result = new ArrayList<EvalArguments>();
        var current = instructionHandle;
        for (int i = argumentsAmount; i > 0; i--) {
            var prevs = evalPrevs(current);
            var valIndex = i - 1;
            var firstPrev = prevs.get(0);
            values[valIndex] = firstPrev;
            if (prevs.size() > 1) {
                for (var j = 1; j < prevs.size(); j++) {
                    var valuesFork = values.clone();
                    var nextPrev = prevs.get(j);
                    valuesFork[valIndex] = nextPrev;

                    var prevLastInstructions = nextPrev.getLastInstructions();
                    var instruction1 = prevLastInstructions.get(0);

                    var evalArguments = evalArguments(instruction1, i - 1, valuesFork);
                    result.addAll(evalArguments);
                }
            }
            var firstPrevLastInstructions = firstPrev.getLastInstructions();
            current = firstPrevLastInstructions.get(0);
        }
        result.add(new EvalArguments(this, asList(values), current));
        return result;
    }

    public List<InstructionHandle> getPrevs(InstructionHandle instructionHandle) {
        var prevs = new ArrayList<InstructionHandle>();
        var prev1 = instructionHandle.getPrev();
        var targeterRequired = false;
        if (prev1 != null) {
            var prevInstruction = prev1.getInstruction();
            if (prevInstruction instanceof GotoInstruction) {
                var goTo = (GotoInstruction) prevInstruction;
                var target = goTo.getTarget();
                int targetPosition = target.getPosition();
                if (targetPosition < prev1.getPosition()) {
                    //go back, loop
                    prev1 = prev1.getPrev();
                } else if (targetPosition > instructionHandle.getPosition()) {
                    targeterRequired = true;
                    prev1 = null;
                }
            } else if (prevInstruction instanceof BranchInstruction) {
                //if switch
                var condition = prev1;
                var conditionInstruction = condition.getInstruction();
                var skip = conditionInstruction.consumeStack(constantPoolGen);
                var skipped = skip;
                prev1 = condition.getPrev();
                while (skipped > 0) {
                    prevInstruction = prev1.getInstruction();
                    var plusSkip = prevInstruction.consumeStack(constantPoolGen);
                    prev1 = prev1.getPrev();
                    skip += plusSkip;
                    skipped += plusSkip;
                    skipped--;
                }
            }
            if (prev1 != null) {
                prevs.add(prev1);
            }
        }

        var targeters = instructionHandle.getTargeters();
        var fork = targeters.length > 0;
        state(!targeterRequired || fork, "targeter required for " + instructionHandle + ", " + method.getName() + "\n" + method.getCode());
        if (fork) {
            for (var targeter : targeters) {
                if (targeter instanceof BranchInstruction) {
                    var offset = ((BranchInstruction) targeter).getIndex();
                    var isGoto = targeter instanceof GotoInstruction;
                    var isLoop = offset < 0 && isGoto;
                    if (!isLoop) {
                        var targetPosition = instructionHandle.getPosition() - offset;
                        var branch = instructionHandle.getPrev();
                        while (branch != null && branch.getPosition() != targetPosition) {
                            branch = branch.getPrev();
                        }
                        if (branch != null) {
                            var targetInstruction = branch.getInstruction();
                            var skip = targetInstruction.consumeStack(constantPoolGen);
                            var prev2 = branch.getPrev();
                            var skipped = skip;
                            while (skipped > 0) {
                                prev2 = prev2.getPrev();
                                var plus = prev2.getInstruction().consumeStack(constantPoolGen);
                                skip += plus;
                                skipped += plus;
                                skipped--;
                            }
                            prevs.add(prev2);
                        }
                    }
                }
            }
        }

        prevs.sort(comparingInt(InstructionHandle::getPosition));

        return prevs;
    }

    @Deprecated
    public Result evalPrev(InstructionHandle instructionHandle) {
        var collect = evalPrevs(instructionHandle);
        return collapse(collect, this);
    }

    public List<Result> evalPrevs(Collection<InstructionHandle> instructionHandles) {
        return instructionHandles.stream().map(this::evalPrevs).flatMap(Collection::stream).collect(toList());
    }

    public List<Result> evalPrevs(InstructionHandle instructionHandle) {
        return getPrevs(instructionHandle).stream().map(this::eval).collect(toList());
    }

    @Deprecated
    public Result evalPrev(Result result) {
        if (result instanceof Duplicate) {
            return ((Duplicate) result).getOnDuplicate();
        } else {
            return multiple(evalPrevs(result.getLastInstruction()), result.getEval());
        }
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

                @Override
                public void put(CallCacheKey key, RuntimeException exception) {

                }
            };
        }

        static CallCache newCallCache() {
            var grainedCache = new ConcurrentHashMap<ComponentKey, Map<Method, Map<Instruction, Map<CallCacheKey, Result>>>>();
            return new CallCache() {
                @Override
                public Result get(CallCacheKey key) {
//                    var exception = error.get(key);
//                    if (exception != null) {
//                        if (ignoreErrorCached) {
//                            return null;
//                        }
//                        throw exception;
//                    }
                    var call = key.getCall();
                    var eval = call.getEval();
                    var componentKey = eval.getComponentKey();
                    var method1 = eval.getMethod();
                    var instruction = call.getFirstInstruction().getInstruction();
                    var perMethod = grainedCache.computeIfAbsent(componentKey, k -> new ConcurrentHashMap<>());
                    var perInstruction = perMethod.computeIfAbsent(method1, k -> new ConcurrentHashMap<>());
                    var perKey = perInstruction.computeIfAbsent(instruction, k -> new ConcurrentHashMap<>());
                    return perKey.get(key);
//                    return success.get(key);
                }

                @Override
                public void put(CallCacheKey key, Result result) {
                    var call = key.getCall();
                    var eval = call.getEval();
                    var componentKey = eval.getComponentKey();
                    var method1 = eval.getMethod();
                    var instruction = call.getFirstInstruction().getInstruction();
                    var perMethod = grainedCache.computeIfAbsent(componentKey, k -> new ConcurrentHashMap<>());
                    var perInstruction = perMethod.computeIfAbsent(method1, k -> new ConcurrentHashMap<>());
                    var perKey = perInstruction.computeIfAbsent(instruction, k -> new ConcurrentHashMap<>());
                    perKey.put(key, result);
//                    success.put(key, result);
                }

                @Override
                public void put(CallCacheKey key, RuntimeException exception) {
                }
            };
        }

        Result get(CallCacheKey key);

        void put(CallCacheKey key, Result result);

        void put(CallCacheKey key, RuntimeException exception);
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
    public static class EvalInvokeObject {
        List<InstructionHandle> firstInstructions;
        List<InstructionHandle> lastInstructions;
        Result object;

        public EvalInvokeObject(Result object) {
            this.object = object;
            firstInstructions = object.getFirstInstructions();
            lastInstructions = object.getLastInstructions();
        }

        public EvalInvokeObject(List<InstructionHandle> firstInstructions, List<InstructionHandle> lastInstructions) {
            this.object = null;
            this.firstInstructions = firstInstructions;
            this.lastInstructions = lastInstructions;
        }
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
        Eval eval;
        List<Result> arguments;
        List<InstructionHandle> lastInstructions;

        public EvalArguments(Eval eval, List<Result> arguments, InstructionHandle lastArgInstruction) {
            this(eval, arguments, getInstructions(lastArgInstruction));
        }

        public EvalArguments(Eval eval, List<Result> arguments, List<InstructionHandle> lastInstructions) {
            this.eval = eval;
            this.arguments = arguments;
            Result lastArg = !arguments.isEmpty() ? arguments.get(0) : null;
            this.lastInstructions = lastArg != null ? lastArg.getLastInstructions() : lastInstructions;
        }

        @Override
        public String toString() {
            return "arguments" + arguments;
        }
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    class ParameterVariants {
        Result parameter;
        int index;
        List<Object> values;
        UnresolvedResultException exception;
    }
}
