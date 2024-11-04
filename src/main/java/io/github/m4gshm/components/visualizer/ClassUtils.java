package io.github.m4gshm.components.visualizer;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ClassUtils {
    public static String getPackageName(Class<?> clazz) {
        return clazz != null ? getPackageName(clazz.getPackage()): null;
    }

    public static String getPackageName(Package aPackage) {
        return aPackage != null ? aPackage.getName() : null;
    }
}
