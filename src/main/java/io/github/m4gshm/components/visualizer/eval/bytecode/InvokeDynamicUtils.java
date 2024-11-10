package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.INVOKEDYNAMIC;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.getClassByName;
import static io.github.m4gshm.components.visualizer.eval.bytecode.MethodInfo.newMethodInfo;
import static java.lang.invoke.MethodHandles.privateLookupIn;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.bcel.Const.*;
import static org.aspectj.apache.bcel.Constants.CONSTANT_MethodHandle;
import static org.aspectj.apache.bcel.Constants.CONSTANT_Methodref;

@Slf4j
@UtilityClass
public class InvokeDynamicUtils {
    public static BootstrapMethodHandlerAndArguments getBootstrapMethodHandlerAndArguments(
            INVOKEDYNAMIC instruction, BootstrapMethods bootstrapMethods, @NonNull ConstantPoolGen constantPoolGen
    ) {
        var constantPool = constantPoolGen.getConstantPool();
        var bootstrapMethod = getBootstrapMethod(instruction, bootstrapMethods, constantPool);
        var bootstrapMethodInfo = getBootstrapMethodInfo(bootstrapMethod, constantPool);

//        var isLambda = LambdaMetafactory.class.getName().equals(bootstrapMethodInfo.getClassName())
//                && "metafactory".equals(bootstrapMethodInfo.getMethodName());
//        if (isLambda) {
//            var methodType = bootstrapMethodInfo.methodType;
//            var types = new Class[5];
//            arraycopy(methodType.parameterArray(), 0, types, 0, 4);
//            types[4] = Class[].class;
//
//            bootstrapMethodInfo = bootstrapMethodInfo.toBuilder()
//                    .methodName("altMetafactory")
//                    .methodType(MethodType.methodType(methodType.returnType(), types))
//                    .build();
//        }

        var lookup = MethodHandles.lookup();
        var handler = lookupReference(lookup,
                bootstrapMethodInfo.referenceKind, getClassByName(bootstrapMethodInfo.className),
                bootstrapMethodInfo.methodName, bootstrapMethodInfo.methodType);
        var invokeDynamicInterfaceInfo = getInvokeDynamicInterfaceInfo(instruction, constantPool);
        var bootstrapMethodArguments = getBootstrapMethodArguments(invokeDynamicInterfaceInfo, bootstrapMethod,
                lookup, constantPool, bootstrapMethodInfo);
//        if (isLambda) {
//            var newBootstrapMethodArguments = new ArrayList<Object>(bootstrapMethodArguments.subList(0, 4));
//            var tail = new Object[bootstrapMethodArguments.size() - 4 + 1];
//            for (var i = 4; i < bootstrapMethodArguments.size(); i++) {
//                tail[i - 4] = bootstrapMethodArguments.get(i);
//            }
//            tail[tail.length - 1] = FLAG_SERIALIZABLE;
//            newBootstrapMethodArguments.add(tail);
//            return new BootstrapMethodHandlerAndArguments(handler, newBootstrapMethodArguments);
//        } else {
            return new BootstrapMethodHandlerAndArguments(handler, bootstrapMethodArguments);
//        }
    }

    public static BootstrapMethodInfo getBootstrapMethodInfo(BootstrapMethod bootstrapMethod, ConstantPool constantPool) {
        var constantMethodHandle = constantPool.getConstant(bootstrapMethod.getBootstrapMethodRef(),
                CONSTANT_MethodHandle, ConstantMethodHandle.class);
        var bootstrapMethodRef = getBootstrapMethodRef(constantMethodHandle.getReferenceIndex(), constantPool);
        var className = bootstrapMethodRef.getClass(constantPool);
        var bootstrapMethodNameAndType = getBootstrapMethodNameAndType(bootstrapMethodRef, constantPool);
        var methodName = bootstrapMethodNameAndType.getName(constantPool);
        var referenceKind = constantMethodHandle.getReferenceKind();
        var methodType = fromMethodDescriptorString(bootstrapMethodNameAndType.getSignature(constantPool), null);
        return new BootstrapMethodInfo(className, methodName, referenceKind, methodType);
    }

    private static ConstantNameAndType getBootstrapMethodNameAndType(ConstantMethodref bootstrapMethodRef, ConstantPool cp) {
        return cp.getConstant(bootstrapMethodRef.getNameAndTypeIndex(),
                CONSTANT_NameAndType, ConstantNameAndType.class);
    }

    private static ConstantMethodref getBootstrapMethodRef(int referenceIndex, ConstantPool cp) {
        return cp.getConstant(referenceIndex, CONSTANT_Methodref, ConstantMethodref.class);
    }

