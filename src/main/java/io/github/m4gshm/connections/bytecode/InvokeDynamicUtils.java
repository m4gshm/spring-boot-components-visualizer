package io.github.m4gshm.connections.bytecode;

import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
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

import static io.github.m4gshm.connections.Utils.classByName;
import static io.github.m4gshm.connections.bytecode.MethodInfo.newMethodInfo;
import static java.lang.invoke.MethodHandles.privateLookupIn;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;
import static org.apache.bcel.Const.*;
import static org.aspectj.apache.bcel.Constants.CONSTANT_MethodHandle;
import static org.aspectj.apache.bcel.Constants.CONSTANT_Methodref;

@Slf4j
@UtilityClass
public class InvokeDynamicUtils {
    public static BootstrapMethodAndArguments getBootstrapMethodAndArguments(
            INVOKEDYNAMIC instruction, BootstrapMethods bootstrapMethods, @NonNull ConstantPoolGen constantPoolGen
    ) {
        var cp = constantPoolGen.getConstantPool();
        var constantInvokeDynamic = getConstantInvokeDynamic(instruction, cp);
        var invokeDynamicInterfaceInfo = getInvokeDynamicInterfaceInfo(constantInvokeDynamic, cp);

        var lookup = MethodHandles.lookup();
        var bootstrapMethod = getBootstrapMethod(bootstrapMethods, constantInvokeDynamic);
        var handler = getBootstrapMethodHandle(bootstrapMethod, lookup, cp);
        var bootstrapMethodArguments = getBootstrapMethodArguments(invokeDynamicInterfaceInfo, bootstrapMethod, lookup, cp);
        return new BootstrapMethodAndArguments(handler, bootstrapMethodArguments);
    }

    private static MethodHandle getBootstrapMethodHandle(BootstrapMethod bootstrapMethod, Lookup lookup, ConstantPool cp) {
        var bootstrapMethodHandle = cp.getConstant(bootstrapMethod.getBootstrapMethodRef(),
                CONSTANT_MethodHandle, ConstantMethodHandle.class);
        var bootstrapMethodRef = cp.getConstant(bootstrapMethodHandle.getReferenceIndex(),
                CONSTANT_Methodref, ConstantMethodref.class);
        var bootstrapMethodNameAndType = cp.getConstant(bootstrapMethodRef.getNameAndTypeIndex(),
                CONSTANT_NameAndType, ConstantNameAndType.class);

        return lookupReference(lookup, bootstrapMethodHandle.getReferenceKind(),
                getClassByName(bootstrapMethodRef.getClass(cp)),
                bootstrapMethodNameAndType.getName(cp),
                fromMethodDescriptorString(bootstrapMethodNameAndType.getSignature(cp), null));
    }

