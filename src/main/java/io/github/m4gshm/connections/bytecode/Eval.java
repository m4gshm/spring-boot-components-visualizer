package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.client.JmsOperationsUtils;
import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.util.*;

import static io.github.m4gshm.connections.Utils.classByName;
import static io.github.m4gshm.connections.bytecode.Eval.Result.Status.notFound;
import static io.github.m4gshm.connections.bytecode.Eval.Value.constant;
import static io.github.m4gshm.connections.bytecode.EvalException.newInvalidEvalException;
import static io.github.m4gshm.connections.bytecode.EvalException.newUnsupportedEvalException;
import static io.github.m4gshm.connections.bytecode.Eval.Result.success;
import static io.github.m4gshm.connections.bytecode.EvalUtils.*;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.Map.entry;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.of;
import static java.util.stream.Stream.ofNullable;
import static lombok.AccessLevel.PRIVATE;

@Slf4j
@Data
@ToString(onlyExplicitlyIncluded = true)
public class Eval {
    private final ConfigurableApplicationContext context;
    private final Object componentInstance;
    @ToString.Include
    private final String componentName;
    private final Class<?> componentType;
    private final ConstantPoolGen constantPoolGen;
    private final BootstrapMethods bootstrapMethods;
    @ToString.Include
    private final Method method;
    private final Collection<Component> components;

    public static ArrayList<Object> getInvokeArgs(Object object, Object[] arguments) {
        var invokeArgs = new ArrayList<>(arguments.length);
        invokeArgs.add(object);
        invokeArgs.addAll(asList(arguments));
        return invokeArgs;
    }

    public static Object defVal(Class<?> type) {
        if (type == null) {
            return null;
        } else if (void.class.equals(type)) {
            return null;
        } else if (boolean.class.equals(type)) {
            return false;
        } else if (byte.class.equals(type)) {
            return (byte) 0;
        } else if (short.class.equals(type)) {
            return (short) 0;
        } else if (int.class.equals(type)) {
            return 0;
        } else if (long.class.equals(type)) {
            return 0L;
        } else if (float.class.equals(type)) {
            return 0F;
        } else if (double.class.equals(type)) {
            return 0D;
        } else if (char.class.equals(type)) {
            return (char) 0;
        } else if (String.class.equals(type)) {
            return "";
        } else if (Collection.class.equals(type) || List.class.equals(type)) {
            return List.of();
        } else if (Set.class.equals(type)) {
            return Set.of();
        } else if (Map.class.equals(type)) {
            return Map.of();
        } else {
            return null;
        }
    }

    public static Value resolveMethodArgument(Value arg, List<Value> variant) {
        if (arg instanceof Value.MethodArgument) {
            var methodArgument = (Value.MethodArgument) arg;
            var localVariable = methodArgument.getLocalVariable();
            var i = localVariable.getIndex() - 1;
            return variant.get(i);
        }
        return arg;
    }

    private static Class<?> getCalledMethodClass(CallPoint calledMethodInsideDependent) {
        var ownerClass = calledMethodInsideDependent.getOwnerClass();
        var ownerClassName = calledMethodInsideDependent.getOwnerClassName();
        Class<?> calledMethodClass = null;
        try {
            calledMethodClass = ownerClass == null ? classByName(ownerClassName) : ownerClass;
        } catch (ClassNotFoundException e) {
            log.debug("getArgumentsVariant", e);
        }
        return calledMethodClass;
    }