    private static List<Object> getBootstrapMethodArguments(InvokeDynamicInterfaceInfo invokeDynamicInterfaceInfo,
                                                            BootstrapMethod bootstrapMethod,
                                                            Lookup lookup, ConstantPool cp, BootstrapMethodInfo bootstrapMethodInfo) {
        var bootstrabMethodArguments = getBootstrapMethodArgumentsFromConstants(bootstrapMethod, cp).stream().map(constant -> {
            if (constant instanceof ConstantMethodType) {
                return newMethodType((ConstantMethodType) constant, cp);
            } else if (constant instanceof ConstantMethodHandle) {
                return newMethodHandleAndLookup((ConstantMethodHandle) constant, lookup, cp);
            } else if (constant instanceof ConstantObject) {
                return ((ConstantObject) constant).getConstantValue(cp);
            } else {
                throw new EvalException("unsupported bootstrap method argument type " + constant);
            }
        }).collect(toList());

        var selectedLookup = bootstrabMethodArguments.stream().map(a -> a instanceof MethodHandleAndLookup
                        ? ((MethodHandleAndLookup) a).getLookup() : null)
                .filter(Objects::nonNull).findFirst()
                .orElseGet(() -> {
                    log.debug("null private lookup of lambda method {}", bootstrapMethod.toString(cp));
                    return lookup;
                });

        bootstrabMethodArguments = bootstrabMethodArguments.stream().map(a -> a instanceof MethodHandleAndLookup
                ? ((MethodHandleAndLookup) a).getMethodHandle() : a).collect(toList());

        bootstrabMethodArguments = concat(of(selectedLookup, invokeDynamicInterfaceInfo.methodName,
                        invokeDynamicInterfaceInfo.methodType),
                bootstrabMethodArguments.stream()).collect(toList());
        return bootstrabMethodArguments;
    }

    public static BootstrapMethod getBootstrapMethod(INVOKEDYNAMIC instruction, BootstrapMethods bootstrapMethods,
                                                     ConstantPool constantPool) {
        var constantInvokeDynamic = getConstantInvokeDynamic(instruction, constantPool);
        return bootstrapMethods.getBootstrapMethods()[constantInvokeDynamic.getBootstrapMethodAttrIndex()];
    }

    public static List<Constant> getBootstrapMethodArgumentsFromConstants(BootstrapMethod bootstrapMethod, ConstantPool cp) {
        return IntStream.of(bootstrapMethod.getBootstrapArguments()).mapToObj(cp::<Constant>getConstant).collect(toList());
    }

    private static InvokeDynamicInterfaceInfo getInvokeDynamicInterfaceInfo(INVOKEDYNAMIC instruction, ConstantPool constantPool) {
        var constantInvokeDynamic = getConstantInvokeDynamic(instruction, constantPool);
        int nameAndTypeIndex = constantInvokeDynamic.getNameAndTypeIndex();
        var constantNameAndType = constantPool.getConstant(nameAndTypeIndex, ConstantNameAndType.class);
        var interfaceMethodName = constantNameAndType.getName(constantPool);
        var factoryMethod = fromMethodDescriptorString(constantNameAndType.getSignature(constantPool), null);
        return new InvokeDynamicInterfaceInfo(interfaceMethodName, factoryMethod);
    }

    private static ConstantInvokeDynamic getConstantInvokeDynamic(INVOKEDYNAMIC instruction, ConstantPool constantPool) {
        return constantPool.getConstant(instruction.getIndex(), CONSTANT_InvokeDynamic, ConstantInvokeDynamic.class);
    }

    private static MethodHandleAndLookup newMethodHandleAndLookup(ConstantMethodHandle constant,
                                                                  Lookup lookup, ConstantPool constantPool) {
        var methodInfo = requireNonNull(newMethodInfo(constant, constantPool), "cannot extract invokedynamic methodInfo");

        var methodType = fromMethodDescriptorString(methodInfo.getSignature(), null);
        var targetClass = methodInfo.getObjectType();
        var methodName = methodInfo.getName();
        setAccessibleMethod(targetClass, methodName, methodType);

        var privateLookup = getPrivateLookup(targetClass, lookup);
        var methodHandle = lookupReference(privateLookup, constant.getReferenceKind(), targetClass, methodName, methodType);
        return new MethodHandleAndLookup(methodHandle, privateLookup);
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

    public static MethodInfo getInvokeDynamicUsedMethodInfo(INVOKEDYNAMIC instruction, ConstantPoolGen constantPoolGen,
                                                            BootstrapMethods bootstrapMethods) {
        return getInvokeDynamicUsedMethodInfo(instruction, bootstrapMethods, constantPoolGen);
    }

    public static MethodInfo getInvokeDynamicUsedMethodInfo(INVOKEDYNAMIC instruction, BootstrapMethods bootstrapMethods,
                                                            ConstantPoolGen constantPoolGen) {
        var constantPool = constantPoolGen.getConstantPool();
        var bootstrapMethod = getBootstrapMethod(instruction, bootstrapMethods, constantPool);
        var bootstrapMethodArguments = getBootstrapMethodArgumentsFromConstants(bootstrapMethod, constantPool);
        return bootstrapMethodArguments.stream().map(constant -> constant instanceof ConstantMethodHandle
                ? newMethodInfo((ConstantMethodHandle) constant, constantPool) : null
        ).filter(Objects::nonNull).findFirst().orElse(null);
    }

    @Data
    @Builder(toBuilder = true)
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class BootstrapMethodInfo {
        String className;
        String methodName;
        int referenceKind;
        MethodType methodType;
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class BootstrapMethodHandlerAndArguments {
        MethodHandle handler;
        List<Object> bootstrapMethodArguments;
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class InvokeDynamicInterfaceInfo {
        String methodName;
        MethodType methodType;
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    private static class MethodHandleAndLookup {
        MethodHandle methodHandle;
        Lookup lookup;
    }
}
