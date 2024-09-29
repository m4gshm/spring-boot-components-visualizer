package io.github.m4gshm.connections.bytecode;

import java.util.Objects;

public class MethodUtils {
    public static boolean isGoThroughMethod(Class<?> objectClass, String methodName, Class<?>[] argumentClasses) {
        //todo need to analyze bytecode instead of hardcode method names
        if (Objects.class.equals(objectClass) && "requireNonNull".equals(methodName)) {
            return true;
        }
        return false;
    }
}
