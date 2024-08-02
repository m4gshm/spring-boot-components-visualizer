package io.github.m4gshm.connections;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.InstructionHandle;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

import static io.github.m4gshm.connections.ReflectionUtils.CallResult.notAccessible;
import static io.github.m4gshm.connections.ReflectionUtils.CallResult.notFound;
import static io.github.m4gshm.connections.ReflectionUtils.CallResult.success;

@UtilityClass
public class ReflectionUtils {

    public static CallResult callStaticMethod(Class<?> type, String methodName, Class[] argTypes, Object[] args, InstructionHandle instructionHandle) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        var declaredMethod = type.getDeclaredMethod(methodName, argTypes);
        return declaredMethod.trySetAccessible()
                ? success(declaredMethod.invoke(type, args), instructionHandle)
                : notAccessible(declaredMethod);
    }

    public static CallResult getFieldValue(Object object, String name, InstructionHandle instructionHandle) throws IllegalAccessException {
        var aClass = object.getClass();
        var field = getField(name, aClass);
        return field == null ? notFound(name) : field.trySetAccessible() ? success(field.get(object), instructionHandle) : notAccessible(field);
    }

    private static Field getField(String name, Class<?> aClass) {
        while (!(aClass == null || Object.class.equals(aClass))) try {
            return aClass.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            aClass = aClass.getSuperclass();
        }
        return null;
    }

    @Data
    @Builder
    public static class CallResult {
        private final Object result;
        private final boolean notAccessible;
        private final boolean notFound;
        private final Object source;
        private final InstructionHandle lastInstruction;

        public static CallResult success(Object value, InstructionHandle lastInstruction) {
            return CallResult.builder().result(value).lastInstruction(lastInstruction).build();
        }

        public static CallResult notAccessible(Object source) {
            return CallResult.builder().notAccessible(true).source(source).build();
        }

        public static CallResult notFound(Object source) {
            return CallResult.builder().notFound(true).source(source).build();
        }
    }

}
