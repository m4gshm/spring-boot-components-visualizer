package io.github.m4gshm.components.visualizer;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;

import static java.util.Arrays.stream;

@Slf4j
public class StubFactoryImpl implements StubFactory {


    @Override
    public Object create(Class<?> type) {
        return create2(type, new HashSet<Class<?>>());
    }

    private Object create2(Class<?> type, HashSet<Class<?>> touched) {
        if (type == null) {
            return null;
        } else if (void.class.equals(type)) {
            return null;
        } else if (boolean.class.equals(type) || Boolean.class.equals(type)) {
            return false;
        } else if (byte.class.equals(type) || Byte.class.equals(type)) {
            return (byte) 0;
        } else if (short.class.equals(type) || Short.class.equals(type)) {
            return (short) 0;
        } else if (int.class.equals(type) || Integer.class.equals(type)) {
            return 0;
        } else if (long.class.equals(type) || Long.class.equals(type)) {
            return 0L;
        } else if (float.class.equals(type) || Float.class.equals(type)) {
            return 0F;
        } else if (double.class.equals(type) || Double.class.equals(type)) {
            return 0D;
        } else if (char.class.equals(type) || Character.class.equals(type)) {
            return (char) 0;
        } else if (String.class.equals(type)) {
            return "";
        } else if (Enum.class.isAssignableFrom(type)) {
            var enumConstants = type.getEnumConstants();
            var enumValue = enumConstants.length > 0 ? enumConstants[0] : null;
            //log
            return enumValue;
        } else if (LocalDate.class.equals(type)) {
            return LocalDate.now();
        } else if (LocalDateTime.class.equals(type)) {
            return LocalDateTime.now();
        } else if (LocalTime.class.equals(type)) {
            return LocalTime.now();
        } else if (OffsetDateTime.class.equals(type)) {
            return OffsetDateTime.now();
        } else if (OffsetTime.class.equals(type)) {
            return OffsetTime.now();
        } else if (ZonedDateTime.class.equals(type)) {
            return ZonedDateTime.now();
        } else if (Instant.class.equals(type)) {
            return Instant.now();
        } else if (Duration.class.equals(type)) {
            return Duration.ofNanos(0);
        } else if (HashMap.class.equals(type)) {
            return new HashMap<>();
        } else if (LinkedHashMap.class.equals(type)) {
            return new LinkedHashMap<>();
        } else if (TreeMap.class.equals(type)) {
            return new TreeMap<>();
        } else if (HashSet.class.equals(type)) {
            return new HashSet<>();
        } else if (LinkedHashSet.class.equals(type)) {
            return new LinkedHashSet<>();
        } else if (ArrayList.class.equals(type)) {
            return new ArrayList<>();
        } else if (LinkedList.class.equals(type)) {
            return new LinkedList<>();
        } else if (Collection.class.equals(type) || List.class.equals(type)) {
            return List.of();
        } else if (Set.class.equals(type)) {
            return Set.of();
        } else if (Map.class.equals(type)) {
            return Map.of();
        } else if (type.isInterface()) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> {
                //log
                return create(method.getReturnType());
            });
        } else {
            touched.add(type);
            return stream(type.getConstructors()).filter(constructor -> {
                var accessible = constructor.trySetAccessible();
                if (!accessible) {
                    log.info("not accessible constructor {} of type {}", constructor, type.getName());
                }
                return accessible;
            }).map(constructor -> {
                var parameterTypes = constructor.getParameterTypes();
                var parameters = stream(parameterTypes).map(paramType -> {
                    boolean alreadyTouched = touched.contains(paramType);
                    if (alreadyTouched) {
                        return null;
                    } else {
                        return create2(paramType, touched);
                    }
                }).toArray();
                try {
                    return constructor.newInstance(parameters);
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Failed to create instance of {}", constructor.getDeclaringClass(), e);
                    } else {
                        log.warn("Failed to create instance of {}, error: {}, message: {}", constructor.getDeclaringClass(),
                                e.getClass().getSimpleName(), e.getMessage());
                    }
                    return null;
                }
            }).filter(Objects::nonNull).findFirst().orElseThrow(() -> new StubFactoryException("cannot stub " + type));
        }
    }
}
