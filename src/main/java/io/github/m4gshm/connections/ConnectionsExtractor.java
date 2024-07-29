package io.github.m4gshm.connections;

import feign.Target;
import io.github.m4gshm.connections.Components.HttpMethod;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.Interface;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.annotation.JmsListeners;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ConnectionsExtractorUtils.getAnnotation;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.getAnnotationMap;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.getMethods;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.hasAnnotation;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.hasMainMethod;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.isIncluded;
import static io.github.m4gshm.connections.model.Interface.Direction.in;
import static io.github.m4gshm.connections.model.Interface.Type.http;
import static java.lang.reflect.Proxy.isProxyClass;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.ofNullable;

@Slf4j
@RequiredArgsConstructor
public class ConnectionsExtractor {
    private final ConfigurableApplicationContext context;

    private static boolean isFeignHandler(Class<? extends InvocationHandler> handlerClass) {
        return "FeignInvocationHandler".equals(handlerClass.getSimpleName());
    }

    private static List<Components.JmsListener> getMethodJmsListeners(Class<?> beanType) {
        try {
            return stream(beanType.getMethods()).flatMap(m -> concat(
                    ofNullable(m.getAnnotation(JmsListener.class)),
                    ofNullable(m.getAnnotation(JmsListeners.class))
                            .map(JmsListeners::value).flatMap(Stream::of)
            ).map(l -> newJmsListener(m, l))).filter(Objects::nonNull).collect(toList());
        } catch (NoClassDefFoundError e) {
            log.debug("getJmsListenerMethods", e);
        }
        return List.of();
    }

    private static Components.JmsListener newJmsListener(Method m, JmsListener jmsListener) {
        return Components.JmsListener.builder()
                .type(Components.JmsListener.Type.JmsListenerMethod)
                .name(m.getName())
                .destination(jmsListener.destination())
                .build();
    }

    @SneakyThrows
    private static Target getFeignTarget(Class<? extends InvocationHandler> handlerClass, InvocationHandler handler) {
        var targetField = handlerClass.getDeclaredField("target");
        targetField.setAccessible(true);
        return (Target) targetField.get(handler);
    }

