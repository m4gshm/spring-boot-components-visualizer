package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Component;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.core.env.Environment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BinaryOperator;
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
            logUnsupportedClass(log, e);
            return null;
        }
    }

    public static void logUnsupportedClass(Logger log, NoClassDefFoundError e) {
        if (log.isDebugEnabled()) {
            log.info("Class is not supported", e);
        } else {
            log.info("Class is not supported, {}", e.getLocalizedMessage());
        }
    }

    public static String getApplicationName(Environment environment) {
        return environment.getProperty("spring.application.name", "application");
    }

    static <T> BinaryOperator<T> warnDuplicated() {
        return (l, r) -> {
            if (l != r) {
                log.warn("Duplicate components detected: first {}, second {}", l, r);
            }
            return l;
        };
    }
}
