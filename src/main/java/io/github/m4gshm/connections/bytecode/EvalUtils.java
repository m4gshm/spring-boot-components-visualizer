package io.github.m4gshm.connections.bytecode;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.ConstantInvokeDynamic;
import org.apache.bcel.classfile.ConstantMethodHandle;
import org.apache.bcel.classfile.ConstantMethodType;
import org.apache.bcel.classfile.ConstantMethodref;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.generic.AASTORE;
import org.apache.bcel.generic.ALOAD;
import org.apache.bcel.generic.ANEWARRAY;
import org.apache.bcel.generic.ASTORE;
import org.apache.bcel.generic.CHECKCAST;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.ConstantPushInstruction;
import org.apache.bcel.generic.DUP;
import org.apache.bcel.generic.GETFIELD;
import org.apache.bcel.generic.INVOKEDYNAMIC;
import org.apache.bcel.generic.INVOKEINTERFACE;
import org.apache.bcel.generic.INVOKESTATIC;
import org.apache.bcel.generic.INVOKEVIRTUAL;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InvokeInstruction;
import org.apache.bcel.generic.LDC;
import org.apache.bcel.generic.Type;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Set;
import java.util.stream.IntStream;

import static io.github.m4gshm.connections.ReflectionUtils.getDeclaredField;
import static io.github.m4gshm.connections.ReflectionUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.bytecode.EvalUtils.CallResult.Status.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalUtils.CallResult.Status.notFound;
import static io.github.m4gshm.connections.bytecode.EvalUtils.CallResult.notAccessible;
import static io.github.m4gshm.connections.bytecode.EvalUtils.CallResult.notFound;
import static io.github.m4gshm.connections.bytecode.EvalUtils.CallResult.success;
import static io.github.m4gshm.connections.bytecode.EvalException.newInvalidEvalException;
import static io.github.m4gshm.connections.bytecode.EvalException.newUnsupportedEvalException;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;
import static org.apache.bcel.Const.CONSTANT_InvokeDynamic;
import static org.apache.bcel.Const.CONSTANT_NameAndType;
import static org.apache.bcel.Const.REF_invokeSpecial;
import static org.apache.bcel.Const.REF_invokeStatic;
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
    )  {
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = getArgumentTypes(instruction.getArgumentTypes(constantPoolGen));
        var evalArgumentsResult = evalArguments(object, instructionHandle, argumentTypes.length, constantPoolGen, bootstrapMethods);
        var arguments = evalArgumentsResult.getResult();
        if (instruction instanceof INVOKEVIRTUAL) {
            var next = evalArgumentsResult.getInstructionHandle().getPrev();
            var objectCallResult = eval(object, next, constantPoolGen, bootstrapMethods);
            var obj = objectCallResult.getResult();
            return callMethod(obj, obj.getClass(), methodName, argumentTypes, arguments, instructionHandle, objectCallResult.getLastInstruction(), constantPoolGen);
        } else if (instruction instanceof INVOKEINTERFACE) {
            var next = evalArgumentsResult.getInstructionHandle().getPrev();
            var objectCallResult = eval(object, next, constantPoolGen, bootstrapMethods);
            var obj = objectCallResult.getResult();
            return callMethod(obj, getClassByName(instruction.getClassName(constantPoolGen)), methodName, argumentTypes, arguments, instructionHandle, objectCallResult.getLastInstruction(), constantPoolGen);
        } else if (instruction instanceof INVOKEDYNAMIC) {
            return callBootstrapMethod(object, instructionHandle, instruction, constantPoolGen, bootstrapMethods);
        } else if (instruction instanceof INVOKESTATIC) {
            return callMethod(null, getClassByName(instruction.getClassName(constantPoolGen)), methodName, argumentTypes, arguments, instructionHandle, instructionHandle, constantPoolGen);
        }
        throw new UnsupportedOperationException("invoke: " + instruction.toString(constantPoolGen.getConstantPool()));
    }

    static CallResult<Object> callBootstrapMethod(Object object, InstructionHandle instructionHandle, InvokeInstruction instruction, ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods) {
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


        var targetClass = getClassByName(bootstrapMethodref.getClass(cp));
        var referenceKind = bootstrapMethodHandle.getReferenceKind();

        var lookup = MethodHandles.lookup();
        var handler = lookupReference(lookup, referenceKind, targetClass, nameAndType.getName(cp), nameAndType.getSignature(cp));

        var arguments = IntStream.of(bootstrapMethod.getBootstrapArguments()).mapToObj(cp::getConstant).map(constant -> {
            if (constant instanceof ConstantMethodType) {
                return newMethodType((ConstantMethodType) constant, cp);
            } else if (constant instanceof ConstantMethodHandle) {
                return newMethodHandle((ConstantMethodHandle) constant, cp, lookup);
            } else {
                String message = "unsupported bootstrap method argument type " + constant;
                throw new EvalException(message);
            }
        }).collect(toList());

        var factoryClass = factoryMethod.parameterArray()[0];
        var privateLookup = getPrivateLookup(lookup, factoryClass);
        var args = concat(of(privateLookup, interfaceMethodName, factoryMethod), arguments.stream()).collect(toList());

        CallSite metafactory;
        try {
            metafactory = (CallSite) handler.invokeWithArguments(args);
        } catch (Throwable e) {
            Throwable e1 = e;
            throw new EvalException(e1);
        }

        var methodHandle = metafactory.dynamicInvoker();
        Object result;
        try {
            result = methodHandle.invoke(object);
        } catch (Throwable e) {
            Throwable e1 = e;
            throw new EvalException(e1);
        }

        return success(result, instructionHandle, instructionHandle);
    }

    private static MethodHandle newMethodHandle(ConstantMethodHandle constant, ConstantPool cp, Lookup lookup) {
        var constantMethodref = cp.getConstant(constant.getReferenceIndex(), ConstantMethodref.class);

        var constantNameAndType = cp.getConstant(constantMethodref.getNameAndTypeIndex(), ConstantNameAndType.class);
        var methodName = constantNameAndType.getName(cp);
        var methodSignature = constantNameAndType.getSignature(cp);

        var targetClass = getClassByName(constantMethodref.getClass(cp));
        var privateLookup = getPrivateLookup(lookup, targetClass);
        int referenceKind = constant.getReferenceKind();
        return lookupReference(privateLookup, referenceKind, targetClass, methodName, methodSignature);
    }

    private static MethodHandle lookupReference(Lookup lookup, int referenceKind, Class<?> targetClass, String methodName, String methodSignature) {
        if (referenceKind == REF_invokeSpecial) {
            return lookupSpecial(lookup, targetClass, methodName, methodSignature);
        } else if (referenceKind == REF_invokeStatic) {
            return lookupStatic(lookup, targetClass, methodName, methodSignature);
        } else {
            String message = "unsupported method handle referenceKind " + referenceKind;
            throw new EvalException(message);
        }
    }

    private static MethodHandle lookupSpecial(Lookup lookup, Class<?> targetClass, String methodName, String methodSignature) {
        try {
            return lookup.findSpecial(targetClass, methodName, fromMethodDescriptorString(methodSignature, null), targetClass);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            Throwable e1 = e;
            throw new EvalException(e1);
        }
    }

    private static MethodHandle lookupStatic(Lookup lookup, Class<?> targetClass, String name, String signature) {
        MethodType methodType = fromMethodDescriptorString(signature, null);
        try {
            return lookup.findStatic(targetClass, name, methodType);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            Throwable e1 = e;
            throw new EvalException(e1);
        }
    }

    private static MethodType newMethodType(ConstantMethodType constantMethodType, ConstantPool cp) {
        return fromMethodDescriptorString(cp.getConstantUtf8(constantMethodType.getDescriptorIndex()).getBytes(), null);
    }

    private static Lookup getPrivateLookup(Lookup lookup, Class<?> targetClass) {
        final Lookup privateLookup;
        try {
            privateLookup = MethodHandles.privateLookupIn(targetClass, lookup);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return privateLookup;
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

    public static CallResult<Object> getFieldValue(Object object, String name, InstructionHandle getFieldInstruction, InstructionHandle lastInstruction) {
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
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new EvalException(e);
            }
            if (log.isDebugEnabled()) {
                log.debug("{}, success, method '{}.{}', result: {}, instruction {}", msg, type.getName(), methodName, result, toString(invokeInstruction, constantPoolGen));
            }
            return success(result, invokeInstruction, lastInstruction);
        } else
            log.warn("{}, method is not accessible, method '{}.{}', instruction {}", msg, type.getName(), methodName, toString(invokeInstruction, constantPoolGen));
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
