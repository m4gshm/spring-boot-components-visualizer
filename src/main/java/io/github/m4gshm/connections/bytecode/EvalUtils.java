package io.github.m4gshm.connections.bytecode;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.IntStream;

import static io.github.m4gshm.connections.ReflectionUtils.getDeclaredField;
import static io.github.m4gshm.connections.ReflectionUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.bytecode.EvalException.newInvalidEvalException;
import static io.github.m4gshm.connections.bytecode.EvalException.newUnsupportedEvalException;
import static io.github.m4gshm.connections.bytecode.EvalUtils.CallResult.Status.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalUtils.CallResult.Status.notFound;
import static io.github.m4gshm.connections.bytecode.EvalUtils.CallResult.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalUtils.CallResult.notFound;
import static io.github.m4gshm.connections.bytecode.EvalUtils.CallResult.success;
import static java.lang.invoke.MethodHandles.privateLookupIn;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;
import static org.apache.bcel.Const.*;
import static org.aspectj.apache.bcel.Constants.CONSTANT_MethodHandle;
import static org.aspectj.apache.bcel.Constants.CONSTANT_Methodref;

@Slf4j
@UtilityClass
public class EvalUtils {

    public static JavaClass lookupClass(Class<?> componentType) {
        try {
            return Repository.lookupClass(componentType);
        } catch (ClassNotFoundException e) {
            throw new EvalException(e);
        }
    }

