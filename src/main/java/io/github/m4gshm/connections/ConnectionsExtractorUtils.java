package io.github.m4gshm.connections;

import io.github.m4gshm.connections.ConnectionsExtractor.HttpMethod;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Stream.of;
import static java.util.stream.Stream.ofNullable;
import static org.springframework.core.annotation.AnnotatedElementUtils.getMergedAnnotation;
import static org.springframework.core.annotation.AnnotatedElementUtils.getMergedRepeatableAnnotations;

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

    static <A extends Annotation, E extends AnnotatedElement> Collection<A> getAllMergedAnnotations(
            Collection<E> elements, Supplier<Class<A>> supplier
    ) {
        return getAnnotations(elements, supplier, AnnotatedElementUtils::getAllMergedAnnotations);
    }

    static <A extends Annotation, E extends AnnotatedElement> Set<A> getAnnotations(
            Collection<E> elements, Supplier<Class<A>> supplier, BiFunction<E, Class<A>, Collection<A>> extractor
    ) {
        var annotationClass = getAnnotationClass(supplier);
        if (annotationClass == null) {
            return Set.of();
        } else {
            return elements.stream()
                    .map(element -> extractor.apply(element, annotationClass)).flatMap(Collection::stream)
                    .collect(toCollection(LinkedHashSet::new));
        }
    }

    static <A extends Annotation, E extends AnnotatedElement> Map<E, Collection<A>> getMergedRepeatableAnnotationsMap(
            Collection<E> elements, Supplier<Class<A>> supplier
    ) {
        var annotationClass = getAnnotationClass(supplier);
        return annotationClass == null ? Map.of() : elements.stream()
                .collect(toMap(element -> element, element -> getMergedRepeatableAnnotations(element, annotationClass)));

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

    public static Collection<HttpMethod> extractControllerHttpMethods(Class<?> beanType) {
        var restController = getAnnotation(beanType, () -> Controller.class);
        if (restController == null) {
            return List.of();
        }
        var rootPath = ofNullable(getAnnotation(beanType, () -> RequestMapping.class))
                .map(RequestMapping::path)
                .flatMap(Arrays::stream).findFirst().orElse("");
        return getAllMergedAnnotations(getMethods(beanType), () -> RequestMapping.class).stream().flatMap(requestMapping -> {
            var methods = getHttpMethods(requestMapping);
            return getPaths(requestMapping).stream().map(path -> concatPath(path, rootPath))
                    .flatMap(path -> methods.stream().map(method -> HttpMethod.builder().url(path).method(method).build()));
        }).collect(toCollection(LinkedHashSet::new));
    }

    private static Collection<String> getPaths(RequestMapping requestMapping) {
        var path = List.of(requestMapping.path());
        return path.isEmpty() ? List.of("") : path;
    }

    private static List<String> getHttpMethods(RequestMapping requestMapping) {
        var methods = Stream.of(requestMapping.method()).map(Enum::name).collect(toList());
        return methods.isEmpty() ? List.of("*") : methods;
    }

    private static String concatPath(String path, String root) {
        return (root.endsWith("/") || path.startsWith("/")) ? root + path : root + "/" + path;
    }

    static boolean isSpringBootMainClass(Class<?> beanType) {
        return hasAnnotation(beanType, () -> SpringBootApplication.class) && hasMainMethod(beanType);
    }

    static Object getFieldValue(String name, Object object) {
        Field field;
        try {
            field = object.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
        field.setAccessible(true);
        try {
            return field.get(object);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    static String getHttpInterfaceName(String method, String url) {
        return method == null || method.isEmpty() ? url : method + ":" + url;
    }
}
