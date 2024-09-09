package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import lombok.Data;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredField;
import static io.github.m4gshm.connections.Utils.classByName;
import static io.github.m4gshm.connections.Utils.loadedClass;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.*;
import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.MethodInfo.newMethodInfo;
import static java.lang.invoke.MethodHandles.privateLookupIn;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.*;
import static java.util.stream.StreamSupport.stream;
import static org.apache.bcel.Const.*;
import static org.aspectj.apache.bcel.Constants.CONSTANT_MethodHandle;
import static org.aspectj.apache.bcel.Constants.CONSTANT_Methodref;
import static org.springframework.aop.support.AopUtils.getTargetClass;

@Slf4j
@UtilityClass
public class EvalBytecodeUtils {

    public static JavaClass lookupClass(Class<?> componentType) {
        componentType = unproxy(componentType);
        try {
            return Repository.lookupClass(componentType);
        } catch (ClassNotFoundException e) {
            throw new EvalBytecodeException(e);
        }
    }

    public static List<JavaClass> lookupClassInheritanceHierarchy(Class<?> componentType) {
        ArrayList<JavaClass> classes = new ArrayList<>();
        componentType = unproxy(componentType);
        JavaClass javaClass;
        try {
            javaClass = Repository.lookupClass(componentType);
        } catch (ClassNotFoundException e) {
            throw new EvalBytecodeException(e);
        }
        classes.add(javaClass);
        try {
            for (javaClass = javaClass.getSuperClass(); javaClass != null; javaClass = javaClass.getSuperClass()) {
                classes.add(javaClass);
            }
        } catch (ClassNotFoundException e) {
            if (log.isDebugEnabled()) {
                log.debug("get superclass error of {}", javaClass.getClassName(), e);
            }
        }
        return classes;
    }

    public static Class<?> unproxy(Class<?> componentType) {
        if (componentType == null) {
            return null;
        }
        if (Proxy.isProxyClass(componentType)) {
            var interfaces = componentType.getInterfaces();
            var firstInterface = Arrays.stream(interfaces).findFirst().orElse(null);
            if (interfaces.length > 1) {
                log.debug("unproxy {} as first interface {}", componentType, firstInterface);
            }
            return firstInterface;
        }
        var springProxyClass = loadedClass(() -> SpringProxy.class);
        if (componentType.getName().contains("$$")) {
            if (springProxyClass == null || !springProxyClass.isAssignableFrom(componentType)) {
                log.debug("detected CGLIB proxy class that doesn't implements SpringProxy interface, {}", componentType);
            }
            componentType = componentType.getSuperclass();
        }
        return componentType;
    }

    static Result invoke(MethodHandle methodHandle, List<Object> arguments, InstructionHandle lastArgInstruction) {
        try {
            return constant(methodHandle.invokeWithArguments(arguments), lastArgInstruction);
        } catch (Throwable e) {
            throw new EvalBytecodeException(e);
        }
    }

    static MethodHandle getMethodHandle(MethodHandleLookup lookup) {
        MethodHandle constructor;
        try {
            constructor = lookup.get();
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new EvalBytecodeException(e);
        }
        return constructor;
    }