    public static CallResult<Object> eval(Object object, InstructionHandle instructionHandle,
                                          ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods) {
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            var value = ldc.getValue(constantPoolGen);
            return success(value, instructionHandle, instructionHandle);
        } else if (instruction instanceof ALOAD) {
            var aload = (ALOAD) instruction;
            var aloadIndex = aload.getIndex();
            if (aloadIndex == 0) {
                //this ???
                return success(object, instructionHandle, instructionHandle);
            } else {
                var prev = instructionHandle.getPrev();
                while (prev != null) {
                    if (prev.getInstruction() instanceof ASTORE) {
                        var astore = (ASTORE) prev.getInstruction();
                        if (astore.getIndex() == aloadIndex) {
                            var storedInLocal = eval(object, prev, constantPoolGen, bootstrapMethods);
                            return success(storedInLocal.getResult(), instructionHandle, instructionHandle);
                        }
                    }
                    prev = prev.getPrev();
                }
                if (log.isDebugEnabled()) {
                    var description = instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
                    log.debug("not found astore for {}", description);
                }
                return success(null, instructionHandle, instructionHandle);
            }
        } else if (instruction instanceof ASTORE) {
            return eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;
            var evalFieldOwnedObject = eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
            var fieldName = getField.getFieldName(constantPoolGen);
            var filedOwnedObject = evalFieldOwnedObject.getResult();
            return getFieldValue(filedOwnedObject, fieldName, instructionHandle, evalFieldOwnedObject.getLastInstruction());
        } else if (instruction instanceof CHECKCAST) {
            return eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
        } else if (instruction instanceof InvokeInstruction) {
            return getMethodResult(object, instructionHandle, (InvokeInstruction) instruction, constantPoolGen, bootstrapMethods);
        } else if (instruction instanceof ANEWARRAY) {
            var anewarray = (ANEWARRAY) instruction;
            var loadClassType = anewarray.getLoadClassType(constantPoolGen);
            var size = eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
            var result = size.getResult();
            var arrayElementType = getClassByName(loadClassType.getClassName());
            return success(Array.newInstance(arrayElementType, (int) result), instructionHandle, size.getLastInstruction());
        } else if (instruction instanceof ConstantPushInstruction) {
            var cpi = (ConstantPushInstruction) instruction;
            var value = cpi.getValue();
            return success(value, instructionHandle, instructionHandle);
        } else if (instruction instanceof AASTORE) {
            var element = eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
            var index = eval(object, element.getLastInstruction().getPrev(), constantPoolGen, bootstrapMethods);

            var array = eval(object, index.getLastInstruction().getPrev(), constantPoolGen, bootstrapMethods);

            var result = array.getResult();
            if (result instanceof Object[]) {
                ((Object[]) result)[(int) index.getResult()] = element.getResult();
            } else {
                throw newInvalidEvalException("expected array but was " + result.getClass(), instruction, constantPoolGen);
            }

            return success(result, instructionHandle, array.getLastInstruction());
        } else if (instruction instanceof DUP) {
            var eval = eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
            return success(eval.getResult(), instructionHandle, eval.getLastInstruction());
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    public static CallResult<Object> getMethodResult(Object object,
                                                     InstructionHandle instructionHandle, InvokeInstruction instruction,
                                                     ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods
    ) {
//        if (log.isTraceEnabled()) {
        var instructionText = instruction.toString(constantPoolGen.getConstantPool());
        log.trace("eval {}", instructionText);
//        }
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = getArgumentTypes(instruction.getArgumentTypes(constantPoolGen));
        var evalArgumentsResult = evalArguments(object, instructionHandle, argumentTypes.length, constantPoolGen, bootstrapMethods);
        var arguments = evalArgumentsResult.getResult();
        var lastArgInstruction = evalArgumentsResult.getInstructionHandle();
        if (instruction instanceof INVOKEVIRTUAL) {
            var next = lastArgInstruction.getPrev();
            var objectCallResult = eval(object, next, constantPoolGen, bootstrapMethods);
            var obj = objectCallResult.getResult();
            var lastInstruction = objectCallResult.getLastInstruction();
            return callMethod(obj, obj.getClass(), methodName, argumentTypes, arguments, instructionHandle, lastInstruction, constantPoolGen);
        } else if (instruction instanceof INVOKEINTERFACE) {
            var next = lastArgInstruction.getPrev();
            var objectCallResult = eval(object, next, constantPoolGen, bootstrapMethods);
            var obj = objectCallResult.getResult();
            var type = getClassByName(instruction.getClassName(constantPoolGen));
            var lastInstruction = objectCallResult.getLastInstruction();
            return callMethod(obj, type, methodName, argumentTypes, arguments, instructionHandle, lastInstruction, constantPoolGen);
        } else if (instruction instanceof INVOKEDYNAMIC) {
            var result = callBootstrapMethod(arguments, (INVOKEDYNAMIC) instruction, constantPoolGen, bootstrapMethods);
            return success(result, instructionHandle, lastArgInstruction);
        } else if (instruction instanceof INVOKESTATIC) {
            var type = getClassByName(instruction.getClassName(constantPoolGen));
            return callMethod(null, type, methodName, argumentTypes, arguments, instructionHandle, instructionHandle, constantPoolGen);
        } else if (instruction instanceof INVOKESPECIAL) {
            var invokeSpec = (INVOKESPECIAL) instruction;
            var type = getClassByName(instruction.getClassName(constantPoolGen));
            if ("<init>".equals(methodName)) {
                return instantiateObject(instructionHandle, type, argumentTypes, arguments);
            } else {
                var signature = invokeSpec.getSignature(constantPoolGen);
                var methodType = fromMethodDescriptorString(signature, type.getClassLoader());
                var lookup = getPrivateLookup(type, MethodHandles.lookup());
                MethodHandle methodHandle;
                try {
                    methodHandle = lookup.findSpecial(type, methodName, methodType, type);
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new EvalException(e);
                }
                var invokeArgs = new ArrayList<>(arguments.length);
                invokeArgs.add(object);
                invokeArgs.addAll(asList(arguments));
                Object result;
                try {
                    result = methodHandle.invokeWithArguments(invokeArgs);
                } catch (Throwable e) {
                    throw new EvalException(e);
                }
                return success(result, instructionHandle, instructionHandle);
            }
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    private static CallResult<Object> instantiateObject(InstructionHandle instructionHandle,
                                                        Class<?> type, Class[] argumentTypes, Object[] arguments) {
        Constructor<?> constructor;
        try {
            constructor = type.getConstructor(argumentTypes);
        } catch (NoSuchMethodException e) {
            throw new EvalException(e);
        }
        if (constructor.trySetAccessible()) try {
            return success(constructor.newInstance(arguments), instructionHandle, instructionHandle);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new EvalException(e);
        }
        else {
            //log
            return notAccessible(constructor, instructionHandle);
        }
    }

    static Object callBootstrapMethod(Object[] arguments, INVOKEDYNAMIC instruction, ConstantPoolGen
            constantPoolGen, BootstrapMethods bootstrapMethods) {
        var cp = constantPoolGen.getConstantPool();
        var constantInvokeDynamic = cp.getConstant(instruction.getIndex(), CONSTANT_InvokeDynamic, ConstantInvokeDynamic.class);
        var nameAndTypeIndex = constantInvokeDynamic.getNameAndTypeIndex();

        var constantNameAndType = cp.getConstant(nameAndTypeIndex, ConstantNameAndType.class);
        var interfaceMethodName = constantNameAndType.getName(cp);

        var factoryMethod = fromMethodDescriptorString(constantNameAndType.getSignature(cp), null);

        int bootstrapMethodAttrIndex = constantInvokeDynamic.getBootstrapMethodAttrIndex();
        var bootstrapMethod = bootstrapMethods.getBootstrapMethods()[bootstrapMethodAttrIndex];
        var bootstrapMethodHandle = cp.getConstant(bootstrapMethod.getBootstrapMethodRef(), CONSTANT_MethodHandle, ConstantMethodHandle.class);
        var bootstrapMethodref = cp.getConstant(bootstrapMethodHandle.getReferenceIndex(), CONSTANT_Methodref, ConstantMethodref.class);

        var nameAndType = cp.getConstant(bootstrapMethodref.getNameAndTypeIndex(), CONSTANT_NameAndType, ConstantNameAndType.class);

        var lookup = MethodHandles.lookup();

        var bootstrapClass = getClassByName(bootstrapMethodref.getClass(cp));
        var bootstrapMethodName = nameAndType.getName(cp);
        var bootstrapMethodSignature = nameAndType.getSignature(cp);
        var handler = lookupReference(lookup, bootstrapMethodHandle.getReferenceKind(),
                bootstrapClass, bootstrapMethodName, fromMethodDescriptorString(bootstrapMethodSignature, null));

        var bootstrabMethodArguments = IntStream.of(bootstrapMethod.getBootstrapArguments()).mapToObj(cp::getConstant).map(constant -> {
            if (constant instanceof ConstantMethodType) {
                return newMethodType((ConstantMethodType) constant, cp);
            } else if (constant instanceof ConstantMethodHandle) {
                return newMethodHandleAndLookup((ConstantMethodHandle) constant, cp, lookup);
            } else {
                String message = "unsupported bootstrap method argument type " + constant;
                throw new EvalException(message);
            }
        }).collect(toList());

        var privateLookup = (Lookup) bootstrabMethodArguments.stream().map(a -> a instanceof Map.Entry ? ((Map.Entry) a).getValue() : null)
                .filter(Objects::nonNull).findFirst()
                .orElseThrow(() -> new EvalException("null private lookup of lambda method"));

        bootstrabMethodArguments = bootstrabMethodArguments.stream().map(a -> a instanceof Map.Entry ? ((Map.Entry) a).getKey() : a).collect(toList());

        bootstrabMethodArguments = concat(of(privateLookup, interfaceMethodName, factoryMethod), bootstrabMethodArguments.stream()).collect(toList());

        CallSite metafactory;
        try {
            metafactory = (CallSite) handler.invokeWithArguments(bootstrabMethodArguments);
        } catch (Throwable e) {
            throw new EvalException(e);
        }

        var lambdaInstance = metafactory.dynamicInvoker();
        Object result;
        try {
            result = lambdaInstance.invokeWithArguments(asList(arguments));
        } catch (Throwable e) {
            throw new EvalException(e);
        }

        return result;
    }

    private static Map.Entry<MethodHandle, Lookup> newMethodHandleAndLookup(ConstantMethodHandle constant,
                                                                            ConstantPool cp, Lookup lookup) {
        var constantMethodref = cp.getConstant(constant.getReferenceIndex(), ConstantMethodref.class);

        var constantNameAndType = cp.getConstant(constantMethodref.getNameAndTypeIndex(), ConstantNameAndType.class);
        var methodName = constantNameAndType.getName(cp);
        var methodSignature = constantNameAndType.getSignature(cp);
        var methodType = fromMethodDescriptorString(methodSignature, null);

        var targetClass = getClassByName(constantMethodref.getClass(cp));

        setAccessibleMethod(targetClass, methodName, methodType);

        var privateLookup = getPrivateLookup(targetClass, lookup);
        return Map.entry(lookupReference(privateLookup, constant.getReferenceKind(), targetClass, methodName, methodType), privateLookup);
    }

    private static Lookup getPrivateLookup(Class<?> targetClass, Lookup lookup) {
        try {
            return privateLookupIn(targetClass, lookup);
        } catch (IllegalAccessException e) {
            throw new EvalException(e);
        }
    }

    private static MethodHandle lookupReference(Lookup lookup, int referenceKind, Class<?> targetClass, String
            methodName, MethodType methodType) {
        setAccessibleMethod(targetClass, methodName, methodType);
        if (referenceKind == REF_invokeSpecial) {
            return lookupSpecial(lookup, targetClass, methodName, methodType);
        } else if (referenceKind == REF_invokeStatic) {
            return lookupStatic(lookup, targetClass, methodName, methodType);
        } else {
            var message = "unsupported method handle referenceKind " + referenceKind;
            throw new EvalException(message);
        }
    }

    private static void setAccessibleMethod(Class<?> targetClass, String methodName, MethodType methodType) {
        try {
            var declaredMethod = targetClass.getDeclaredMethod(methodName, methodType.parameterArray());
            declaredMethod.setAccessible(true);
        } catch (Exception e) {
            throw new EvalException(e);
        }
    }

    private static MethodHandle lookupSpecial(Lookup lookup, Class<?> targetClass, String methodName, MethodType
            methodType) {
        try {
            return lookup.findSpecial(targetClass, methodName, methodType, targetClass);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new EvalException(e);
        }
    }

    private static MethodHandle lookupStatic(Lookup lookup, Class<?> targetClass, String name, MethodType
            methodType) {
        try {
            return lookup.findStatic(targetClass, name, methodType);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new EvalException(e);
        }
    }

    private static MethodType newMethodType(ConstantMethodType constantMethodType, ConstantPool cp) {
        return fromMethodDescriptorString(cp.getConstantUtf8(constantMethodType.getDescriptorIndex()).getBytes(), null);
    }

    static Class[] getArgumentTypes(Type[] argumentTypes) {
        var args = new Class[argumentTypes.length];
        for (int i = 0; i < argumentTypes.length; i++) {
            var argumentType = argumentTypes[i];
            String className = argumentType.getClassName();
            args[i] = getClassByName(className);
        }
        return args;
    }

    static Class<?> getClassByName(String className) {
        Class<?> forName;
        try {
            forName = Class.forName(className);
        } catch (ClassNotFoundException e) {
            Throwable e1 = e;
            throw new EvalException(e1);
        }
        return forName;
    }

    static EvalArgumentsResult evalArguments(Object object,
                                             InstructionHandle instructionHandle, int argumentsCount,
                                             ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods) {
        var args = new Object[argumentsCount];
        var current = instructionHandle;
        for (int i = argumentsCount; i > 0; i--) {
            current = current.getPrev();
            var eval = eval(object, current, constantPoolGen, bootstrapMethods);
            current = eval.getCallInstruction();
            args[i - 1] = eval.getResult();
        }
        return new EvalArgumentsResult(args, current);
    }

    public static CallResult<Object> getFieldValue(Object object, String name, InstructionHandle
            getFieldInstruction, InstructionHandle lastInstruction) {
        var field = getDeclaredField(name, object.getClass());
        return field == null ? notFound(name, getFieldInstruction) : field.trySetAccessible()
                ? success(getValue(object, field), getFieldInstruction, lastInstruction) : notAccessible(field, getFieldInstruction);
    }

    private static Object getValue(Object object, Field field) {
        try {
            return field.get(object);
        } catch (IllegalAccessException e) {
            throw new EvalException(e);
        }
    }

    public static CallResult<Object> callMethod(Object object, Class<?> type,
                                                String methodName, Class[] argTypes, Object[] args,
                                                InstructionHandle invokeInstruction, InstructionHandle lastInstruction,
                                                ConstantPoolGen constantPoolGen) {
        var msg = "callMethod";
        var declaredMethod = getDeclaredMethod(methodName, type, argTypes);
        if (declaredMethod == null) {
            log.info("{}, method not found '{}.{}', instruction {}", msg, type.getName(), methodName, toString(invokeInstruction, constantPoolGen));
            return notFound(methodName, invokeInstruction);
        } else if (declaredMethod.trySetAccessible()) {
            Object result;
            try {
                result = declaredMethod.invoke(object, args);
            } catch (IllegalAccessException e) {
                throw new EvalException(e);
            } catch (InvocationTargetException e) {
                throw new EvalException(e.getTargetException());
            }
            if (log.isDebugEnabled()) {
                log.debug("{}, success, method '{}.{}', result: {}, instruction {}", msg, type.getName(), methodName, result, toString(invokeInstruction, constantPoolGen));
            }
            return success(result, invokeInstruction, lastInstruction);
        } else {
            log.warn("{}, method is not accessible, method '{}.{}', instruction {}", msg, type.getName(), methodName, toString(invokeInstruction, constantPoolGen));
        }
        return notAccessible(declaredMethod, invokeInstruction);
    }

    private static String toString(InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen) {
        return instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
    }

    @Data
    static class EvalArgumentsResult {
        private final Object[] result;
        private final InstructionHandle instructionHandle;
    }

    @Data
    @Builder
    public static class CallResult<T> {
        private final T result;
        private final Set<Status> status;
        private final Object source;
        private final InstructionHandle callInstruction;
        private final InstructionHandle lastInstruction;

        public static <T> CallResult<T> success(T value, InstructionHandle callInstruction, InstructionHandle lastInstruction) {
            return CallResult.<T>builder().result(value).callInstruction(callInstruction).lastInstruction(lastInstruction).build();
        }

        public static <T> CallResult<T> notAccessible(Object source, InstructionHandle callInstruction) {
            return CallResult.<T>builder().status(Set.of(notAccessible)).source(source).callInstruction(callInstruction).build();
        }

        public static <T> CallResult<T> notFound(Object source, InstructionHandle callInstruction) {
            return CallResult.<T>builder().status(Set.of(notFound)).source(source).callInstruction(callInstruction).build();
        }

        public T getResult() {
            throwResultExceptionIfInvalidStatus();
            return result;
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
