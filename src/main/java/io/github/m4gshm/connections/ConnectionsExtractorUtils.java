package io.github.m4gshm.connections;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;

import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Map.entry;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Stream.of;
import static java.util.stream.Stream.ofNullable;
import static org.springframework.core.annotation.AnnotatedElementUtils.getMergedAnnotation;

@Slf4j
@UtilityClass
public class ConnectionsExtractorUtils {
    static boolean hasMainMethod(Class<?> beanType) {
        return of(beanType.getMethods()).anyMatch(method -> method.getName().equals("main")
                && isOnlyOneArgStringArray(method)
                && method.getReturnType().equals(void.class)
                && isStatic(method.getModifiers()) && isPublic(method.getModifiers()));
    }

    static boolean isOnlyOneArgStringArray(Method method) {
        var parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 1 && String[].class.equals(parameterTypes[0]);
    }

    static boolean isIncluded(Class<?> type) {
        return !(isSpringBootTest(type) || isSpringConfiguration(type) || isStorage(type));
    }

    static boolean isSpringBootTest(Class<?> beanType) {
        return hasAnnotation(beanType, () -> SpringBootTest.class);
    }

    static boolean isSpringConfiguration(Class<?> beanType) {
        return hasAnnotation(beanType, () -> Configuration.class);
    }

    static boolean isStorage(Class<?> beanType) {
        return OnApplicationReadyEventConnectionsVisualizeGenerator.Storage.class.isAssignableFrom(beanType);
    }

    static <T extends Annotation> boolean hasAnnotation(Class<?> type, Supplier<Class<T>> annotationSupplier) {
        return getAnnotation(type, annotationSupplier) != null;
    }

    static <T extends Annotation> T getAnnotation(Class<?> aClass, Supplier<Class<T>> supplier) {
        var annotationClass = getAnnotationClass(supplier);
        if (annotationClass == null) {
            return null;
        }
        T annotation;
        while ((annotation = getMergedAnnotation(aClass, annotationClass)) == null) {
            aClass = aClass.getSuperclass();
            if (aClass == null || Object.class.equals(aClass)) {
                break;
            }
        }
        return annotation;

    }

    static <T extends Annotation, E extends AnnotatedElement> Map<T, E> getAnnotationMap(
            Collection<E> elements, Supplier<Class<T>> supplier
    ) {
        var annotationClass = getAnnotationClass(supplier);
        return annotationClass == null ? Map.of() : elements.stream()
                .flatMap(element -> ofNullable(getMergedAnnotation(element, annotationClass))
                        .map(annotation -> entry(annotation, element)))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (l, r) -> l, LinkedHashMap::new));
    }

    private static <T extends Annotation> Class<T> getAnnotationClass(Supplier<Class<T>> supplier) {
        final Class<T> annotationClass;
        try {
            annotationClass = supplier.get();
        } catch (NoClassDefFoundError e) {
            log.error("getAnnotatedMethods error", e);
            return null;
        }
        return annotationClass;
    }

    public static Collection<Method> getMethods(Class<?> type) {
        var methods = new LinkedHashSet<>(Arrays.asList(type.getMethods()));
        var superclass = type.getSuperclass();
        var superMethods = (superclass != null && !Object.class.equals(superclass)) ?
                getMethods(superclass) : List.<Method>of();

        methods.addAll(superMethods);
        return methods;
    }
}
