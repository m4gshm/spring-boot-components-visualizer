package io.github.m4gshm.connections.bytecode;

import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.BootstrapMethodHandlerAndArguments;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.classfile.Utility;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.Type;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractorUtils.getDeclaredField;
import static io.github.m4gshm.connections.Utils.classByName;
import static io.github.m4gshm.connections.Utils.loadedClass;
import static io.github.m4gshm.connections.bytecode.EvalBytecode.Result.*;
import static java.util.Arrays.asList;
import static java.util.stream.Stream.ofNullable;
import static java.util.stream.StreamSupport.stream;
import static org.springframework.aop.support.AopUtils.getTargetClass;

@Slf4j
@UtilityClass
public class EvalBytecodeUtils {

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

    static Result invoke(MethodHandle methodHandle, Object[] arguments, InstructionHandle firstInstruction,
                         InstructionHandle lastArgInstruction, EvalBytecode evalBytecode, Result parent) {
        try {
            var value = methodHandle.invokeWithArguments(asList(arguments));
            return constant(value, firstInstruction, lastArgInstruction, evalBytecode, parent);
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
                                    Class<?> type, Class<?>[] argumentTypes, Object[] arguments,
                                    EvalBytecode evalBytecode, Result parent) {
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor(argumentTypes);
        } catch (NoSuchMethodException e) {
            throw new EvalBytecodeException(e);
        }
        if (constructor.trySetAccessible()) try {
            return constant(constructor.newInstance(arguments), instructionHandle, evalBytecode, parent);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new EvalBytecodeException(e);
        } catch (IllegalArgumentException e) {
            throw e;
        }
        else {
            return notAccessible(constructor, instructionHandle, parent);
        }
    }

    static Result callBootstrapMethod(@NonNull Object[] arguments, InstructionHandle instructionHandle,
                                      @NonNull InstructionHandle lastArgInstruction, EvalBytecode evalBytecode,
                                      BootstrapMethodHandlerAndArguments methodAndArguments, Result parent) {
        var callSite = getCallSite(methodAndArguments);
        var lambdaInstance = callSite.dynamicInvoker();
        return invoke(lambdaInstance, arguments, instructionHandle, lastArgInstruction, evalBytecode, parent);
    }

    private static CallSite getCallSite(BootstrapMethodHandlerAndArguments methodAndArguments) {
        try {
            return (CallSite) methodAndArguments.getHandler().invokeWithArguments(methodAndArguments.getBootstrapMethodArguments());
        } catch (Throwable e) {
            throw new EvalBytecodeException(e);
        }
    }

    public static Class<?>[] toClasses(Type[] types) {
        var args = new Class[types.length];
        for (int i = 0; i < types.length; i++) {
            var argumentType = types[i];
            args[i] = toClass(argumentType.getClassName());
        }
        return args;
    }

    public static Class<?> toClass(String rawClassName) {
        var className = rawClassName.replace("/", ".");
        return getClassByName(className);
    }

    public static Result getFieldValue(Result result, String name, InstructionHandle instructionHandle,
                                       InstructionHandle lastInstruction, ConstantPoolGen constantPoolGen,
                                       EvalBytecode evalBytecode,
                                       Result parent) {
        var instructionText = getInstructionString(instructionHandle, constantPoolGen);
        return delay(instructionText, instructionHandle, evalBytecode, parent, (thisDelay, needResolve, unevaluatedHandler) -> {
            var object = result.getValue(unevaluatedHandler);
            return getFieldValue(getTargetObject(object), getTargetClass(object), name, instructionHandle,
                    lastInstruction, evalBytecode, thisDelay);
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
                                       InstructionHandle getFieldInstruction, InstructionHandle lastInstruction,
                                       EvalBytecode evalBytecode, Result parent) {
        var field = getDeclaredField(name, objectClass);
        return field == null ? Result.notFound(name, getFieldInstruction, parent) : field.trySetAccessible()
                ? getFieldValue(object, field, lastInstruction, evalBytecode, parent)
                : notAccessible(field, getFieldInstruction, parent);
    }

    private static Result getFieldValue(Object object, Field field, InstructionHandle lastInstruction,
                                        EvalBytecode evalBytecode, Result parent) {
        try {
            return constant(field.get(object), lastInstruction, evalBytecode, parent);
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

    public static String getInstructionString(InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen) {
        return instructionHandle.getPosition() + ": " + instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
    }

    public static String toString(Method method) {
        final String access = Utility.accessToString(method.getAccessFlags());
        return Utility.methodSignatureToString(method.getSignature(), method.getName(), access, true, method.getLocalVariableTable());
    }

    public static Class<?> getClassByName(String className) {
        try {
            return classByName(className);
        } catch (ClassNotFoundException e) {
            throw new EvalBytecodeException(e);
        }
    }

    @FunctionalInterface
    public interface MethodHandleLookup {
        MethodHandle get() throws NoSuchMethodException, IllegalAccessException;
    }

}
