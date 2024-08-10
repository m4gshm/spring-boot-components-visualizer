package io.github.m4gshm.connections;

import feign.InvocationHandlerFactory;
import feign.MethodMetadata;
import feign.Target;
import io.github.m4gshm.connections.ComponentsExtractor.FeignClient;
import io.github.m4gshm.connections.ComponentsExtractor.JmsClient;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ReflectionUtils.getFieldValue;
import static io.github.m4gshm.connections.model.Interface.Direction.in;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static java.lang.reflect.Proxy.isProxyClass;
import static java.util.Arrays.asList;
import static java.util.Map.entry;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Stream.of;
import static java.util.stream.Stream.ofNullable;
import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;
import static org.springframework.core.annotation.AnnotatedElementUtils.getMergedRepeatableAnnotations;

@Slf4j
@UtilityClass
public class ComponentsExtractorUtils {
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
        return !(isSpringBootTest(type) || (isSpringConfiguration(type))
                || isVisualizeAPI(type) || isProperties(type));
    }

    private static boolean isProperties(Class<?> beanType) {
        return hasAnnotation(beanType, () -> ConfigurationProperties.class);
    }

    static boolean isSpringBootTest(Class<?> beanType) {
        return hasAnnotation(beanType, () -> SpringBootTest.class);
    }

    static boolean isSpringConfiguration(Class<?> beanType) {
        return hasAnnotation(beanType, () -> Configuration.class);
    }

    static boolean isVisualizeAPI(Class<?> beanType) {
        return OnApplicationReadyEventConnectionsVisualizeGenerator.Storage.class.isAssignableFrom(beanType);
    }

    static <T extends Annotation> boolean hasAnnotation(Class<?> type, Supplier<Class<T>> annotationSupplier) {
        return getAnnotation(type, annotationSupplier) != null;
    }

    static <T extends Annotation> T getAnnotation(Class<?> aClass, Supplier<Class<T>> supplier) {
        var annotationClass = Utils.loadedClass(supplier);
        if (annotationClass == null) {
            return null;
        }
        T annotation;
        while ((annotation = findMergedAnnotation(aClass, annotationClass)) == null) {
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
        var annotationClass = Utils.loadedClass(supplier);
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
        var annotationClass = Utils.loadedClass(supplier);
        return annotationClass == null ? Map.of() : elements.stream()
                .collect(toMap(element -> element, element -> getMergedRepeatableAnnotations(element, annotationClass)));

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
        return beanType != null && hasAnnotation(beanType, () -> SpringBootApplication.class);//&& hasMainMethod(beanType);
    }

    static String getHttpInterfaceName(String method, String url) {
        return method == null || method.isEmpty() ? url : method + ":" + url;
    }

    static List<JmsClient> extractMethodJmsListeners(Class<?> beanType) {
        var annotationMap = getMergedRepeatableAnnotationsMap(asList(beanType.getMethods()), () -> JmsListener.class);
        return annotationMap.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(annotation -> entry(entry.getKey(), annotation)))
                .map(entry -> JmsClient.builder()
                        .direction(in)
                        .name(entry.getKey().getName())
                        .destination(entry.getValue().destination())
                        .build())
                .collect(toList());
    }

    static FeignClient extractFeignClient(String name, ConfigurableApplicationContext context) {
        try {
            var bean = context.getBean(name);
            if (!isProxyClass(bean.getClass())) {
                return null;
            }
            var handler = Proxy.getInvocationHandler(bean);
            var handlerClass = handler.getClass();
            if ("org.springframework.aop.framework.JdkDynamicAopProxy".equals(handlerClass.getName())) {
                var advised = getFieldValue(handler, "advised");
                if (advised instanceof ProxyFactory) {
                    var pf = (ProxyFactory) advised;
                    var targetSource = pf.getTargetSource();
                    Object target;
                    try {
                        target = targetSource.getTarget();
                    } catch (Exception e) {
                        log.debug("extractFeignClient bean, getTarget, {}", name, e);
                        return null;
                    }
                    if (!isProxyClass(target.getClass())) {
                        return null;
                    }

                    handler = Proxy.getInvocationHandler(target);
                    handlerClass = handler.getClass();
                } else {
                    //log
                }
            }
            if (!"FeignInvocationHandler".equals(handlerClass.getSimpleName())) {
                return null;
            }

            var httpMethods = ((Collection<?>) ((Map<?, ?>) getFieldValue(handler, "dispatch")).values()
            ).stream().map(value -> (InvocationHandlerFactory.MethodHandler) value).map(value -> {
                var buildTemplateFromArgs = getFieldValue(value, "buildTemplateFromArgs");
                var metadata = (MethodMetadata) getFieldValue(buildTemplateFromArgs, "metadata");
                var template = metadata.template();
                var method = template.method();
                var url = template.url();
                return HttpMethod.builder().method(method).url(url).build();
            }).collect(toList());

            var target = (Target<?>) getFieldValue(handler, "target");
            if (target == null) {
                //log
                return null;
            }
            var type = target.type();
            return FeignClient.builder()
                    .type(type)
                    .name(target.name())
                    .url(target.url())
                    .httpMethods(httpMethods)
                    .build();
        } catch (NoSuchBeanDefinitionException e) {
            log.debug("extractFeignClient bean {}", name, e);
            return null;
        }
    }

    static String getComponentPath(Package rootPackage, Class<?> componentType) {
        if (rootPackage != null) {
            var rootPackageName = rootPackage.getName();
            var typePackageName = componentType.getPackage().getName();
            if (typePackageName.startsWith(rootPackageName)) {
                var path = typePackageName.substring(rootPackageName.length());
                return path.startsWith(".") ? path.substring(1) : path;
            }
        }
        return "";
    }

    static <T> Component findDependencyByType(Collection<Component> dependencies, Supplier<Class<T>> classSupplier) {
        var type = Utils.loadedClass(classSupplier);
        return type != null ? dependencies.stream()
                .filter(component -> type.isAssignableFrom(component.getType()))
                .findFirst().orElse(null) : null;
    }

}