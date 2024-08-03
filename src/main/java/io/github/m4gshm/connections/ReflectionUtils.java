package io.github.m4gshm.connections;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.InstructionHandle;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Set;

import static io.github.m4gshm.connections.ReflectionUtils.CallResult.Status.notAccessible;
import static io.github.m4gshm.connections.ReflectionUtils.CallResult.Status.notFound;
import static io.github.m4gshm.connections.ReflectionUtils.CallResult.notAccessible;
import static io.github.m4gshm.connections.ReflectionUtils.CallResult.notFound;
import static io.github.m4gshm.connections.ReflectionUtils.CallResult.success;

@Slf4j
@UtilityClass
public class ReflectionUtils {

    public static CallResult<Object> callMethod(Object object, Class<?> type, String methodName,
                                                Class[] argTypes, Object[] args,
                                                InstructionHandle invokeInstruction,
                                                InstructionHandle lastInstruction,
                                                ConstantPoolGen constantPoolGen
    ) throws IllegalAccessException, InvocationTargetException {
        var msg = "callMethod";
        var declaredMethod = getDeclaredMethod(methodName, type, argTypes);
        if (declaredMethod == null) {
            log.info("{}, method not found '{}.{}', instruction {}",
                    msg, type.getName(), methodName, toString(invokeInstruction, constantPoolGen));
            return notFound(methodName, invokeInstruction);
        } else if (declaredMethod.trySetAccessible()) {
            var result = declaredMethod.invoke(object, args);
            if (log.isDebugEnabled()) {
                log.debug("{}, success, method '{}.{}', result: {}, instruction {}",
                        msg, type.getName(), methodName, result, toString(invokeInstruction, constantPoolGen));
            }
            return success(result, invokeInstruction, lastInstruction);
        } else
            log.warn("{}, method is not accessible, method '{}.{}', instruction {}",
                    msg, type.getName(), methodName, toString(invokeInstruction, constantPoolGen));
        return notAccessible(declaredMethod, invokeInstruction);
    }

    private static String toString(InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen) {
        return instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
    }

    public static CallResult<Object> getFieldValue(Object object, String name,
                                                   InstructionHandle getFieldInstruction, InstructionHandle lastInstruction) throws IllegalAccessException {
        var field = getDeclaterField(name, object.getClass());
        return field == null ? notFound(name, getFieldInstruction) : field.trySetAccessible() ? success(field.get(object),
                getFieldInstruction, lastInstruction) : notAccessible(field, getFieldInstruction);
    }

    private static Field getDeclaterField(String name, Class<?> type) {
        while (!(type == null || Object.class.equals(type))) try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            type = type.getSuperclass();
        }
        return null;
    }

    private static Method getDeclaredMethod(String name, Class<?> type, Class[] argTypes) {
        while (!(type == null || Object.class.equals(type))) try {
            return type.getDeclaredMethod(name, argTypes);
        } catch (NoSuchMethodException e) {
            type = type.getSuperclass();
        }
        return null;
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
            return CallResult.<T>builder().status(Set.of(notAccessible)).source(source)
                    .callInstruction(callInstruction).build();
        }

        public static <T> CallResult<T> notFound(Object source, InstructionHandle callInstruction) {
            return CallResult.<T>builder().status(Set.of(notFound)).source(source)
                    .callInstruction(callInstruction).build();
        }

        public T getResult() {
            if (status != null && !status.isEmpty()) {
                throw new CallResultException(status, source, callInstruction);
            }
            return result;
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
