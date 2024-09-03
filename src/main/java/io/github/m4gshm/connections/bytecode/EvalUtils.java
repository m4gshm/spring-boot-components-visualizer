package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.Eval.Value;
import io.github.m4gshm.connections.model.Component;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;
import org.springframework.aop.SpringProxy;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredField;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.Utils.classByName;
import static io.github.m4gshm.connections.Utils.loadedClass;
import static io.github.m4gshm.connections.bytecode.Eval.Result.*;
import static io.github.m4gshm.connections.bytecode.Eval.Value.constant;
import static java.lang.invoke.MethodHandles.privateLookupIn;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.*;
import static java.util.stream.StreamSupport.stream;
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

    public static Eval.Result eval(Object object, String componentName, InstructionHandle instructionHandle,
                                   ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods,
                                   Method method, Collection<Component> components,
                                   ConfigurableApplicationContext context) {
        var eval = new Eval(context, object, componentName, object.getClass(), constantPoolGen, bootstrapMethods, method, components);
        return eval.eval(instructionHandle);
    }

    static Value invoke(MethodHandle methodHandle, List<Object> arguments) {
        try {
            return constant(methodHandle.invokeWithArguments(arguments));
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

    
    static Eval.Result instantiateObject(InstructionHandle instructionHandle,
                                         Class<?> type, Class[] argumentTypes, Object[] arguments) {
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor(argumentTypes);
        } catch (NoSuchMethodException e) {
            throw new EvalException(e);
        }
        if (constructor.trySetAccessible()) try {
            return success(Value.constant(constructor.newInstance(arguments)), instructionHandle, instructionHandle);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new EvalException(e);
        }
        else {
            return notAccessible(constructor, instructionHandle);
        }
    }

    static Value callBootstrapMethod(Object[] arguments, INVOKEDYNAMIC instruction,
                                     ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods) {
        var cp = constantPoolGen.getConstantPool();
        var constantInvokeDynamic = cp.getConstant(instruction.getIndex(),
                CONSTANT_InvokeDynamic, ConstantInvokeDynamic.class);
        var nameAndTypeIndex = constantInvokeDynamic.getNameAndTypeIndex();

        var constantNameAndType = cp.getConstant(nameAndTypeIndex, ConstantNameAndType.class);
        var interfaceMethodName = constantNameAndType.getName(cp);

        var factoryMethod = fromMethodDescriptorString(constantNameAndType.getSignature(cp), null);

        var bootstrapMethodAttrIndex = constantInvokeDynamic.getBootstrapMethodAttrIndex();
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

        bootstrabMethodArguments = concat(of(selectedLookup, interfaceMethodName, factoryMethod),
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

    private static MethodHandleAndLookup newMethodHandleAndLookup(ConstantMethodHandle constant,
                                                                  ConstantPool cp, Lookup lookup) {
        var constantMethodref = cp.getConstant(constant.getReferenceIndex(), ConstantMethodref.class);

        var constantNameAndType = cp.getConstant(constantMethodref.getNameAndTypeIndex(), ConstantNameAndType.class);
        var methodName = constantNameAndType.getName(cp);
        var methodSignature = constantNameAndType.getSignature(cp);
        var methodType = fromMethodDescriptorString(methodSignature, null);

        var targetClass = getClassByName(constantMethodref.getClass(cp));

        setAccessibleMethod(targetClass, methodName, methodType);

        var privateLookup = getPrivateLookup(targetClass, lookup);
        return new MethodHandleAndLookup(
                lookupReference(privateLookup, constant.getReferenceKind(), targetClass, methodName, methodType),
                privateLookup
        );
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
            throw new EvalException(e);
        }
    }

    public static Eval.Result getFieldValue(Object object, String name, InstructionHandle
            getFieldInstruction, InstructionHandle lastInstruction) {
        return getFieldValue(object, object.getClass(), name, getFieldInstruction, lastInstruction);
    }

    public static Eval.Result getFieldValue(Object object, Class<?> objectClass, String name, InstructionHandle
            getFieldInstruction, InstructionHandle lastInstruction) {
        var field = getDeclaredField(name, objectClass);
        return field == null ? notFound(name, getFieldInstruction) : field.trySetAccessible()
                ? success(getFieldValue(object, field), getFieldInstruction, lastInstruction) : notAccessible(field, getFieldInstruction);
    }

    private static Value getFieldValue(Object object, Field field) {
        try {
            return constant(field.get(object));
        } catch (IllegalAccessException e) {
            throw new EvalException(e);
        }
    }

    static Eval.Result callMethod(Object object, Class<?> type,
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
            return success(constant(result), invokeInstruction, lastInstruction);
        } else {
            log.warn("{}, method is not accessible, method '{}.{}', instruction {}", msg, type.getName(), methodName, toString(invokeInstruction, constantPoolGen));
        }
        return notAccessible(declaredMethod, invokeInstruction);
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

    protected static Class[] getArgumentTypes(InvokeInstruction instruction, ConstantPoolGen constantPoolGen) {
        return getArgumentTypes(instruction.getArgumentTypes(constantPoolGen));
    }

    @FunctionalInterface
    public interface MethodHandleLookup {
        MethodHandle get() throws NoSuchMethodException, IllegalAccessException;
    }

    @Data
    @FieldDefaults(makeFinal = true)
    private static class MethodHandleAndLookup {
        MethodHandle methodHandle;
        Lookup lookup;
    }

}