    static Result instantiateObject(InstructionHandle instructionHandle,
                                    Class<?> type, Class<?>[] argumentTypes, Object[] arguments) {
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor(argumentTypes);
        } catch (NoSuchMethodException e) {
            throw new EvalBytecodeException(e);
        }
        if (constructor.trySetAccessible()) try {
            return constant(constructor.newInstance(arguments), instructionHandle);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new EvalBytecodeException(e);
        } catch (IllegalArgumentException e) {
            throw e;
        }
        else {
            return notAccessible(constructor, instructionHandle);
        }
    }

    static Result callBootstrapMethod(@NonNull Object[] arguments, @NonNull INVOKEDYNAMIC instruction,
                                      @NonNull ConstantPoolGen constantPoolGen, @NonNull BootstrapMethods bootstrapMethods,
                                      @NonNull InstructionHandle lastArgInstruction) {
        var cp = constantPoolGen.getConstantPool();
        var constantInvokeDynamic = getConstantInvokeDynamic(instruction, cp);
        var invokeDynamicInterfaceInfo = getInvokeDynamicInterfaceInfo(constantInvokeDynamic, cp);

        var bootstrapMethodAttrIndex = constantInvokeDynamic.getBootstrapMethodAttrIndex();
        var bootstrapMethod = bootstrapMethods.getBootstrapMethods()[bootstrapMethodAttrIndex];
        var bootstrapMethodHandle = cp.getConstant(bootstrapMethod.getBootstrapMethodRef(),
                CONSTANT_MethodHandle, ConstantMethodHandle.class);
        var bootstrapMethodRef = cp.getConstant(bootstrapMethodHandle.getReferenceIndex(),
                CONSTANT_Methodref, ConstantMethodref.class);
        var bootstrapMethodNameAndType = cp.getConstant(bootstrapMethodRef.getNameAndTypeIndex(),
                CONSTANT_NameAndType, ConstantNameAndType.class);

        var bootstrapClass = getClassByName(bootstrapMethodRef.getClass(cp));
        var bootstrapMethodName = bootstrapMethodNameAndType.getName(cp);
        var bootstrapMethodType = fromMethodDescriptorString(bootstrapMethodNameAndType.getSignature(cp), null);

        var lookup = MethodHandles.lookup();
        var handler = lookupReference(lookup, bootstrapMethodHandle.getReferenceKind(),
                bootstrapClass, bootstrapMethodName, bootstrapMethodType);

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

        CallSite metafactory;
        try {
            metafactory = (CallSite) handler.invokeWithArguments(bootstrabMethodArguments);
        } catch (Throwable e) {
            throw new EvalBytecodeException(e);
        }

        var lambdaInstance = metafactory.dynamicInvoker();
        return invoke(lambdaInstance, asList(arguments), lastArgInstruction);
    }

    public static List<Constant> getBootstrapMethodArguments(BootstrapMethod bootstrapMethod, ConstantPool cp) {
        return IntStream.of(bootstrapMethod.getBootstrapArguments()).mapToObj(cp::<Constant>getConstant).collect(toList());
    }

    public static InvokeDynamicInterfaceInfo getInvokeDynamicInterfaceInfo(ConstantInvokeDynamic constantInvokeDynamic, ConstantPool cp) {
        var constantNameAndType = cp.getConstant(constantInvokeDynamic.getNameAndTypeIndex(), ConstantNameAndType.class);
        var interfaceMethodName = constantNameAndType.getName(cp);
        var factoryMethod = fromMethodDescriptorString(constantNameAndType.getSignature(cp), null);
        return new InvokeDynamicInterfaceInfo(interfaceMethodName, factoryMethod);
    }

    public static ConstantInvokeDynamic getConstantInvokeDynamic(INVOKEDYNAMIC instruction, ConstantPool cp) {
        return cp.getConstant(instruction.getIndex(), CONSTANT_InvokeDynamic, ConstantInvokeDynamic.class);
    }

    private static MethodHandleAndLookup newMethodHandleAndLookup(ConstantMethodHandle constant,
                                                                  ConstantPool cp, Lookup lookup) {
        var result = Objects.requireNonNull(newMethodInfo(constant, cp), "cannot extract invokedynamic methodInfo");

        setAccessibleMethod(result.targetClass, result.methodName, result.methodType);

        var privateLookup = getPrivateLookup(result.targetClass, lookup);
        return new MethodHandleAndLookup(
                lookupReference(privateLookup, constant.getReferenceKind(), result.targetClass, result.methodName, result.methodType),
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

    public static Class[] getArgumentTypes(Type[] argumentTypes) {
        var args = new Class[argumentTypes.length];
        for (int i = 0; i < argumentTypes.length; i++) {
            var argumentType = argumentTypes[i];
            var rawClassName = argumentType.getClassName();
            var className = rawClassName.replace("/", ".");
            args[i] = getClassByName(className);
        }
        return args;
    }

    static Class<?> getClassByName(String className) {
        try {
            return classByName(className);
        } catch (ClassNotFoundException e) {
            throw new EvalBytecodeException(e);
        }
    }

    public static Result getFieldValue(Result result, String name, InstructionHandle getFieldInstruction,
                                       InstructionHandle lastInstruction, ConstantPoolGen constantPoolGen,
                                       Function<Result, Result> unevaluatedHandler) {
        var instructionText = getInstructionString(getFieldInstruction, constantPoolGen);
        return delay(instructionText, () -> lastInstruction, lastInstr -> {
            var object = result.getValue(unevaluatedHandler);
            return getFieldValue(getTargetObject(object), getTargetClass(object), name, getFieldInstruction, lastInstr);
        });
    }

    public static Object getTargetObject(Object candidate) {
        if (AopUtils.isAopProxy(candidate) && candidate instanceof Advised) try {
            return ((Advised) candidate).getTargetSource().getTarget();
        } catch (Exception e) {
            log.error("cglib target object getting error", e);
            return candidate;
        }
        return candidate;
    }

    public static Result getFieldValue(Object object, Class<?> objectClass, String name,
                                       InstructionHandle getFieldInstruction, InstructionHandle lastInstruction) {
        var field = getDeclaredField(name, objectClass);
        return field == null ? Result.notFound(name, getFieldInstruction) : field.trySetAccessible()
                ? getFieldValue(object, field, lastInstruction)
                : notAccessible(field, getFieldInstruction);
    }

    private static Result getFieldValue(Object object, Field field, InstructionHandle lastInstruction) {
        try {
            return constant(field.get(object), lastInstruction);
        } catch (IllegalAccessException e) {
            throw new EvalBytecodeException(e);
        }
    }

    public static String toString(InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen) {
        return instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
    }

    public static Stream<InstructionHandle> instructionHandleStream(Code code) {
        return ofNullable(code).map(Code::getCode).flatMap(bc -> instructionHandleStream(new InstructionList(bc)));
    }

    public static Stream<InstructionHandle> instructionHandleStream(InstructionList instructionHandles) {
        return stream(instructionHandles.spliterator(), false);
    }

    public static Class<?>[] getArgumentTypes(InvokeInstruction instruction, ConstantPoolGen constantPoolGen) {
        return getArgumentTypes(instruction.getArgumentTypes(constantPoolGen));
    }

    public static String getInstructionString(InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen) {
        return instructionHandle.getPosition() + ": " + instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
    }

    @FunctionalInterface
    public interface MethodHandleLookup {
        MethodHandle get() throws NoSuchMethodException, IllegalAccessException;
    }

    @Data
    public static class MethodInfo {
        public final String methodName;
        public final MethodType methodType;
        public final Class<?> targetClass;

        public static MethodInfo newMethodInfo(ConstantMethodHandle constant, ConstantPool cp) {
            var constantCP = cp.getConstant(constant.getReferenceIndex(), ConstantCP.class);
            if (constantCP instanceof ConstantMethodref || constantCP instanceof ConstantInterfaceMethodref) {
                var constantNameAndType = cp.getConstant(constantCP.getNameAndTypeIndex(), ConstantNameAndType.class);
                var methodName = constantNameAndType.getName(cp);
                var methodSignature = constantNameAndType.getSignature(cp);
                var methodType = fromMethodDescriptorString(methodSignature, null);
                var targetClass = getClassByName(constantCP.getClass(cp));
                return new MethodInfo(methodName, methodType, targetClass);
            } else {
                return null;
            }
        }

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