    private static List<Object> getBootstrapMethodArguments(InvokeDynamicInterfaceInfo invokeDynamicInterfaceInfo,
                                                            BootstrapMethod bootstrapMethod,
                                                            Lookup lookup,
                                                            ConstantPool cp) {
        var bootstrabMethodArguments = getBootstrapMethodArguments(bootstrapMethod, cp).stream().map(constant -> {
            if (constant instanceof ConstantMethodType) {
                return newMethodType((ConstantMethodType) constant, cp);
            } else if (constant instanceof ConstantMethodHandle) {
                return newMethodHandleAndLookup((ConstantMethodHandle) constant, cp, lookup);
            } else if (constant instanceof ConstantObject) {
                return ((ConstantObject) constant).getConstantValue(cp);
            } else {
                throw new EvalBytecodeException("unsupported bootstrap method argument type " + constant);
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

    static BootstrapMethod getBootstrapMethod(BootstrapMethods bootstrapMethods, ConstantInvokeDynamic constantInvokeDynamic) {
        return bootstrapMethods.getBootstrapMethods()[constantInvokeDynamic.getBootstrapMethodAttrIndex()];
    }

    public static List<Constant> getBootstrapMethodArguments(BootstrapMethod bootstrapMethod, ConstantPool cp) {
        return IntStream.of(bootstrapMethod.getBootstrapArguments()).mapToObj(cp::<Constant>getConstant).collect(toList());
    }

    public static InvokeDynamicInterfaceInfo getInvokeDynamicInterfaceInfo(
            ConstantInvokeDynamic constantInvokeDynamic, ConstantPool cp
    ) {
        int nameAndTypeIndex = constantInvokeDynamic.getNameAndTypeIndex();
        var constantNameAndType = cp.getConstant(nameAndTypeIndex, ConstantNameAndType.class);
        var interfaceMethodName = constantNameAndType.getName(cp);
        var factoryMethod = fromMethodDescriptorString(constantNameAndType.getSignature(cp), null);
        return new InvokeDynamicInterfaceInfo(interfaceMethodName, factoryMethod);
    }

    public static ConstantInvokeDynamic getConstantInvokeDynamic(INVOKEDYNAMIC instruction, ConstantPool cp) {
        return cp.getConstant(instruction.getIndex(), CONSTANT_InvokeDynamic, ConstantInvokeDynamic.class);
    }

    private static MethodHandleAndLookup newMethodHandleAndLookup(ConstantMethodHandle constant,
                                                                  ConstantPool cp, Lookup lookup) {
        var result = requireNonNull(newMethodInfo(constant, cp), "cannot extract invokedynamic methodInfo");

        var methodType = fromMethodDescriptorString(result.getSignature(), null);
        setAccessibleMethod(result.objectType, result.name, methodType);

        var privateLookup = getPrivateLookup(result.objectType, lookup);
        return new MethodHandleAndLookup(
                lookupReference(privateLookup, constant.getReferenceKind(), result.objectType, result.name,
                        methodType),
                privateLookup
        );
    }

    static Lookup getPrivateLookup(Class<?> targetClass, Lookup lookup) {
        try {
            return privateLookupIn(targetClass, lookup);
        } catch (IllegalAccessException e) {
            throw new EvalBytecodeException(e);
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
            throw new EvalBytecodeException(message);
        }
    }

    private static void setAccessibleMethod(Class<?> targetClass, String methodName, MethodType methodType) {
        try {
            var declaredMethod = targetClass.getDeclaredMethod(methodName, methodType.parameterArray());
            declaredMethod.setAccessible(true);
        } catch (Exception e) {
            throw new EvalBytecodeException(e);
        }
    }

    private static MethodHandle lookupSpecial(Lookup lookup, Class<?> targetClass, String methodName, MethodType
            methodType) {
        try {
            return lookup.findSpecial(targetClass, methodName, methodType, targetClass);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new EvalBytecodeException(e);
        }
    }

    private static MethodHandle lookupStatic(Lookup lookup, Class<?> targetClass, String name, MethodType
            methodType) {
        try {
            return lookup.findStatic(targetClass, name, methodType);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new EvalBytecodeException(e);
        }
    }

    private static MethodHandle lookupVirtual(Lookup lookup, Class<?> targetClass, String methodName, MethodType
            methodType) {
        try {
            return lookup.findVirtual(targetClass, methodName, methodType);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new EvalBytecodeException(e);
        }
    }

    private static MethodType newMethodType(ConstantMethodType constantMethodType, ConstantPool cp) {
        return fromMethodDescriptorString(cp.getConstantUtf8(constantMethodType.getDescriptorIndex()).getBytes(), null);
    }

    static Class<?> getClassByName(String className) {
        try {
            return classByName(className);
        } catch (ClassNotFoundException e) {
            throw new EvalBytecodeException(e);
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class BootstrapMethodAndArguments {
        private final MethodHandle handler;
        private final List<Object> bootstrapMethodArguments;
    }

    @Data
    @FieldDefaults(makeFinal = true)
    public static class InvokeDynamicInterfaceInfo {
        public final String methodName;
        public final MethodType methodType;
    }

    @Data
    @FieldDefaults(makeFinal = true)
    private static class MethodHandleAndLookup {
        MethodHandle methodHandle;
        Lookup lookup;
    }
}
