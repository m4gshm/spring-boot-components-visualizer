package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval.ParameterValue;
import io.github.m4gshm.components.visualizer.eval.result.Delay;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.classfile.Utility;
import org.apache.bcel.generic.*;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.ComponentsExtractorUtils.getDeclaredField;
import static io.github.m4gshm.components.visualizer.Utils.classByName;
import static io.github.m4gshm.components.visualizer.Utils.loadedClass;
import static io.github.m4gshm.components.visualizer.eval.result.Result.*;
import static java.util.Arrays.asList;
import static java.util.Map.entry;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.of;
import static java.util.stream.Stream.ofNullable;
import static java.util.stream.StreamSupport.stream;

@Slf4j
@UtilityClass
public class EvalUtils {

    public static final String CGLIB_CLASS_SEPARATOR = "$$";
    private static final Class<SpringProxy> springProxyClass = loadedClass(() -> SpringProxy.class);

    public static List<JavaClass> getClassSources(Class<?> componentType) {
        try {
            return lookupClassSources(componentType);
        } catch (ClassNotFoundException e) {
            log.debug("getClassInheritanceHierarchy {}", componentType, e);
            return List.of();
        }
    }

    public static List<JavaClass> lookupClassSources(Class<?> aClass) throws ClassNotFoundException {
        var classes = new ArrayList<JavaClass>();
        var javaClass = Repository.lookupClass(unproxy(aClass));
        classes.add(javaClass);
        var className = javaClass.getClassName();
        try {
            classes.addAll(asList(javaClass.getAllInterfaces()));
        } catch (ClassNotFoundException e) {
//            if (log.isDebugEnabled()) {
            log.error("get interfaces error of {}", className, e);
//            }
            throw e;
        }
        try {
            for (javaClass = javaClass.getSuperClass(); javaClass != null; javaClass = javaClass.getSuperClass()) {
                classes.add(javaClass);
            }
        } catch (ClassNotFoundException e) {
//            if (log.isDebugEnabled()) {
            log.error("get superclass error of {}", className, e);
//            }
            throw e;
        }
        return classes;
    }

    public static Class<?> unproxy(Class<?> componentType) {
        if (componentType == null) {
            return null;
        } else if (Proxy.isProxyClass(componentType)) {
            var interfaces = componentType.getInterfaces();
            var firstInterface = Arrays.stream(interfaces).findFirst().orElse(null);
            if (interfaces.length > 1) {
                log.debug("unproxy {} as first interface {}", componentType, firstInterface);
            }
            return firstInterface;
        } else if (componentType.getName().contains(CGLIB_CLASS_SEPARATOR) && springProxyClass != null
                && springProxyClass.isAssignableFrom(componentType)) {
            componentType = componentType.getSuperclass();
        }
        return componentType;
    }

    static Result invoke(MethodHandle methodHandle, Object[] arguments, InstructionHandle firstInstruction,
                         InstructionHandle lastArgInstruction, Type expectedType,
                         Eval eval, List<ParameterValue> parameters) {
        try {
            var value = methodHandle.invokeWithArguments(asList(arguments));
            return invoked(value, expectedType, firstInstruction, lastArgInstruction, null, eval, parameters);
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

    static Result instantiateObject(InstructionHandle instructionHandle, Class<?> type, Class<?>[] argumentTypes,
                                    Object[] arguments, Delay parent, Eval eval) {
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor(argumentTypes);
        } catch (NoSuchMethodException e) {
            throw new EvalException(e);
        }
        if (constructor.trySetAccessible()) try {
            var value = constructor.newInstance(arguments);
            return constant(value, ObjectType.getType(type), instructionHandle, instructionHandle,
                    null, eval, parent.getRelations()
            );
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException |
                 InvocationTargetException e) {
            throw new IllegalInvokeException(e, constructor, instructionHandle, parent);
        }
        else {
            return notAccessible(constructor, instructionHandle, parent, eval);
        }
    }

    static Result callBootstrapMethod(@NonNull Object[] arguments, InstructionHandle instructionHandle,
                                      @NonNull InstructionHandle lastArgInstruction, Type expectedType, Eval eval,
                                      List<ParameterValue> parameters, CallSite callSite) {
        return invoke(callSite.dynamicInvoker(), arguments, instructionHandle, lastArgInstruction, expectedType,
                eval, parameters);
    }

    public static CallSite getCallSite(MethodHandle handler, List<Object> bootstrapMethodArguments) {
        try {
            return (CallSite) handler.invokeWithArguments(bootstrapMethodArguments);
        } catch (Throwable e) {
            throw new EvalException(e);
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
                                       Result parent, Eval eval) {
        var field = getDeclaredField(objectClass, name);
        return field == null ? Result.notFound(name, getFieldInstruction, parent, eval) : field.trySetAccessible()
                ? getFieldValue(object, field, lastInstruction, eval)
                : notAccessible(field, getFieldInstruction, parent, eval);
    }

    private static Result getFieldValue(Object object, Field field, InstructionHandle lastInstruction,
                                        Eval eval) {
        try {
            return constant(field.get(object), ObjectType.getType(field.getType()), lastInstruction, lastInstruction,
                    eval, List.of()
            );
        } catch (IllegalAccessException e) {
            throw new EvalException(e);
        }
    }

    public static String toString(InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen) {
        return instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
    }

    public static Stream<InstructionHandle> instructionHandleStream(Method method) {
        return instructionHandleStream(method.getCode());
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

    public static Class<?> getClassByName(@NonNull String className) {
        try {
            return classByName(className);
        } catch (ClassNotFoundException e) {
            throw new EvalException(e);
        }
    }

    public static Class<?> findClassByName(@NonNull String className) {
        try {
            return classByName(className);
        } catch (ClassNotFoundException e) {
            //log
            return null;
        }
    }

    public static String stringForLog(Type[] argumentTypes) {
        return Arrays.stream(argumentTypes).map(t -> t + "").reduce((l, r) -> l + "," + r).orElse("");
    }

    public static Predicate<Method> byArgs(Type... argTypes) {
        return method -> Arrays.equals(method.getArgumentTypes(), argTypes);
    }

    public static Predicate<Method> byName(String methodName) {
        return method -> method.getName().equals(methodName);
    }

    public static Predicate<Method> byNameAndArgs(String methodName, Type[] argTypes) {
        return byName(methodName).and(byArgs(argTypes));
    }

    public static Entry<JavaClass, Method> getClassAndMethodSource(Class<?> type, String methodName, Type[] argTypes) {
        var javaClasses = getClassSources(type);
        var filter = byNameAndArgs(methodName, argTypes);
        return javaClasses.stream().map(javaClass -> {
            var filteredMethods = getMethodStream(javaClass, filter);
            var method = filteredMethods.findFirst().orElse(null);
            return method != null ? entry(javaClass, method) : null;
        }).filter(Objects::nonNull).findFirst().orElse(null);
    }

    public static List<Method> getMethodsSource(JavaClass javaClass, Predicate<Method> filter) {
        return getMethodStream(javaClass, filter).collect(toList());
    }

    private static Stream<Method> getMethodStream(JavaClass javaClass, Predicate<Method> filter) {
        return of(javaClass.getMethods()).filter(filter);
    }

    @FunctionalInterface
    public interface MethodHandleLookup {
        MethodHandle get() throws NoSuchMethodException, IllegalAccessException;
    }

}