    public Result eval(InstructionHandle instructionHandle) {
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            var value = ldc.getValue(constantPoolGen);
            return success(constant(value), instructionHandle, instructionHandle);
        } else if (instruction instanceof LoadInstruction) {
            var aload = (LoadInstruction) instruction;
            var aloadIndex = aload.getIndex();
            var localVariableTable = of(this.method.getLocalVariableTable().getLocalVariableTable())
                    .collect(groupingBy(LocalVariable::getIndex));
            var localVariables = localVariableTable.getOrDefault(aloadIndex, List.of());
            var position = instructionHandle.getPosition();

            var localVariable = localVariables.stream().filter(variable -> {
                int startPC = variable.getStartPC();
                var endPC = startPC + variable.getLength();
                return startPC <= position && position <= endPC;
            }).findFirst().orElseGet(() -> {
                if (localVariables.isEmpty()) {
                    log.warn("no matched local variables for instruction {} ", instructionHandle);
                    return null;
                }
                return localVariables.get(0);
            });

            var name = localVariable != null ? localVariable.getName() : null;
            if ("this".equals(name)) {
                return success(constant(componentInstance), instructionHandle, instructionHandle);
            }

            var prev = instructionHandle.getPrev();
            var aStoreResults = new ArrayList<Value>(localVariables.size());
            while (prev != null) {
                if (prev.getInstruction() instanceof ASTORE) {
                    var astore = (ASTORE) prev.getInstruction();
                    if (astore.getIndex() == aloadIndex) {
                        var storedInLocal = eval(prev);
                        var result = storedInLocal.getFirstValue();
                        aStoreResults.add(result);
                        prev = prev.getPrev();
                    }
                }
                prev = prev.getPrev();
            }
            if (!aStoreResults.isEmpty()) {
                return success(aStoreResults, instructionHandle, instructionHandle);
            }
            if (log.isDebugEnabled()) {
                var description = instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
                log.debug("not found astore for {}", description);
            }
            if (localVariable == null) {
                throw newInvalidEvalException("null local variable at index " + aloadIndex, instruction, constantPoolGen);
            }
            return success(Value.variable(localVariable), instructionHandle, instructionHandle);
        } else if (instruction instanceof ASTORE) {
            return eval(instructionHandle.getPrev());
        } else if (instruction instanceof GETSTATIC) {
            var getStatic = (GETSTATIC) instruction;
            var fieldType = getStatic.getFieldType(constantPoolGen);
            var fieldName = getStatic.getFieldName(constantPoolGen);
            var fieldClass = getClassByName(fieldType.getClassName());
            return getFieldValue(null, fieldClass, fieldName, instructionHandle, instructionHandle);
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;
            var evalFieldOwnedObject = eval(instructionHandle.getPrev());
            var fieldName = getField.getFieldName(constantPoolGen);
            var filedOwnedObject = evalFieldOwnedObject.getFirstValue();
            return getFieldValue(filedOwnedObject.getValue(), fieldName, instructionHandle, evalFieldOwnedObject.getLastInstruction());
        } else if (instruction instanceof CHECKCAST) {
            return eval(instructionHandle.getPrev());
        } else if (instruction instanceof InvokeInstruction) {
            return getInvokeResult(instructionHandle, (InvokeInstruction) instruction);
        } else if (instruction instanceof ANEWARRAY) {
            var anewarray = (ANEWARRAY) instruction;
            var loadClassType = anewarray.getLoadClassType(constantPoolGen);
            var size = eval(instructionHandle.getPrev());
            var result = size.getFirstValue();
            var arrayElementType = getClassByName(loadClassType.getClassName());
            return success(constant(Array.newInstance(arrayElementType, (int) result.getValue())), instructionHandle, size.getLastInstruction());
        } else if (instruction instanceof ConstantPushInstruction) {
            var cpi = (ConstantPushInstruction) instruction;
            var value = cpi.getValue();
            return success(constant(value), instructionHandle, instructionHandle);
        } else if (instruction instanceof AASTORE) {
            var element = eval(instructionHandle.getPrev());
            var index = eval(element.getLastInstruction().getPrev());
            var array = eval(index.getLastInstruction().getPrev());
            var result = array.getFirstValue().getValue();
            if (result instanceof Object[]) {
                ((Object[]) result)[(int) index.getFirstValue().getValue()] = element.getFirstValue();
            } else {
                throw newInvalidEvalException("expected array but was " + result.getClass(), instruction, constantPoolGen);
            }
            return success(constant(result), instructionHandle, array.getLastInstruction());
        } else if (instruction instanceof DUP) {
            var eval = eval(instructionHandle.getPrev());
            return success(eval.getFirstValue(), instructionHandle, eval.getLastInstruction());
        } else if (instruction instanceof ACONST_NULL) {
            return success(constant(null), instructionHandle, instructionHandle);
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    public Object getDefaultValue(Class<?> type) {
        var val = defVal(type);
        log.trace("getDefaultValue type {} is {}", type, val);
        return val;
    }

    public Result getInvokeResult(InstructionHandle instructionHandle, InvokeInstruction instruction) {
        if (log.isTraceEnabled()) {
            var instructionText = instruction.toString(constantPoolGen.getConstantPool());
            log.trace("eval {}", instructionText);
        }
        var methodName = instruction.getMethodName(constantPoolGen);
        var evalArguments = evalArguments(instructionHandle, instruction);
        var argumentVariants = evalArguments.getValueVariants();

        if (argumentVariants.size() > 1) {
            throw new UnsupportedOperationException("argumentVariants");
        }

        var values = argumentVariants.get(0);
        var arguments = values.stream().map(Value::getValue).toArray(Object[]::new);
        var lastArgInstruction = evalArguments.getInstructionHandle();
        if (instruction instanceof INVOKEVIRTUAL) {
            var next = lastArgInstruction.getPrev();
            var objectCallResult = eval(next);
            var obj = objectCallResult.getFirstValue();
            var lastInstruction = objectCallResult.getLastInstruction();
            var value = obj.getValue();
            return callMethod(value, value.getClass(), methodName, getArgumentTypes(instruction, constantPoolGen),
                    arguments, instructionHandle, lastInstruction, constantPoolGen);
        } else if (instruction instanceof INVOKEINTERFACE) {
            var next = lastArgInstruction.getPrev();
            var objectCallResult = eval(next);
            var obj = objectCallResult.getFirstValue();
            var type = getClassByName(instruction.getClassName(constantPoolGen));
            var lastInstruction = objectCallResult.getLastInstruction();
            return callMethod(obj.getValue(), type, methodName, getArgumentTypes(instruction, constantPoolGen), arguments,
                    instructionHandle, lastInstruction, constantPoolGen);
        } else if (instruction instanceof INVOKEDYNAMIC) {
            var result = callBootstrapMethod(arguments, (INVOKEDYNAMIC) instruction, constantPoolGen, bootstrapMethods);
            return success(result, instructionHandle, lastArgInstruction);
        } else if (instruction instanceof INVOKESTATIC) {
            var type = getClassByName(instruction.getClassName(constantPoolGen));
            return callMethod(null, type, methodName, getArgumentTypes(instruction, constantPoolGen), arguments,
                    instructionHandle, instructionHandle, constantPoolGen);
        } else if (instruction instanceof INVOKESPECIAL) {
            var invokeSpec = (INVOKESPECIAL) instruction;
            var lookup = MethodHandles.lookup();
            var type = getClassByName(instruction.getClassName(constantPoolGen));
            var signature = invokeSpec.getSignature(constantPoolGen);
            var methodType = fromMethodDescriptorString(signature, type.getClassLoader());
            if ("<init>".equals(methodName)) {
//                var constructor = getMethodHandle(() -> lookup.findConstructor(type, methodType));
//                Object result = invoke(constructor, asList(arguments));
//                return success(result, instructionHandle, instructionHandle);
                return instantiateObject(instructionHandle, type, getArgumentTypes(instruction, constantPoolGen), arguments);
            } else {
                var privateLookup = getPrivateLookup(type, lookup);
                var methodHandle = getMethodHandle(() -> privateLookup.findSpecial(type, methodName, methodType, type));
                var result = invoke(methodHandle, getInvokeArgs(componentInstance, arguments));
                return success(result, instructionHandle, instructionHandle);
            }
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    public List<List<Value>> resolveArgumentValues(Value[] argumentValues) {
        var hasUnresolved = of(argumentValues).anyMatch(a -> a instanceof Value.MethodArgument);
        if (hasUnresolved) {
            var dependentOnObject = components.stream()
                    .filter(c -> c.getName().equals(componentName) || ofNullable(c.getDependencies())
                            .flatMap(Collection::stream).map(Component::getName)
                            .anyMatch(n -> n.equals(componentName)))
                    .collect(toList());

            var methodCallVariants = dependentOnObject.stream().map(componentDependent -> {
                var callersWithVariants = componentDependent.getCallPoints().stream().map(dependentMethod -> {
                    var argVariants = dependentMethod.getCallPoints().stream()
                            .map(callPoint -> getArgumentsVariant(componentDependent, dependentMethod, callPoint))
                            .filter(Objects::nonNull).collect(toList());
                    return !argVariants.isEmpty() ? entry(dependentMethod, argVariants) : null;
                }).filter(Objects::nonNull).collect(toList());
                return entry(componentDependent, callersWithVariants);
            }).collect(toList());

            var variants = methodCallVariants.stream().map(Map.Entry::getValue).flatMap(Collection::stream)
                    .flatMap(e -> e.getValue().stream()).map(EvalArguments::getValueVariants)
                    .flatMap(Collection::stream)
                    .distinct().collect(toList());

//            if (variants.size() == 1) {
//                List<Object> variant = variants.get(0);
//                return List.of(of(arguments).map(arg -> resolveMethodArgument(arg, variant)).collect(toList()));
//            } else {
            return variants.stream().map(variant -> of(argumentValues).map(arg -> resolveMethodArgument(arg, variant))
                            .collect(toList()))
                    .collect(toList());
//            }
        } else {
            return List.of(Arrays.asList(argumentValues));
        }
    }

    protected EvalArguments getArgumentsVariant(Component component, CallPoint dependentMethod,
                                                CallPoint calledMethodInsideDependent) {
        var calledMethod = calledMethodInsideDependent.getMethodName();
        var currentMethod = method.getName();
        var methodEquals = currentMethod.equals(calledMethod);
        var argumentTypes = method.getArgumentTypes();
        var calledMethodClass = getCalledMethodClass(calledMethodInsideDependent);
        var classEquals = calledMethodClass != null && calledMethodClass.isAssignableFrom(componentType);
        var argumentsEqual = Arrays.equals(argumentTypes, calledMethodInsideDependent.getArgumentTypes());
        if (methodEquals && argumentsEqual && classEquals) {
            var instructionHandle = calledMethodInsideDependent.getInstruction();
            var instructionHandleInstruction = instructionHandle.getInstruction();
            var javaClass = dependentMethod.getJavaClass();
            var constantPoolGen = new ConstantPoolGen(dependentMethod.getMethod().getConstantPool());
            var unmanagedInstance = component.getUnmanagedInstance();
            var instance = unmanagedInstance != null ? unmanagedInstance : context.getBean(component.getName());
            var eval = new Eval(context, instance,
                    component.getName(), component.getType(), constantPoolGen, JmsOperationsUtils.getBootstrapMethods(javaClass),
                    dependentMethod.getMethod(), components);
            return eval.evalArguments(instructionHandle, (InvokeInstruction) instructionHandleInstruction);
        } else {
            return null;
        }
    }

    public EvalArguments evalArguments(InstructionHandle instructionHandle, InvokeInstruction instruction) {
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var argumentsAmount = argumentTypes.length;
        var values = new Value[argumentsAmount];
        var multiValues = new HashMap<Integer, Collection<Value>>();
        var valuesVariants = new ArrayList<Value[]>();
        var dimension = 1;
        var current = instructionHandle;
        for (int i = argumentsAmount; i > 0; i--) {
            current = current.getPrev();
            var eval = eval(current);
            current = eval.getLastInstruction();
            var results = eval.getValues();
            var valIndex = i - 1;
            values[valIndex] = results.size() == 1 ? results.get(0) : Value.constant(null);
            if (results.size() > 1) {
                var uniques = new LinkedHashSet<>(results);
                if (uniques.size() > 1) {
                    dimension *= uniques.size();
                    multiValues.put(valIndex, uniques);
                }
            }
        }
        if (!multiValues.isEmpty()) {
            var iterators = new HashMap<Integer, Iterator<Value>>();
            for (var i = 0; i < dimension; i++) {
                var copy = Arrays.copyOf(values, values.length);
                for (var multiArgIndex : multiValues.keySet()) {
                    var iter = iterators.get(multiArgIndex);
                    if (iter == null || !iter.hasNext()) {
                        var variants = multiValues.get(multiArgIndex);
                        iter = variants.iterator();
                        iterators.put(multiArgIndex, iter);
                    }
                    var val = iter.next();
                    copy[multiArgIndex] = val;
                }
                valuesVariants.add(copy);
            }
        } else {
            valuesVariants.add(values);
        }
        var valueVariants = valuesVariants.isEmpty()
                ? resolveArgumentValues(values)
                : valuesVariants.stream().map(this::resolveArgumentValues)
                .flatMap(Collection::stream)
                .collect(toList());
        return new EvalArguments(valueVariants, current);
    }

    public interface Value {
        static Const constant(Object value) {
            return new Const(value);
        }

        static MethodArgument variable(LocalVariable localVariable) {
            return Value.MethodArgument.builder().localVariable(localVariable).build();
        }

        Object getValue();

        @Data
        public class Const implements Eval.Value {
            private final Object value;

            @Override
            public Object getValue() {
                return value;
            }
        }

        @Data
        @Builder(toBuilder = true)
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        public class MethodArgument implements Eval.Value {
            LocalVariable localVariable;
            List<Object> variants;

            @Override
            public Object getValue() {
                throw new UnevaluatedVariableException("todo");
            }

            @Override
            public String toString() {
                return "arg(" + localVariable.getIndex() + "):" + localVariable.getName();
            }
        }
    }

    @Data
    public static class EvalArguments {
        private final List<List<Value>> valueVariants;
        private final InstructionHandle instructionHandle;
    }

    @Data
    @Builder
    public static class Result {
        private final List<Value> values;
        private final Set<Status> status;
        private final Object source;
        private final InstructionHandle callInstruction;
        private final InstructionHandle lastInstruction;

        public static Result success(List<Value> values, InstructionHandle callInstruction, InstructionHandle lastInstruction) {
            return Result.builder().values(values).callInstruction(callInstruction).lastInstruction(lastInstruction).build();
        }

        public static Result success(Value value, InstructionHandle callInstruction, InstructionHandle lastInstruction) {
            var nullSupporting = new ArrayList<Value>();
            nullSupporting.add(value);
            return Result.builder().values(nullSupporting).callInstruction(callInstruction).lastInstruction(lastInstruction).build();
        }

        public static Result notAccessible(Object source, InstructionHandle callInstruction) {
            return Result.builder().status(Set.of(Status.notAccessible)).source(source).callInstruction(callInstruction).build();
        }

        public static Result notFound(Object source, InstructionHandle callInstruction) {
            return Result.builder().status(Set.of(notFound)).source(source).callInstruction(callInstruction).build();
        }

        public Value getFirstValue() {
            return getValues().get(0);
        }

        public List<Value> getValues() {
            throwResultExceptionIfInvalidStatus();
            return values;
        }

        public InstructionHandle getLastInstruction() {
            throwResultExceptionIfInvalidStatus();
            return lastInstruction;
        }

        private void throwResultExceptionIfInvalidStatus() {
            if (status != null && !status.isEmpty()) {
                throw new CallResultException(status, source, callInstruction);
            }
        }

        public enum Status {
            notAccessible, notFound;
        }

        @Getter
        public static class CallResultException extends RuntimeException {
            public CallResultException(Collection<Status> status, Object source, InstructionHandle instruction) {
                super(status + ", source=" + source + ", instruction=" + instruction);
            }
        }
    }
}