    private static Collection<HttpMethod> extractHttpMethods(Class<?> beanType) {
        var restController = getAnnotation(beanType, () -> Controller.class);
        if (restController == null) {
            return List.of();
        }
        var rootPath = ofNullable(getAnnotation(beanType, () -> RequestMapping.class))
                .map(RequestMapping::path)
                .flatMap(Arrays::stream).findFirst().orElse("");
        return getAnnotationMap(getMethods(beanType), () -> RequestMapping.class).keySet().stream().flatMap(requestMapping -> {
            var methods = getHttpMethods(requestMapping);
            return getPaths(requestMapping).stream().map(path -> concatPath(path, rootPath))
                    .flatMap(path -> methods.stream().map(method -> HttpMethod.builder().path(path).method(method).build()));
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

    private static String getComponentPath(Package rootPackage, Class<?> beanType) {
        if (rootPackage != null) {
            var rootPackageName = rootPackage.getName();
            var typePackageName = beanType.getPackage().getName();
            if (typePackageName.startsWith(rootPackageName)) {
                var path = typePackageName.substring(rootPackageName.length());
                return path.startsWith(".") ? path.substring(1) : path;
            }
        }
        return "";
    }

    private static boolean isSpringBootMainClass(Class<?> beanType) {
        return hasAnnotation(beanType, () -> SpringBootApplication.class) && hasMainMethod(beanType);
    }

    public Components getComponents() {
//        var httpInterfaces = new LinkedHashMap<String, HttpInterface>();
//        var httpClients = new LinkedHashMap<String, Components.HttpClient>();
//        var jmsListeners = new HashMap<String, Components.JmsListener>();
        var allBeans = asList(context.getBeanDefinitionNames());

        var componentCache = new HashMap<String, Component>();
        var rootComponent = allBeans.stream().filter(beanName1 -> isSpringBootMainClass(context.getType(beanName1)))
                .map(beanName -> newComponent(beanName, null, componentCache))
                .filter(Objects::nonNull).findFirst().orElse(null);
        var rootPackage = rootComponent != null ? rootComponent.getType().getPackage() : null;

        var components = filterByRootPackage(rootComponent, allBeans.stream()
                .map(beanName -> newComponent(beanName, rootPackage, componentCache))
                .filter(Objects::nonNull).filter(bean -> isIncluded(bean.getType())))
                .collect(toList());

//        for (var component : components) {
//            var componentName = component.getName();
//            try {
//                var beanType = component.getType();
//
//                components.put(component, dependenciesForBean.stream()
//                        .filter(dependencyBeanName -> isIncluded(context.getType(dependencyBeanName)))
//                        .collect(toList())
//                );
//
//                if (!dependenciesForBean.isEmpty()) {
//                    boolean useRestTemplate;
//                    try {
//                        useRestTemplate = dependenciesForBean.stream().map(context::getType)
//                                .filter(Objects::nonNull).anyMatch(RestTemplate.class::isAssignableFrom);
//                    } catch (NoSuchBeanDefinitionException e) {
//                        log.trace("useRestTemplate, component {}", componentName, e);
//                        useRestTemplate = false;
//                    }
//                    if (useRestTemplate) {
//                        httpClients.put(componentName, Components.HttpClient.builder()
//                                .name(componentName)
//                                .type(RestTemplateBased)
//                                .build());
//                        log.debug("rest template dependent component {}, type {}", componentName, beanType);
//                    }
//                }
//
//                var feignClient = extractFeignClient(componentName);
//                if (feignClient != null) {
//                    httpClients.put(componentName, Components.HttpClient.builder()
//                            .name(feignClient.getName())
//                            .url(feignClient.getUrl())
//                            .type(Feign)
//                            .build());
//                }
//
//                var beanJmsListeners = getMethodJmsListeners(beanType);
//                if (!beanJmsListeners.isEmpty()) {
//                    log.debug("jms method listeners, class {}, amount {}", beanType, jmsListeners.size());
//                    for (var beanJmsListener : beanJmsListeners) {
//                        jmsListeners.put(componentName + "." + beanJmsListener.getName(), beanJmsListener);
//                    }
//                }
//            } catch (NoClassDefFoundError e) {
//                log.debug("bad component {}", componentName, e);
//            }
//        }
        return Components.builder()
                .components(components)
                .build();
    }

    private Component newComponent(String componentName, Package rootPackage, Map<String, Component> cache) {
        var cached = cache.get(componentName);
        if (cached != null) {
            //log
            return cached;
        }
        Class<?> componentType;
        try {
            componentType = context.getType(componentName);
        } catch (NoSuchBeanDefinitionException e) {
            //log
            componentType = null;
        }
        if (componentType == null) {
            return null;
        }
        var dependencies = stream(context.getBeanFactory().getDependenciesForBean(componentName))
                .map(dep -> newComponent(dep, rootPackage, cache))
                .collect(toList());

        var httpMethods = extractHttpMethods(componentType);

        var httpInterfaces = httpMethods.stream().map(httpMethod -> {
            var method = httpMethod.getMethod();
            var path = httpMethod.getPath();
            return method == null || method.isEmpty() ? path : method + ":" + path;
        }).map(interfaceName -> Interface.builder().name(interfaceName).direction(in).type(http).build()).collect(toList());

        var component = Component.builder()
                .name(componentName)
                .path(getComponentPath(rootPackage, componentType))
                .type(componentType)
                .interfaces(httpInterfaces)
                .dependencies(dependencies)
                .build();

        cache.put(component.getName(), component);
        return component;
    }

    private Stream<Component> filterByRootPackage(Component rootComponent, Stream<Component> componentStream) {
        var rootPackage = rootComponent != null ? rootComponent.getType().getPackage() : null;
        if (rootPackage != null) {
            componentStream = componentStream.filter(bean -> bean.getType().getPackage().getName().startsWith(rootPackage.getName()));
        }
        return componentStream;
    }

    private FeignClient extractFeignClient(String name) {
        try {
            final FeignClient feignClient;
            var bean = context.getBean(name);
            if (isProxyClass(bean.getClass()) && !this.getClass().isAssignableFrom(bean.getClass())) {
                var handler = Proxy.getInvocationHandler(bean);
                var handlerClass = handler.getClass();
                if (isFeignHandler(handlerClass)) {
                    var target = getFeignTarget(handlerClass, handler);
                    var url = target.url();
                    log.debug("feign {}", url);
                    feignClient = FeignClient.builder()
                            .type(target.type())
                            .name(target.name())
                            .url(target.url())
                            .build();
                } else {
                    feignClient = null;
                }
            } else {
                feignClient = null;
            }
            return feignClient;
        } catch (NoClassDefFoundError e) {
            log.debug("extractFeignClient bean {}", name, e);
            return null;
        }
    }

    @Data
    @Builder
    public static class FeignClient {
        private final Class<?> type;
        private final String name;
        private final String url;
    }
}
