package io.github.m4gshm.connections;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.MethodMetadata;
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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ConnectionsExtractorUtils.extractControllerHttpMethods;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.hasAnnotation;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.hasMainMethod;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.isIncluded;
import static io.github.m4gshm.connections.model.Interface.Direction.in;
import static io.github.m4gshm.connections.model.Interface.Direction.out;
import static io.github.m4gshm.connections.model.Interface.Type.http;
import static java.lang.reflect.Proxy.isProxyClass;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
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

    private static FeignClient extractFeignClient(String name, ConfigurableApplicationContext context) {
        try {
            var bean = context.getBean(name);
            if (!isProxyClass(bean.getClass())) {
                return null;
            }
            var handler = Proxy.getInvocationHandler(bean);
            var handlerClass = handler.getClass();
            if (!isFeignHandler(handlerClass)) {
                return null;
            }

            var httpMethods = ((Collection<?>) ((Map) getFieldValue("dispatch", handler)).values()
            ).stream().map(value -> (MethodHandler) value).map(value -> {
                var buildTemplateFromArgs = getFieldValue("buildTemplateFromArgs", value);
                var metadata = (MethodMetadata) getFieldValue("metadata", buildTemplateFromArgs);
                var template = metadata.template();
                var method = template.method();
                var url = template.url();
                return HttpMethod.builder().method(method).url(url).build();
            }).collect(toList());

            var target = getFeignTarget(handlerClass, handler);
            var type = target.type();

            return FeignClient.builder()
                    .type(type)
                    .name(target.name())
                    .url(target.url())
                    .httpMethods(httpMethods)
                    .build();

        } catch (NoClassDefFoundError | NoSuchBeanDefinitionException e) {
            log.debug("extractFeignClient bean {}", name, e);
            return null;
        }
    }

    private static Object getFieldValue(String name, Object object) {
        Field dispatch;
        try {
            dispatch = object.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
        dispatch.setAccessible(true);
        try {
            return dispatch.get(object);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static String httpInterfaceName(String method, String url) {
        return method == null || method.isEmpty() ? url : method + ":" + url;
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

        return Components.builder().components(components).build();
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

        var feignClient = extractFeignClient(componentName, context);
        if (feignClient != null) {
            //log
            componentType = feignClient.getType();
        }

        if (componentType == null) {
            return null;
        }

        var dependencies = feignClient != null ? List.<Component>of() : stream(context.getBeanFactory()
                .getDependenciesForBean(componentName))
                .map(dep -> newComponent(dep, rootPackage, cache))
                .collect(toList());

        var outHttpInterface = ofNullable(feignClient).flatMap(client -> ofNullable(client.getHttpMethods())
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(httpMethod -> httpInterfaceName(httpMethod.getMethod(), httpMethod.getUrl()))
                .map(interfaceName -> Interface.builder().group(client.getUrl()).name(interfaceName).direction(out).type(http).build())
        );

        var inHttpInterfaces = extractControllerHttpMethods(componentType).stream()
                .map(httpMethod -> httpInterfaceName(httpMethod.getMethod(), httpMethod.getUrl()))
                .map(interfaceName -> Interface.builder().name(interfaceName).direction(in).type(http).build());

        var name = feignClient != null && !feignClient.name.equals(feignClient.url) ? feignClient.name : componentName;

        var component = Component.builder()
                .name(name)
                .path(getComponentPath(rootPackage, componentType))
                .type(componentType)
                .interfaces(Stream.of(inHttpInterfaces, outHttpInterface).flatMap(s -> s).collect(toList()))
                .dependencies(dependencies)
                .build();

        cache.put(componentName, component);
        return component;
    }

    private Stream<Component> filterByRootPackage(Component rootComponent, Stream<Component> componentStream) {
        var rootPackage = rootComponent != null ? rootComponent.getType().getPackage() : null;
        if (rootPackage != null) {
            componentStream = componentStream.filter(bean -> bean.getType().getPackage().getName().startsWith(rootPackage.getName()));
        }
        return componentStream;
    }

    @Data
    @Builder
    public static class FeignClient {
        private final Class<?> type;
        private final String name;
        private final String url;
        private final List<HttpMethod> httpMethods;
    }
}
