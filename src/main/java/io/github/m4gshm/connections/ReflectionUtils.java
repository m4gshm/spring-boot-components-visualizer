package io.github.m4gshm.connections;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


@Slf4j
@UtilityClass
public class ReflectionUtils {

    public static Field getDeclaredField(String name, Class<?> type) {
        while (!(type == null || Object.class.equals(type))) try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            type = type.getSuperclass();
        }
        return null;
    }

    public static Object getFieldValue(Object object, String name) {
        var aClass = object.getClass();
        var field = getDeclaredField(name, aClass);
        if (field == null) {
            return null;
        }
        field.setAccessible(true);
        try {
            return field.get(object);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    public static Method getDeclaredMethod(String name, Class<?> type, Class<?>[] argumentTypes) {
        while (!(type == null || Object.class.equals(type))) try {
            return type.getDeclaredMethod(name, argumentTypes);
        } catch (NoSuchMethodException e) {
            type = type.getSuperclass();
        }
        return null;
    }


}
