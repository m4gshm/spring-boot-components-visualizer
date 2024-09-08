package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.MethodArgumentResolver.Argument;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Delay;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Illegal.Status;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Multiple;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Variable;
import io.github.m4gshm.connections.client.JmsOperationsUtils;
import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.Utils.classByName;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.Illegal.Status.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.constant;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.delay;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.notFound;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.variable;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeException.newInvalidEvalException;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeException.newUnsupportedEvalException;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.*;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.Map.entry;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.ofNullable;
import static lombok.AccessLevel.PRIVATE;
import static lombok.AccessLevel.PUBLIC;

@Slf4j
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class EvalBytecode {
    ConfigurableApplicationContext context;
    Object componentInstance;
    String componentName;
    Class<?> componentType;
    ConstantPoolGen constantPoolGen;
    BootstrapMethods bootstrapMethods;
    Method method;
    Collection<Component> components;
    Map<Integer, List<InstructionHandle>> jumpTo;
    EvalBytecode.MethodArgumentResolver methodArgumentResolver;
    EvalBytecode.MethodReturnResolver methodReturnResolver;

    public EvalBytecode(ConfigurableApplicationContext context, Object componentInstance, String componentName,
                        Class<?> componentType, ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods,
                        Method method, Collection<Component> components,
                        MethodArgumentResolver methodArgumentResolver,
                        MethodReturnResolver methodReturnResolver) {
        this.context = context;
        this.componentInstance = componentInstance;
        this.componentName = componentName;
        this.componentType = componentType;
        this.constantPoolGen = constantPoolGen;
        this.bootstrapMethods = bootstrapMethods;
        this.method = method;
        this.components = components;
        this.jumpTo = instructionHandleStream(method.getCode()).map(instructionHandle -> {
            var instruction = instructionHandle.getInstruction();
            if (instruction instanceof BranchInstruction) {
                return entry(((BranchInstruction) instruction).getTarget().getPosition(), instructionHandle);
            } else {
                return null;
            }
        }).filter(Objects::nonNull).collect(groupingBy(Entry::getKey, mapping(Entry::getValue, toList())));
        this.methodArgumentResolver = methodArgumentResolver;
        this.methodReturnResolver = methodReturnResolver;
    }

    public static ArrayList<Object> getInvokeArgs(Object object, Object[] arguments) {
        var invokeArgs = new ArrayList<>(arguments.length);
        invokeArgs.add(object);
        invokeArgs.addAll(asList(arguments));
        return invokeArgs;
    }

    public static Result resolveMethodArgumentVariant(Result arg, List<Result> variant) {
        if (arg instanceof Variable) {
            var methodArgument = (Variable) arg;
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
            log.debug("getCalledMethodClass", e);
        }
        return calledMethodClass;
    }

    private static InstructionHandle getLastArgInstruction(List<Result> arguments, Arguments evalArguments) {
        //a first arg is resolved last and refers to the last evaluated instruction
        var lastArgInstruction = arguments.isEmpty()
                ? evalArguments.getLastArgInstruction()
                : arguments.get(0).getLastInstruction();
        return lastArgInstruction;
    }

    @Override
    public String toString() {
        return "EvalBytecode{" +
                "componentName='" + componentName + '\'' +
                "method='" + method.getSignature() + '\'' +
                '}';
    }

    public Result eval(InstructionHandle instructionHandle) {
        var instruction = instructionHandle.getInstruction();
        var consumeStack = instruction.consumeStack(constantPoolGen);
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            var value = ldc.getValue(constantPoolGen);
            return constant(value, instructionHandle);
        } else if (instruction instanceof LoadInstruction) {
            var aload = (LoadInstruction) instruction;
            var aloadIndex = aload.getIndex();
            var localVariableTable = Stream.of(this.method.getLocalVariableTable().getLocalVariableTable())
                    .collect(groupingBy(LocalVariable::getIndex));
            var localVariables = localVariableTable.getOrDefault(aloadIndex, List.of());
            var position = instructionHandle.getPosition();

            var localVariable = findLocalVariable(instructionHandle, localVariables, position);

            var name = localVariable != null ? localVariable.getName() : null;
            if ("this".equals(name)) {
                return constant(componentInstance, instructionHandle);
            }

            var aStoreResults = findStoreInstructionResults(instructionHandle, localVariables, aloadIndex);
            if (aStoreResults.size() == 1) {
                return aStoreResults.get(0);
            } else if (!aStoreResults.isEmpty()) {
                return Result.multiple(aStoreResults, instructionHandle);
            }
            if (log.isDebugEnabled()) {
                var description = instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
                log.debug("not found astore for {}", description);
            }
            if (localVariable == null) {
                throw newInvalidEvalException("null local variable at index " + aloadIndex, instruction, constantPoolGen);
            }
            return variable(localVariable, this.method, this.componentType, instructionHandle);
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
            var lastInstruction = evalFieldOwnedObject.getLastInstruction();
            return getFieldValue(evalFieldOwnedObject, fieldName, instructionHandle, lastInstruction);
        } else if (instruction instanceof CHECKCAST) {
            return eval(instructionHandle.getPrev());
        } else if (instruction instanceof InvokeInstruction) {
            return getInvokeResult(instructionHandle, (InvokeInstruction) instruction);
        } else if (instruction instanceof ANEWARRAY) {
            var anewarray = (ANEWARRAY) instruction;
            var loadClassType = anewarray.getLoadClassType(constantPoolGen);
            var size = eval(instructionHandle.getPrev());
            var arrayElementType = getClassByName(loadClassType.getClassName());
            return delay(size::getLastInstruction, lastInstruction -> {
                return constant(Array.newInstance(arrayElementType, (int) size.getValue()), lastInstruction);
            });
        } else if (instruction instanceof ConstantPushInstruction) {
            var cpi = (ConstantPushInstruction) instruction;
            var value = cpi.getValue();
            return constant(value, instructionHandle);
        } else if (instruction instanceof AASTORE) {
            var element = eval(instructionHandle.getPrev());
            var index = eval(element.getLastInstruction().getPrev());
            var array = eval(index.getLastInstruction().getPrev());
            return delay(array::getLastInstruction, lastInstruction -> {
                var result = array.getValue();
                if (result instanceof Object[]) {
                    ((Object[]) result)[(int) index.getValue()] = element;
                } else {
                    throw newInvalidEvalException("expected array but was " + result.getClass(), instruction, constantPoolGen);
                }
                return constant(result, lastInstruction);
            });
        } else if (instruction instanceof NEW) {
            var newInstance = (NEW) instruction;
        } else if (instruction instanceof DUP) {
            return eval(instructionHandle.getPrev());
        } else if (instruction instanceof ACONST_NULL) {
            return constant(null, instructionHandle);
        } else if (instruction instanceof IfInstruction) {
            var args = new Result[consumeStack];
            var current = instructionHandle;
            for (var i = consumeStack - 1; i >= 0; --i) {
                current = instructionHandle.getPrev();
                args[i] = eval(current);
            }
            return delay(() -> args.length > 0 ? args[0].getLastInstruction() : instructionHandle, lastInstruction -> {
                List<List<Result>> collected = Arrays.stream(args).map(a -> resolve(a)).collect(toList());
                var ifInstruction = (IfInstruction) instruction;
                //todo need select positive or negative branch
                throw newUnsupportedEvalException(ifInstruction, constantPoolGen);
            });
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    private List<Result> findStoreInstructionResults(InstructionHandle instructionHandle, List<LocalVariable> localVariables, int index) {
        var prev = instructionHandle.getPrev();
        var aStoreResults = new ArrayList<Result>(localVariables.size());
        while (prev != null) {
            if (prev.getInstruction() instanceof ASTORE) {
                var astore = (ASTORE) prev.getInstruction();
                if (astore.getIndex() == index) {
                    var storedInLocal = eval(prev);
                    aStoreResults.add(storedInLocal);
                    prev = prev.getPrev();
                }
            }
            prev = prev.getPrev();
        }
        return aStoreResults;
    }

    private static LocalVariable findLocalVariable(InstructionHandle instructionHandle, List<LocalVariable> localVariables, int position) {
        if (localVariables.isEmpty()) {
            log.warn("no matched local variables for instruction {} ", instructionHandle);
            return null;
        }
       return localVariables.stream().filter(variable -> {
            int startPC = variable.getStartPC();
            var endPC = startPC + variable.getLength();
            return startPC <= position && position <= endPC;
        }).findFirst().orElseGet(() -> {
            return localVariables.get(0);
        });
    }

    public Result getInvokeResult(InstructionHandle instructionHandle, InvokeInstruction instruction) {
        if (log.isTraceEnabled()) {
            var instructionText = instruction.toString(constantPoolGen.getConstantPool());
            log.trace("eval {}", instructionText);
        }
        var methodName = instruction.getMethodName(constantPoolGen);
        var evalArguments = evalArguments(instructionHandle, instruction);
        var evalArgumentsResults = evalArguments.getArguments();
        var lastArgInstruction = evalArguments.getLastArgInstruction();

        final InstructionHandle lastInstruction;
        final Result objectCallResult;
        var callConstructor = instruction instanceof INVOKESPECIAL && methodName.equals("<init>");
        if (callConstructor) {
            var prev = lastArgInstruction.getPrev();
            if (prev.getInstruction() instanceof DUP) {
                prev = prev.getPrev();
            }
            if (prev.getInstruction() instanceof NEW) {
                lastInstruction = prev;
            } else {
                //log warn
                lastInstruction = lastArgInstruction;
            }
            objectCallResult = null;
        } else if (instruction instanceof INVOKEVIRTUAL || instruction instanceof INVOKEINTERFACE) {
            objectCallResult = eval(lastArgInstruction.getPrev());
            lastInstruction = objectCallResult.getLastInstruction();
        } else {
            objectCallResult = null;
            lastInstruction = lastArgInstruction;
        }

        var argumentTypes = getArgumentTypes(instruction, constantPoolGen);
        if (instruction instanceof INVOKEVIRTUAL) {
            return delay(() -> lastInstruction, lastIntr -> {
                var firstVariantResults = resolveArgumentVariants(evalArgumentsResults);
//                var lastArgInstruction = getLastArgInstruction(firstVariantResults, evalArguments);
//                var next = lastInstruction.getPrev();
//                var objectCallResult = eval(next);
//                var lastInstruction = objectCallResult.getLastInstruction();

                var arguments = getArguments(argumentTypes, firstVariantResults);
                var firstResolved = resolveFirstResult(Objects.requireNonNull(objectCallResult, "call object"));
                var value = firstResolved.getValue();
                return callMethod(value, value.getClass(), methodName, argumentTypes,
                        arguments, instructionHandle, lastIntr, constantPoolGen);
            });
        } else if (instruction instanceof INVOKEINTERFACE) {
            return delay(() -> lastInstruction, lastIntr -> {
                var firstVariantResults = resolveArgumentVariants(evalArgumentsResults);
//                var lastArgInstruction = getLastArgInstruction(firstVariantResults, evalArguments);
//                var next = lastInstruction.getPrev();
//                var objectCallResult = eval(next);
                var type = getClassByName(instruction.getClassName(constantPoolGen));
//                var lastInstruction = objectCallResult.getLastInstruction();
                var arguments = getArguments(argumentTypes, firstVariantResults);
                var firstResolved = resolveFirstResult(objectCallResult);
                return callMethod(firstResolved.getValue(), type, methodName, argumentTypes, arguments,
                        instructionHandle, lastIntr, constantPoolGen);
            });
        } else if (instruction instanceof INVOKEDYNAMIC) {
            return delay(() -> lastInstruction, lastIntr -> {
                var firstVariantResults = resolveArgumentVariants(evalArgumentsResults);
//                var lastArgInstruction = getLastArgInstruction(firstVariantResults, evalArguments);
                var arguments = getArguments(argumentTypes, firstVariantResults);
                return callBootstrapMethod(arguments, (INVOKEDYNAMIC) instruction, constantPoolGen, bootstrapMethods,
                        lastIntr);
            });
        } else if (instruction instanceof INVOKESTATIC) {
            return delay(() -> lastInstruction, lastIntr -> {
                var firstVariantResults = resolveArgumentVariants(evalArgumentsResults);
//                var lastArgInstruction = getLastArgInstruction(firstVariantResults, evalArguments);
                var arguments = getArguments(argumentTypes, firstVariantResults);
                var type = getClassByName(instruction.getClassName(constantPoolGen));
                return callMethod(null, type, methodName, argumentTypes, arguments,
                        instructionHandle, lastIntr, constantPoolGen);
            });
        } else if (instruction instanceof INVOKESPECIAL) {
            return delay(() -> lastInstruction, lastIntr -> {
                var firstVariantResults = resolveArgumentVariants(evalArgumentsResults);
//                var lastArgInstruction = getLastArgInstruction(firstVariantResults, evalArguments);
                var arguments = getArguments(argumentTypes, firstVariantResults);
                var invokeSpec = (INVOKESPECIAL) instruction;
                var lookup = MethodHandles.lookup();
                var type = getClassByName(instruction.getClassName(constantPoolGen));
                var signature = invokeSpec.getSignature(constantPoolGen);
                var methodType = fromMethodDescriptorString(signature, type.getClassLoader());
                if ("<init>".equals(methodName)) {
                    return instantiateObject(lastIntr, type, argumentTypes, arguments);
                } else {
                    var privateLookup = getPrivateLookup(type, lookup);
                    var methodHandle = getMethodHandle(() -> privateLookup.findSpecial(type, methodName, methodType, type));
                    return invoke(methodHandle, getInvokeArgs(componentInstance, arguments), lastIntr);
                }
            });
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    private List<Result> resolveArgumentVariants(List<Result> evalArgumentsResults) {
        var argumentVariants = new ArrayList<List<Result>>(evalArgumentsResults.size());
        var dimension = 1;
        //todo arguments should be resolved in reverse mode
        for (var value : evalArgumentsResults) {
            var variant = resolve(value);
            argumentVariants.add(variant);
            var size = variant.size();
            dimension = dimension * size;
        }

        var filledVariants = new ArrayList<List<Result>>();
        for (var i = 0; i < dimension; i++) {
            var filledVariant = new ArrayList<Result>();
            for (var variant : argumentVariants) {
                int size = variant.size();
                int i1 = size % dimension;
                var variantResult = variant.get(i1);
                filledVariant.add(variantResult);
            }
            filledVariants.add(filledVariant);
        }
        return filledVariants.get(0);
    }

    private Object[] getArguments(Class<?>[] argumentTypes, List<Result> results) {
        return normalizeArgumentTypes(argumentTypes, results.stream().map(Result::getValue).toArray());
    }

    protected Result callMethod(Object object, Class<?> type, String methodName,
                                Class<?>[] argTypes, Object[] args,
                                InstructionHandle invokeInstruction,
                                InstructionHandle lastInstruction,
                                ConstantPoolGen constantPoolGen) {
        var msg = "callMethod";
        var declaredMethod = getDeclaredMethod(methodName, type, argTypes);
        if (declaredMethod == null) {
            log.info("{}, method not found '{}.{}', instruction {}", msg, type.getName(), methodName,
                    EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
            return notFound(methodName, invokeInstruction);
        } else if (!declaredMethod.trySetAccessible()) {
            log.warn("{}, method is not accessible, method '{}.{}', instruction {}", msg, type.getName(), methodName,
                    EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
            return notAccessible(declaredMethod, invokeInstruction);
        }
        Object result;
        try {
            result = declaredMethod.invoke(object, args);
        } catch (IllegalAccessException e) {
            result = resolveStub(object, declaredMethod, e);
        } catch (InvocationTargetException e) {
            result = resolveStub(object, declaredMethod, e.getTargetException());
        } catch (NullPointerException e) {
            //todo just check the object is null
            throw e;
        }
        if (log.isDebugEnabled()) {
            log.debug("{}, success, method '{}.{}', result: {}, instruction {}", msg, type.getName(), methodName,
                    result, EvalBytecodeUtils.toString(invokeInstruction, constantPoolGen));
        }
        return constant(result, lastInstruction);
    }

    private Object resolveStub(Object object, java.lang.reflect.Method declaredMethod, Throwable targetException) {
        Object result;
        var objectClass = object != null ? object.getClass() : null;
        var returnType = declaredMethod.getReturnType();
        if (log.isDebugEnabled()) {
            log.debug("method invocation error, type {}, method {}. Trying to make stub of type {}",
                    objectClass, declaredMethod, returnType.getName(), targetException);
        } else {
            log.info("method invocation error, type {}, method {}, message {}. Trying to make stub of type {}",
                    objectClass, declaredMethod, targetException.getMessage(), returnType.getName());
        }
        var signature = Type.getType(declaredMethod.getReturnType()).getSignature();
        try {
            result = this.methodReturnResolver.resolve(new MethodInfo(objectClass, declaredMethod.getName(),
                    signature));
        } catch (Exception re) {
            throw new EvalBytecodeException(re);
        }
        return result;
    }


    private Result resolveFirstResult(Result first) {
        var resolved = resolve(first);
        //todo need to support case with multiple variants
        if (resolved.size() > 1) {
            throw new UnsupportedOperationException("multiple objectCallResult resolved");
        }
        return resolved.get(0);
    }

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

    public List<Result> resolve(Result value) {
        if (value instanceof Variable) {
            var variable = (Variable) value;

            var dependentOnThisComponent = getDependentOnThisComponent();
            var methodName = variable.method.getName();
            var argumentTypes = variable.method.getArgumentTypes();
            var objectType = variable.objectType;

            var methodCallVariants = getMethodCallVariants(
                    methodName, argumentTypes, objectType, dependentOnThisComponent
            );
            var argumentVariants = getArgumentVariants(methodCallVariants);
            var valueVariants = argumentVariants.stream()
                    .map(variant -> resolveMethodArgumentVariant(value, variant))
                    .collect(toList());
            if (!valueVariants.isEmpty()) {
                return valueVariants.stream().flatMap(r -> resolve(r).stream()).collect(toList());
            } else {
                return List.of(manualResolve((Variable) value));
            }
        } else if (value instanceof Delay) {
            return resolve(((Delay) value).getDelayed());
        } else if (value instanceof Multiple) {
            return ((Multiple) value).getValues().stream().flatMap(v -> resolve(v).stream()).collect(toList());
        } else {
            return List.of(value);
        }
    }

    protected Result manualResolve(Variable value) {
        var localVariable = value.localVariable;
        var type = Type.getType(localVariable.getSignature());
        var method = value.getMethod();
        var methodSignature = method.getSignature();
        var argument = new Argument(type.getClassName(), localVariable.getName(), localVariable.getIndex());
        var argumentMethod = new MethodInfo(value.getObjectType(), method.getName(), methodSignature);
        var resolved = constant(this.methodArgumentResolver.resolve(argumentMethod, argument), value.getLastInstruction());
        log.info("resolved argument {} of method {}, result {}", argument, argumentMethod, resolved);
        return resolved;
    }

    private List<List<Result>> getArgumentVariants(
            List<Entry<Component, List<Entry<CallPoint, List<Arguments>>>>> methodCallVariants
    ) {
        return methodCallVariants.stream().map(Entry::getValue)
                .flatMap(Collection::stream)
                .flatMap(e -> e.getValue().stream()).map(Arguments::getArguments)
                .distinct().collect(toList());
    }

    private List<Entry<Component, List<Entry<CallPoint, List<Arguments>>>>> getMethodCallVariants(
            String methodName, Type[] argumentTypes, Class<?> objectType, List<Component> dependentOnThis
    ) {
        return dependentOnThis.stream().map(dependentComponent -> {
            var callPoints = dependentComponent.getCallPoints();
            var callersWithVariants = callPoints.stream().map(dependentMethod -> {
                var matchedCallPoints = dependentMethod.getCallPoints().stream()
                        .filter(calledMethodInsideDependent -> {
                            var match = isMatch(methodName, argumentTypes, objectType, calledMethodInsideDependent);
                            var cycled = isMatch(dependentMethod.getMethodName(), dependentMethod.getArgumentTypes(),
                                    dependentMethod.getOwnerClass(), calledMethodInsideDependent);
                            //exclude cycling
                            return match && !cycled;
                        }).collect(toList());
                var argVariants = matchedCallPoints.stream()
                        .map(callPoint -> getArguments(dependentComponent, dependentMethod, callPoint))
                        .filter(Objects::nonNull).collect(toList());
                return !argVariants.isEmpty() ? entry(dependentMethod, argVariants) : null;
            }).filter(Objects::nonNull).collect(toList());
            return !callersWithVariants.isEmpty() ? entry(dependentComponent, callersWithVariants) : null;
        }).filter(Objects::nonNull).collect(toList());
    }

    private List<Component> getDependentOnThisComponent() {
        var componentName = this.componentName;
        return components.stream().filter(c -> c.getName().equals(componentName) || ofNullable(c.getDependencies())
                        .flatMap(Collection::stream).map(Component::getName)
                        .anyMatch(n -> n.equals(componentName)))
                .collect(toList());
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

    private Arguments getArguments(Component dependentComponent, CallPoint dependentMethod, CallPoint calledMethodInsideDependent) {
        var instructionHandle = calledMethodInsideDependent.getInstruction();
        var instructionHandleInstruction = instructionHandle.getInstruction();
        var javaClass = dependentMethod.getJavaClass();
        var constantPoolGen = new ConstantPoolGen(dependentMethod.getMethod().getConstantPool());
        var unmanagedInstance = dependentComponent.getUnmanagedInstance();
        var instance = unmanagedInstance != null ? unmanagedInstance : context.getBean(dependentComponent.getName());
        var eval = new EvalBytecode(context, instance,
                dependentComponent.getName(), dependentComponent.getType(), constantPoolGen, JmsOperationsUtils.getBootstrapMethods(javaClass),
                dependentMethod.getMethod(), components, this.methodArgumentResolver, this.methodReturnResolver);
        return eval.evalArguments(instructionHandle, (InvokeInstruction) instructionHandleInstruction);
    }

    public Arguments evalArguments(InstructionHandle instructionHandle, InvokeInstruction instruction) {
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var argumentsAmount = argumentTypes.length;
        var values = new Result[argumentsAmount];
        var current = instructionHandle;
        for (int i = argumentsAmount; i > 0; i--) {
            var jumpsFrom = this.jumpTo.get(current.getPosition());
            if (jumpsFrom != null && !jumpsFrom.isEmpty()) {
                //log
                var firstJump = jumpsFrom.get(0);
                while (true) {
                    jumpsFrom = this.jumpTo.get(firstJump.getPosition());
                    if (jumpsFrom != null && !jumpsFrom.isEmpty()) {
                        firstJump = jumpsFrom.get(0);
                    } else {
                        break;
                    }
                }
                //todo need call eval(firstJump)
                current = firstJump.getPrev();
            } else {
                current = current.getPrev();
            }
            var eval = eval(current);
            current = eval.getLastInstruction();
            var valIndex = i - 1;
            values[valIndex] = eval;
        }
        return new Arguments(asList(values), current);
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

        static Const constant(Object value, InstructionHandle lastInstruction) {
            return new Const(value, lastInstruction);
        }

        static Delay delay(Supplier<InstructionHandle> lastInstructionSupplier, Function<InstructionHandle, Result> evaluator) {
            return new Delay(lastInstructionSupplier, evaluator);
        }

        static Variable variable(LocalVariable localVariable, Method method, Class<?> type, InstructionHandle lastInstruction) {
            return new Variable(type, method, localVariable, lastInstruction);
        }

        static Illegal notAccessible(Object source, InstructionHandle callInstruction) {
            return new Illegal(Set.of(notAccessible), source, callInstruction, callInstruction);
        }

        static Illegal notFound(Object source, InstructionHandle callInstruction) {
            return new Illegal(Set.of(Status.notFound), source, callInstruction, callInstruction);
        }

        static Result multiple(List<Result> values, InstructionHandle lastInstruction) {
            return new Multiple(values, lastInstruction);
        }

        Object getValue();

        default boolean isMultiple() {
            return false;
        }

        InstructionHandle getLastInstruction();

        @Data
        class Const implements Result {
            private final Object value;
            private final InstructionHandle lastInstruction;

            @Override
            public Object getValue() {
                return value;
            }

            @Override
            public String toString() {
                return "const(" + value + ")";
            }
        }

        @Data
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class Illegal implements Result {
            Set<Status> status;
            Object source;
            InstructionHandle callInstruction;
            InstructionHandle lastInstruction;

            @Override
            public Object getValue() {
                throw new IllegalInvokeException(status, source, callInstruction);
            }

            public enum Status {
                notAccessible, notFound
            }
        }

        @Data
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        class Variable implements Result {
            Class<?> objectType;
            Method method;
            LocalVariable localVariable;
            InstructionHandle lastInstruction;

            @Override
            public Object getValue() {
                throw new UnevaluatedVariableException("unresolved variable " + this);
            }

            @Override
            public String toString() {
                return "arg(" + localVariable.getIndex() + "):" + localVariable.getName();
            }
        }


        @Data
        @FieldDefaults(level = PRIVATE)
        class Delay implements Result {
            final Supplier<InstructionHandle> lastInstructionSupplier;
            final Function<InstructionHandle, Result> evaluator;
            Result result;
            boolean evaluated;
            InstructionHandle lastInstruction;

            @Override
            public Object getValue() {
                return getDelayed().getValue();
            }

            public Result getDelayed() {
                Result result = this.result;
                if (!evaluated) {
                    var instructionHandle = getInstructionHandle();
                    result = evaluator.apply(instructionHandle);
                    this.result = result;
                    evaluated = true;
                }
                return result;
            }

            private InstructionHandle getInstructionHandle() {
                var lastInstruction = this.lastInstruction;
                if (lastInstruction == null) {
                    var instruction = lastInstructionSupplier.get();
                    lastInstruction = instruction;
                }
                return lastInstruction;
            }

            @Override
            public InstructionHandle getLastInstruction() {
                return getDelayed().getLastInstruction();
            }

            @Override
            public String toString() {
                return "delay(evaluated:" + evaluated + ", result:" + result + ")";
            }
        }

        @Data
        @Builder(toBuilder = true)
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        public static class Multiple implements Result {
            List<Result> values;
            InstructionHandle lastInstruction;

            private void checkState() {
                if (values.isEmpty()) {
                    throw new IllegalStateException("unresolved multiple values");
                }
            }

            @Override
            public Object getValue() {
                checkState();
                return values.get(0);
            }

            public List<Result> getValues() {
                checkState();
                return values;
            }

            @Override
            public boolean isMultiple() {
                return Result.super.isMultiple();
            }
        }
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class Arguments {
        List<Result> arguments;
        InstructionHandle lastArgInstruction;
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PUBLIC)
    public static class MethodInfo {
        Class<?> objectType;
        String name;
        String signature;

        @Override
        public String toString() {
            return objectType.getName() + "." + name + signature;
        }
    }

    @Data
    public static class EvalArguments {
        private final List<List<Result>> valueVariants;
        private final InstructionHandle instructionHandle;
    }

}
