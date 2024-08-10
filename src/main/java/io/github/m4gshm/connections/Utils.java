package io.github.m4gshm.connections;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@Slf4j
public class Utils {
    public static <T> Collector<T, ?, LinkedHashSet<T>> toLinkedHashSet() {
        return Collectors.toCollection(LinkedHashSet::new);
    }

    public static <T> Class<T> loadedClass(Supplier<Class<T>> classSupplier) {
        try {
            return classSupplier.get();
        } catch (NoClassDefFoundError e) {
            if (log.isDebugEnabled()) {
                log.info("Class is not supported", e);
            } else {
                log.info("Class is not supported, {}", e.getLocalizedMessage());
            }
            return null;
        }
    }
}
