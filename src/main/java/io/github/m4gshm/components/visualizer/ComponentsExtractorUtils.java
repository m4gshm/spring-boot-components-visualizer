package io.github.m4gshm.components.visualizer;

import feign.InvocationHandlerFactory;
import feign.MethodMetadata;
import feign.Target;
import io.github.m4gshm.components.visualizer.ComponentsExtractor.FeignClient;
import io.github.m4gshm.components.visualizer.ComponentsExtractor.JmsService;
import io.github.m4gshm.components.visualizer.ComponentsExtractor.ScheduledMethod;
import io.github.m4gshm.components.visualizer.client.SchedulingConfigurerUtils;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalContextFactory;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalException;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.model.Component;
import io.github.m4gshm.components.visualizer.model.Component.ComponentKey;
import io.github.m4gshm.components.visualizer.model.HttpMethod;
import io.github.m4gshm.components.visualizer.model.Interface;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.Utils.*;
import static io.github.m4gshm.components.visualizer.model.Component.ComponentKey.newComponentKey;
import static io.github.m4gshm.components.visualizer.model.HttpMethod.ALL;
import static io.github.m4gshm.components.visualizer.model.Interface.Direction.in;
import static io.github.m4gshm.components.visualizer.model.Interface.Type.jms;
import static io.github.m4gshm.components.visualizer.model.Interface.Type.ws;
import static io.github.m4gshm.components.visualizer.model.MethodId.newMethodId;
import static java.lang.reflect.Proxy.isProxyClass;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableSet;
import static java.util.Map.entry;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.*;
import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;
import static org.springframework.core.annotation.AnnotatedElementUtils.getMergedRepeatableAnnotations;

@Slf4j
@UtilityClass
public class ComponentsExtractorUtils {

    public static boolean isIncluded(Class<?> type) {
        return !(isSpringBootTest(type) || isProperties(type));
    }

    public static boolean isProperties(Class<?> beanType) {
        return hasAnnotation(beanType, () -> ConfigurationProperties.class);
    }

    public static boolean isSpringBootTest(Class<?> beanType) {
        return hasAnnotation(beanType, () -> SpringBootTest.class);
    }

    public static boolean isSpringConfiguration(Class<?> beanType) {
        return hasAnnotation(beanType, () -> Configuration.class);
    }

    public static <T extends Annotation> boolean hasAnnotation(Class<?> type, Supplier<Class<T>> annotationSupplier) {
        return getAnnotation(type, annotationSupplier) != null;
    }

    public static <T extends Annotation> T getAnnotation(Class<?> aClass, Supplier<Class<T>> supplier) {
        var annotationClass = loadedClass(supplier);
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

    public static <A extends Annotation, E extends AnnotatedElement> Collection<A> getAllMergedAnnotations(
            Collection<E> elements, Supplier<Class<A>> supplier
    ) {
        return getAnnotations(elements, supplier, AnnotatedElementUtils::getAllMergedAnnotations);
    }

    public static <A extends Annotation, E extends AnnotatedElement> Set<A> getAnnotations(
            Collection<E> elements, Supplier<Class<A>> supplier, BiFunction<E, Class<A>, Collection<A>> extractor
    ) {
        var annotationClass = loadedClass(supplier);
        return annotationClass != null ? elements.stream().map(element -> extractor.apply(element, annotationClass))
                .flatMap(Collection::stream).collect(toCollection(LinkedHashSet::new)) : Set.of();
    }

    public static <A extends Annotation, E extends AnnotatedElement> Map<E, Collection<A>> getMergedRepeatableAnnotationsMap(
            Collection<E> elements, Supplier<Class<A>> supplier
    ) {
        var annotationClass = loadedClass(supplier);
        if (annotationClass != null) {
            return elements.stream().collect(toMap(identity(), element -> {
                return getMergedRepeatableAnnotations(element, annotationClass);
            }, warnDuplicated(), LinkedHashMap::new));
        } else {
            return Map.of();
        }
    }

    public static Collection<Method> getMethods(Class<?> type) {
        var methods = new LinkedHashSet<>(Arrays.asList(type.getMethods()));
        var superclass = type.getSuperclass();
        var superMethods = (superclass != null && !Object.class.equals(superclass)) ? getMethods(superclass) : List.<Method>of();
        methods.addAll(superMethods);
        return methods;
    }

    public static Collection<HttpMethod> extractControllerHttpMethods(Class<?> beanType) {
        var restController = getAnnotation(beanType, () -> Controller.class);
        if (restController == null) {
            return List.of();
        }
        var rootPath = ofNullable(getAnnotation(beanType, () -> RequestMapping.class))
                .map(RequestMapping::path).flatMap(Arrays::stream).findFirst().orElse("");
        return getAllMergedAnnotations(getMethods(beanType), () -> RequestMapping.class).stream().flatMap(requestMapping -> {
            var methods = getHttpMethods(requestMapping);
            return getPaths(requestMapping).stream().map(path -> concatPath(path, rootPath))
                    .flatMap(path -> methods.stream().map(method -> HttpMethod.builder()
                            .path(path)
                            .method(method)
                            .build())
                    );
        }).collect(toCollection(LinkedHashSet::new));
    }

    public static Collection<String> getPaths(RequestMapping requestMapping) {
        var path = List.of(requestMapping.path());
        return path.isEmpty() ? List.of("") : path;
    }

    public static List<String> getHttpMethods(RequestMapping requestMapping) {
        var methods = Stream.of(requestMapping.method()).map(Enum::name).collect(toList());
        return methods.isEmpty() ? List.of(ALL) : methods;
    }

    public static String concatPath(String path, String root) {
        return (root.endsWith("/") || path.startsWith("/")) ? root + path : root + "/" + path;
    }

    public static boolean isSpringBootMainClass(Class<?> beanType) {
        return beanType != null && hasAnnotation(beanType, () -> SpringBootApplication.class);//&& hasMainMethod(beanType);
    }


    public static List<JmsService> extractMethodJmsListeners(Class<?> beanType, ConfigurableBeanFactory beanFactory) {
        var annotationMap = getMergedRepeatableAnnotationsMap(asList(beanType.getMethods()), () -> JmsListener.class);
        return annotationMap.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(annotation -> entry(entry.getKey(), annotation)))
                .map(entry -> JmsService.builder().direction(in).name(entry.getKey().getName())
                        .destination(beanFactory.resolveEmbeddedValue(entry.getValue().destination())).build())
                .collect(toList());
    }

