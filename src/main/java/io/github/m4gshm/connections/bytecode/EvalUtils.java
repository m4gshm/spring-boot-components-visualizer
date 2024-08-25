package io.github.m4gshm.connections.bytecode;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.INVOKEDYNAMIC;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;
import org.springframework.aop.SpringProxy;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredField;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.Utils.loadedClass;
import static io.github.m4gshm.connections.bytecode.EvalResult.*;
import static java.lang.invoke.MethodHandles.privateLookupIn;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.Map.entry;
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
        componentType = unproxy(componentType);
        try {
            return Repository.lookupClass(componentType);
        } catch (ClassNotFoundException e) {
            throw new EvalException(e);
        }
    }

    public static Class<?> unproxy(Class<?> componentType) {
        if (componentType == null) {
            return null;
        }
        var springProxyClass = loadedClass(() -> SpringProxy.class);
        if (springProxyClass != null && componentType.getName().contains("$$") && springProxyClass.isAssignableFrom(componentType)) {
            componentType = componentType.getSuperclass();
        }
        return componentType;
    }

    public static EvalResult<Object> eval(Object object, InstructionHandle instructionHandle,
                                          ConstantPoolGen constantPoolGen, LocalVariableTable localVariableTable,
                                          BootstrapMethods bootstrapMethods, Code code) {
        return new Eval(constantPoolGen, localVariableTable, bootstrapMethods, code).eval(object, instructionHandle);
    }

    static Object invoke(MethodHandle methodHandle, List<Object> arguments) {
        try {
            return methodHandle.invokeWithArguments(arguments);
        } catch (Throwable e) {
            throw new EvalException(e);
        }
    }

    static MethodHandle getMethodHandle(MethodHandleLookup lookup) {
        MethodHandle constructor;
        try {
            constructor = lookup.get();
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new EvalException(e);
        }
        return constructor;
    }

    static EvalResult<Object> instantiateObject(InstructionHandle instructionHandle,
                                                Class<?> type, Class[] argumentTypes, Object[] arguments) {
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor(argumentTypes);
        } catch (NoSuchMethodException e) {
            throw new EvalException(e);
        }
        if (constructor.trySetAccessible()) try {
            return success(constructor.newInstance(arguments), instructionHandle, instructionHandle);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new EvalException(e);
        }
        else {
            return notAccessible(constructor, instructionHandle);
        }
    }

    static Object callBootstrapMethod(Object object, Object[] arguments, INVOKEDYNAMIC instruction,
                                      ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods) {
        var cp = constantPoolGen.getConstantPool();
        var constantInvokeDynamic = cp.getConstant(instruction.getIndex(),
                CONSTANT_InvokeDynamic, ConstantInvokeDynamic.class);
        var nameAndTypeIndex = constantInvokeDynamic.getNameAndTypeIndex();

        var constantNameAndType = cp.getConstant(nameAndTypeIndex, ConstantNameAndType.class);
        var interfaceMethodName = constantNameAndType.getName(cp);

        var factoryMethod = fromMethodDescriptorString(constantNameAndType.getSignature(cp), null);

        int bootstrapMethodAttrIndex = constantInvokeDynamic.getBootstrapMethodAttrIndex();
        var bootstrapMethod = bootstrapMethods.getBootstrapMethods()[bootstrapMethodAttrIndex];
        var bootstrapMethodHandle = cp.getConstant(bootstrapMethod.getBootstrapMethodRef(),
                CONSTANT_MethodHandle, ConstantMethodHandle.class);
        var bootstrapMethodref = cp.getConstant(bootstrapMethodHandle.getReferenceIndex(),
                CONSTANT_Methodref, ConstantMethodref.class);

        var nameAndType = cp.getConstant(bootstrapMethodref.getNameAndTypeIndex(),
                CONSTANT_NameAndType, ConstantNameAndType.class);

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

        var privateLookup = (Lookup) bootstrabMethodArguments.stream().map(a -> a instanceof Map.Entry
                        ? ((Map.Entry<?, ?>) a).getValue() : null)
                .filter(Objects::nonNull).findFirst()
                .orElseThrow(() -> new EvalException("null private lookup of lambda method"));

        bootstrabMethodArguments = bootstrabMethodArguments.stream().map(a -> a instanceof Map.Entry
                ? ((Map.Entry<?, ?>) a).getKey() : a).collect(toList());

        bootstrabMethodArguments = concat(of(privateLookup, interfaceMethodName, factoryMethod),
                bootstrabMethodArguments.stream()).collect(toList());

        CallSite metafactory;
        try {
            metafactory = (CallSite) handler.invokeWithArguments(bootstrabMethodArguments);
        } catch (Throwable e) {
            throw new EvalException(e);
        }

        var lambdaInstance = metafactory.dynamicInvoker();

        return invoke(lambdaInstance, asList(arguments));
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
        return entry(lookupReference(privateLookup, constant.getReferenceKind(), targetClass, methodName, methodType),
                privateLookup);
    }

    static Lookup getPrivateLookup(Class<?> targetClass, Lookup lookup) {
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
        } else if (referenceKind == REF_invokeVirtual) {
            return lookupVirtual(lookup, targetClass, methodName, methodType);
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

    private static MethodHandle lookupVirtual(Lookup lookup, Class<?> targetClass, String methodName, MethodType
            methodType) {
        try {
            return lookup.findVirtual(targetClass, methodName, methodType);
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
        switch (className) {
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "char":
                return char.class;
            case "short":
                return short.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "void":
                return void.class;
        }
        Class<?> forName;
        try {
            forName = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new EvalException(e);
        }
        return forName;
    }

    public static EvalResult<Object> getFieldValue(Object object, String name, InstructionHandle
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

    static EvalResult<Object> callMethod(Object object, Class<?> type,
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

    @FunctionalInterface
    public interface MethodHandleLookup {
        MethodHandle get() throws NoSuchMethodException, IllegalAccessException;
    }

}
