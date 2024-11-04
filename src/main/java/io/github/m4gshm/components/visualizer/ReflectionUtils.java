package io.github.m4gshm.components.visualizer;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.AccessibleObject;

@Slf4j
public class ReflectionUtils {
    public static boolean trySetAccessible(AccessibleObject accessibleObject) {
        try {
            accessibleObject.setAccessible(true);
            return true;
        } catch (Exception e) {
            log.info("cannot set access {}", accessibleObject, e);
        }
        return false;
    }
}