    public static List<ScheduledMethod> extractScheduledMethods(Component component, Class<?> componentType,
                                                                Function<TimeUnit, String> timeUnitStringifier,
                                                                EvalContextFactory evalContextFactory,
                                                                Resolver resolver) {

        var scheduledByConfigurerMethods = SchedulingConfigurerUtils.getScheduledByConfigurerMethods(component,
                componentType, timeUnitStringifier, evalContextFactory, resolver);
        var scheduledByAnnotationMethods = getScheduledByAnnotationMethods(component.getName(), componentType, timeUnitStringifier);
        return Stream.concat(scheduledByConfigurerMethods.stream(), scheduledByAnnotationMethods.stream()).collect(toList());
    }

    public static FeignClient extractFeignClient(String name, Object object) {
        try {
            if (!isProxyClass(object.getClass())) {
                return null;
            }
            var handler = Proxy.getInvocationHandler(object);
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
                } else if (advised == null) {
                    log.info("advised is null");
                } else {
                    log.info("unexpected advised type {}", advised.getClass());
                }
            }
            if (!"FeignInvocationHandler".equals(handlerClass.getSimpleName())) {
                log.info("unexpected feign invocation handler type {}", handlerClass.getSimpleName());
                return null;
            }

            var target = (Target<?>) getFieldValue(handler, "target");
            if (target == null) {
                log.info("FeignInvocationHandler target is null, handler instance {}", handler);
                return null;
            }
            var rootUrl = target.url();

            var httpMethods = ((Collection<?>) ((Map<?, ?>) getFieldValue(handler, "dispatch"))
                    .values()).stream().map(value -> (InvocationHandlerFactory.MethodHandler) value).map(value -> {
                var buildTemplateFromArgs = getFieldValue(value, "buildTemplateFromArgs");
                var metadata = (MethodMetadata) getFieldValue(buildTemplateFromArgs, "metadata");
                var template = metadata.template();
                var httpMethod = template.method();
                var method = metadata.method();
                var url = template.url();
                var methodId = newMethodId(method);
                return HttpMethod.builder().method(httpMethod).path(url).methodSource(methodId).build();
            }).collect(toList());

