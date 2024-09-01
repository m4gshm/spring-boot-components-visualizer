package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.client.JmsOperationsUtils;
import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.MethodArgument;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.bytecode.EvalException.newInvalidEvalException;
import static io.github.m4gshm.connections.bytecode.EvalException.newUnsupportedEvalException;
import static io.github.m4gshm.connections.bytecode.EvalResult.success;
import static io.github.m4gshm.connections.bytecode.EvalUtils.*;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Map.entry;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.of;

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

    public static Object resolveMethodArgument(Object arg, List<Object> variant) {
        if (arg instanceof MethodArgument) {
            var methodArgument = (MethodArgument) arg;
            var localVariable = methodArgument.getLocalVariable();
            var i = localVariable.getIndex() - 1;
            return variant.get(i);
        }
        return arg;
    }

    public EvalResult<Object> eval(InstructionHandle instructionHandle) {
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            var value = ldc.getValue(constantPoolGen);
            return success(value, instructionHandle, instructionHandle);
        } else if (instruction instanceof ALOAD) {
            var aload = (ALOAD) instruction;
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
                return success(componentInstance, instructionHandle, instructionHandle);
            }

            var prev = instructionHandle.getPrev();
            var aStoreResults = new ArrayList<>(localVariables.size());
            while (prev != null) {
                if (prev.getInstruction() instanceof ASTORE) {
                    var astore = (ASTORE) prev.getInstruction();
                    if (astore.getIndex() == aloadIndex) {
                        var storedInLocal = eval(prev);
                        var result = storedInLocal.getResult();
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
            return success(MethodArgument.builder().localVariable(localVariable).build(), instructionHandle, instructionHandle);
        } else if (instruction instanceof ASTORE) {
            return eval(instructionHandle.getPrev());
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;
            var evalFieldOwnedObject = eval(instructionHandle.getPrev());
            var fieldName = getField.getFieldName(constantPoolGen);
            var filedOwnedObject = evalFieldOwnedObject.getResult();
            return getFieldValue(filedOwnedObject, fieldName, instructionHandle, evalFieldOwnedObject.getLastInstruction());
        } else if (instruction instanceof CHECKCAST) {
            return eval(instructionHandle.getPrev());
        } else if (instruction instanceof InvokeInstruction) {
            return getInvokeResult(instructionHandle, (InvokeInstruction) instruction);
        } else if (instruction instanceof ANEWARRAY) {
            var anewarray = (ANEWARRAY) instruction;
            var loadClassType = anewarray.getLoadClassType(constantPoolGen);
            var size = eval(instructionHandle.getPrev());
            var result = size.getResult();
            var arrayElementType = getClassByName(loadClassType.getClassName());
            return success(Array.newInstance(arrayElementType, (int) result), instructionHandle, size.getLastInstruction());
        } else if (instruction instanceof ConstantPushInstruction) {
            var cpi = (ConstantPushInstruction) instruction;
            var value = cpi.getValue();
            return success(value, instructionHandle, instructionHandle);
        } else if (instruction instanceof AASTORE) {
            var element = eval(instructionHandle.getPrev());
            var index = eval(element.getLastInstruction().getPrev());
            var array = eval(index.getLastInstruction().getPrev());
            var result = array.getResult();
            if (result instanceof Object[]) {
                ((Object[]) result)[(int) index.getResult()] = element.getResult();
            } else {
                throw newInvalidEvalException("expected array but was " + result.getClass(), instruction, constantPoolGen);
            }
            return success(result, instructionHandle, array.getLastInstruction());
        } else if (instruction instanceof DUP) {
            var eval = eval(instructionHandle.getPrev());
            return success(eval.getResult(), instructionHandle, eval.getLastInstruction());
        } else if (instruction instanceof ACONST_NULL) {
            return success(singletonList(null), instructionHandle, instructionHandle);
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    public Object getDefaultValue(Class<?> type) {
        var val = defVal(type);
        log.trace("getDefaultValue type {} is {}", type, val);
        return val;
    }

    public EvalResult<Object> getInvokeResult(InstructionHandle instructionHandle, InvokeInstruction instruction) {
        if (log.isTraceEnabled()) {
            var instructionText = instruction.toString(constantPoolGen.getConstantPool());
            log.trace("eval {}", instructionText);
        }
        var methodName = instruction.getMethodName(constantPoolGen);
        var evalArgumentsResult = evalArguments(instructionHandle, instruction);
        var argumentVariants = evalArgumentsResult.getValueVariants();

        if (argumentVariants.size() > 1) {
            throw new UnsupportedOperationException("argumentVariants");
        }

        var arguments = argumentVariants.get(0).toArray();
        var lastArgInstruction = evalArgumentsResult.getInstructionHandle();
        if (instruction instanceof INVOKEVIRTUAL) {
            var next = lastArgInstruction.getPrev();
            var objectCallResult = eval(next);
            var obj = objectCallResult.getResult();
            var lastInstruction = objectCallResult.getLastInstruction();
            return callMethod(obj, obj.getClass(), methodName, getArgumentTypes(instruction, constantPoolGen),
                    arguments, instructionHandle, lastInstruction, constantPoolGen);
        } else if (instruction instanceof INVOKEINTERFACE) {
            var next = lastArgInstruction.getPrev();
            var objectCallResult = eval(next);
            var obj = objectCallResult.getResult();
            var type = getClassByName(instruction.getClassName(constantPoolGen));
            var lastInstruction = objectCallResult.getLastInstruction();
            return callMethod(obj, type, methodName, getArgumentTypes(instruction, constantPoolGen), arguments,
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

    public List<List<Object>> resolveArgumentValues(Object[] argumentValues) {
        var hasUnresolved = of(argumentValues).anyMatch(a -> a instanceof MethodArgument);
        if (hasUnresolved) {
            var dependentOnObject = components.stream().filter(c -> Stream.ofNullable(c.getDependencies())
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
                    .flatMap(e -> e.getValue().stream()).map(ArgumentsValues::getValueVariants)
                    .flatMap(Collection::stream)
                    .distinct().collect(toList());

//            if (variants.size() == 1) {
//                List<Object> variant = variants.get(0);
//                return List.of(of(arguments).map(arg -> resolveMethodArgument(arg, variant)).collect(toList()));
//            } else {
            return variants.stream().map(variant -> of(argumentValues)
                    .map(arg -> resolveMethodArgument(arg, variant))
                    .collect(toList())).collect(toList());
//            }
        } else {
            return List.of(Arrays.asList(argumentValues));
        }
    }

    protected ArgumentsValues getArgumentsVariant(Component component, CallPoint dependentMethod,
                                                  CallPoint calledMethodInsideDependent) {
        var calledMethod = calledMethodInsideDependent.getMethodName();
        var currentMethod = method.getName();
        var methodEquals = currentMethod.equals(calledMethod);
        var argumentTypes = method.getArgumentTypes();
        var calledMethodClass = getClassByName(calledMethodInsideDependent.getOwnerClass());
        var classEquals = calledMethodClass.isAssignableFrom(componentType);
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

    public ArgumentsValues evalArguments(InstructionHandle instructionHandle, InvokeInstruction instruction) {
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var argumentsAmount = argumentTypes.length;
        var values = new Object[argumentsAmount];
        var multiValues = new HashMap<Integer, Collection<Object>>();
        var valuesVariants = new ArrayList<Object[]>();
        var dimension = 1;
        var current = instructionHandle;
        for (int i = argumentsAmount; i > 0; i--) {
            current = current.getPrev();
            var eval = eval(current);
            current = eval.getLastInstruction();
            var results = eval.getResults();
            var valIndex = i - 1;
            values[valIndex] = results.size() == 1 ? results.get(0) : null;
            if (results.size() > 1) {
                var uniques = new LinkedHashSet<>(results);
                if (uniques.size() > 1) {
                    dimension *= uniques.size();
                    multiValues.put(valIndex, uniques);
                }
            }
        }
        if (!multiValues.isEmpty()) {
            var iterators = new HashMap<Integer, Iterator<Object>>();
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
        }
        var valueVariants = valuesVariants.isEmpty()
                ? resolveArgumentValues(values)
                : valuesVariants.stream().map(this::resolveArgumentValues)
                .flatMap(Collection::stream)
                .collect(toList());
        return new ArgumentsValues(valueVariants, current);
    }

    @Data
    public static class ArgumentsValues {
        private final List<List<Object>> valueVariants;
        private final InstructionHandle instructionHandle;
    }
}