            var type = target.type();
            return FeignClient.builder().type(type).name(target.name()).url(rootUrl).httpMethods(httpMethods).build();
        } catch (NoSuchBeanDefinitionException e) {
            log.debug("extractFeignClient bean {}", name, e);
            return null;
        }
    }

    public static <T> Component findDependencyByType(Collection<Component> dependencies, Supplier<Class<T>> classSupplier) {
        var type = loadedClass(classSupplier);
        return type != null && dependencies != null ? dependencies.stream().filter(component -> {
            return type.isAssignableFrom(component.getType());
        }).findFirst().orElse(null) : null;
    }

    public static Field getDeclaredField(Class<?> type, String name) {
        while (!(type == null || Object.class.equals(type))) try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            type = type.getSuperclass();
        }
        return null;
    }

    @SneakyThrows
    public static Object invokeMethod(Object object, String name, Class<?>... argumentTypes) {
        return getDeclaredMethod(object.getClass(), name, argumentTypes).invoke(object);
    }

    public static Object getFieldValue(Object object, String name) {
        return getFieldValue(object, name, true);
    }

    public static Object getFieldValue(Object object, Field field) {
        return getFieldValue(object, field, true);
    }

    public static Object getFieldValue(Object object, String name, boolean throwException) {
        var field = getDeclaredField(object.getClass(), name);
        return field == null ? null : getFieldValue(object, field, throwException);
    }

    public static Object getFieldValue(Object object, Field field, boolean throwException) {
        try {
            field.setAccessible(true);
            return field.get(object);
        } catch (Exception e) {
            if (throwException) {
                throw new EvalException(e);
            }
            log.debug("eval getFieldValue {}, of object type {}", field, object != null ? object.getClass() : null, e);
            return null;
        }
    }

    public static Method getDeclaredMethod(Class<?> type, String name, Class<?>[] argumentTypes) {
        var current = type;
        while (!(current == null || Object.class.equals(current))) try {
            return current.getDeclaredMethod(name, argumentTypes);
        } catch (NoSuchMethodException e) {
            current = type.getSuperclass();
        }
        return Optional.ofNullable(type).map(Class::getInterfaces).stream().flatMap(Arrays::stream).map(iface -> {
            return getDeclaredMethod(iface, name, argumentTypes);
        }).filter(Objects::nonNull).findFirst().orElse(null);
    }

    public static Interface.InterfaceBuilder newJmsInterfaceBuilder(JmsService jmsService) {
        var destination = jmsService.getDestination();
        return Interface.builder()
                .direction(jmsService.getDirection())
                .type(jms)
                .name(destination)
                .core(JmsService.Destination.builder()
                        .destination(destination)
                        .direction(jmsService.getDirection())
                        .build())
                .evalSource(jmsService.getEvalSource())
                .methodSource(jmsService.getMethodSource());
    }

    public static boolean isRootRelatedBean(Class<?> type, Collection<String> rootPackageNames) {
        return rootPackageNames.stream().anyMatch(rootPackageName -> isRootRelatedBean(type, rootPackageName));
    }

    public static boolean isRootRelatedBean(Class<?> type, String rootPackageName) {
        if (rootPackageName != null) {
            var relatedType = Stream.ofNullable(type)
                    .flatMap(aClass -> concat(Stream.of(entry(aClass, aClass.getPackage())), getInterfaces(aClass)
                            .map(c -> entry(c, c.getPackage()))))
                    .filter(e -> e.getValue().getName().startsWith(rootPackageName))
                    .findFirst().orElse(null);
            if (relatedType != null) {
                log.debug("type is related to root package. type: {}, related by {}", type, relatedType.getKey());
                return true;
            }
        }
        return false;
    }

    public static Stream<Class<?>> getInterfaces(Class<?> aClass) {
        return stream(aClass.getInterfaces()).flatMap(i -> concat(Stream.of(i), getInterfaces(i))).distinct();
    }

    public static Package getPackage(Component component) {
        return component != null ? component.getType().getPackage() : null;
    }

    @SafeVarargs
    public static Map<ComponentKey, Component> mergeComponents(Collection<Component>... components) {
        return of(components).flatMap(Collection::stream).collect(toMap(Component.ComponentKey::newComponentKey, c -> c, (l, r) -> {
            var lInterfaces = l.getInterfaces();
            var lDependencies = l.getDependencies();
            var rInterfaces = r.getInterfaces();
            var rDependencies = r.getDependencies();
            var dependencies = new LinkedHashSet<>(lDependencies);
            dependencies.addAll(rDependencies);
            var interfaces = mergeInterfaces(lInterfaces, rInterfaces);
            return l.toBuilder().dependencies(unmodifiableSet(new LinkedHashSet<>(dependencies)))
                    .interfaces(unmodifiableList(interfaces)).build();
        }, LinkedHashMap::new));
    }

    @SafeVarargs
    public static List<Interface> mergeInterfaces(Collection<Interface>... interfaces) {
        return of(interfaces).filter(Objects::nonNull).flatMap(Collection::stream).collect(toList());
    }

    public static boolean isMatchAny(String value, Set<String> regExps) {
        return regExps.stream().anyMatch(value::matches);
    }

    public static Stream<String> toFilteredByName(Set<String> excludeBeanNames, Stream<String> beanDefinitionNames) {
        if (!excludeBeanNames.isEmpty()) {
            beanDefinitionNames = beanDefinitionNames.filter(beanName -> {
                var exclude = isMatchAny(beanName, excludeBeanNames);
                if (exclude) {
                    log.debug("component exclude by name '{}", beanName);
                }
                return !exclude;
            });
        }
        return beanDefinitionNames;
    }

    public static void handleError(String errMsg, String componentName, EvalException e, boolean failFast) {
        if (failFast) {
            log.error("{} {}", errMsg, componentName, e);
            throw e;
        } else if (log.isDebugEnabled()) {
            log.debug("{} {}", errMsg, componentName, e);
        } else {
            log.warn("{} {}, message '{}'", errMsg, componentName, e.getLocalizedMessage());
        }
    }

    public static Stream<Component> flatDependencies(Component component) {
        var dependencies = component.getDependencies();
        return concat(Stream.of(component), dependencies != null ? dependencies.stream() : empty());
    }

    public static Component getComponentWithFilteredDependencies(Component component, Map<ComponentKey, Component> componentsPerName) {
        var dependencies = component.getDependencies();
        if (dependencies != null && !dependencies.isEmpty()) {
            var filteredDependencies = dependencies.stream()
                    .filter(componentDependency -> componentsPerName.containsKey(newComponentKey(componentDependency)))
                    .collect(toLinkedHashSet());
            return component.toBuilder().dependencies(filteredDependencies).build();
        }
        return component;
    }

    public static Component newManagedDependency(String name, Object object) {
        return Component.builder().name(name).bean(object).build();
    }

    public static String getWebsocketInterfaceId(Interface.Direction direction, String uri) {
        return direction + ":" + ws + ":" + uri;
    }

    public static Package getFieldType(Class<?> type) {
        return type.isArray() ? getFieldType(type.getComponentType()) : type.getPackage();
    }

    public static List<ScheduledMethod> getScheduledByAnnotationMethods(String name, Class<?> componentType,
                                                                        Function<TimeUnit, String> timeUnitStringifier) {
        var scheduledAnnotationMethods = getMergedRepeatableAnnotationsMap(asList(componentType.getMethods()), () -> Scheduled.class);
        return scheduledAnnotationMethods.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(annotation -> entry(entry.getKey(), annotation)))
                .map(entry -> {
                    var scheduled = entry.getValue();
                    var cron = scheduled.cron();
                    var fixedDelayLong = scheduled.fixedDelay();
                    if (fixedDelayLong <= -1) {
                        var fixedDelay = scheduled.fixedDelayString();
                        if (!fixedDelay.isEmpty()) {
                            fixedDelayLong = Long.parseLong(fixedDelay);
                        }
                    }
                    long fixedRateLong = scheduled.fixedRate();
                    if (fixedRateLong <= -1) {
                        var fixedRate = scheduled.fixedRateString();
                        if (!fixedRate.isEmpty()) {
                            fixedRateLong = Long.parseLong(fixedRate);
                        }
                    }
                    var triggerType = !cron.isEmpty() ? ScheduledMethod.TriggerType.cron
                            : fixedDelayLong > -1 ? ScheduledMethod.TriggerType.fixedDelay
                            : fixedRateLong > -1 ? ScheduledMethod.TriggerType.fixedRate
                            : null;
                    var timeUnit = scheduled.timeUnit();
                    var expression = triggerType == ScheduledMethod.TriggerType.cron ? cron
                            : triggerType == ScheduledMethod.TriggerType.fixedDelay ? getTimeExpression(timeUnitStringifier, fixedDelayLong, timeUnit)
                            : triggerType == ScheduledMethod.TriggerType.fixedRate ? getTimeExpression(timeUnitStringifier, fixedRateLong, timeUnit)
                            : null;

                    return ScheduledMethod.builder()
                            .beanName(name)
                            .method(newMethodId(entry.getKey()))
                            .triggerType(triggerType)
                            .expression(expression)
                            .build();
                })
                .filter(scheduledMethod -> {
                    //log
                    return scheduledMethod.getTriggerType() != null;
                })
                .collect(toList());
    }

    private static String getTimeExpression(Function<TimeUnit, String> timeUnitStringifier, long fixedDelayLong,
                                            TimeUnit timeUnit) {
        return SchedulingConfigurerUtils.getTimeExpression(fixedDelayLong,
                MILLISECONDS.equals(timeUnit) ? null : timeUnit,
                timeUnitStringifier);
    }
}
