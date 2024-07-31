package io.github.m4gshm.connections;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.MethodMetadata;
import feign.Target;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.Interface;
import io.github.m4gshm.connections.model.Interface.InterfaceBuilder;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jms.annotation.JmsListener;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ConnectionsExtractorUtils.extractControllerHttpMethods;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.getMergedRepeatableAnnotationsMap;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.isIncluded;
import static io.github.m4gshm.connections.model.Interface.Direction.in;
import static io.github.m4gshm.connections.model.Interface.Direction.out;
import static io.github.m4gshm.connections.model.Interface.Type.http;
import static io.github.m4gshm.connections.model.Interface.Type.jms;
import static java.lang.reflect.Proxy.isProxyClass;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.ofNullable;

@Slf4j
@RequiredArgsConstructor
public class ConnectionsExtractor {
    private final ConfigurableApplicationContext context;

    private static List<JmsClientListener> extractMethodJmsListeners(Class<?> beanType) {
        var annotationMap = getMergedRepeatableAnnotationsMap(asList(beanType.getMethods()), () -> JmsListener.class);
        return annotationMap.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(annotation -> Map.entry(entry.getKey(), annotation)))
                .map(entry -> JmsClientListener.builder()
                        .type(JmsClientListener.Type.JmsListenerMethod)
                        .name(entry.getKey().getName())
                        .destination(entry.getValue().destination())
                        .build()).collect(toList());
    }

    private static FeignClient extractFeignClient(String name, ConfigurableApplicationContext context) {
        try {
            var bean = context.getBean(name);
            if (!isProxyClass(bean.getClass())) {
                return null;
            }
            var handler = Proxy.getInvocationHandler(bean);
            var handlerClass = handler.getClass();
            if (!"FeignInvocationHandler".equals(handlerClass.getSimpleName())) {
                return null;
            }

            var httpMethods = ((Collection<?>) ((Map) ConnectionsExtractorUtils.getFieldValue("dispatch", handler)).values()
            ).stream().map(value -> (MethodHandler) value).map(value -> {
                var buildTemplateFromArgs = ConnectionsExtractorUtils.getFieldValue("buildTemplateFromArgs", value);
                var metadata = (MethodMetadata) ConnectionsExtractorUtils.getFieldValue("metadata", buildTemplateFromArgs);
                var template = metadata.template();
                var method = template.method();
                var url = template.url();
                return HttpMethod.builder().method(method).url(url).build();
            }).collect(toList());

            var target = (Target) ConnectionsExtractorUtils.getFieldValue("target", handler);
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

        } catch (NoClassDefFoundError | NoSuchBeanDefinitionException e) {
            log.debug("extractFeignClient bean {}", name, e);
            return null;
        }
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

    public Components getComponents() {
        var allBeans = asList(context.getBeanDefinitionNames());

        var componentCache = new HashMap<String, Component>();
        var rootComponent = allBeans.stream().filter(beanName1 -> ConnectionsExtractorUtils.isSpringBootMainClass(context.getType(beanName1)))
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

        var jmsClientListeners = extractMethodJmsListeners(componentType);
        var inJmsInterface = jmsClientListeners.stream().map(jmsClientListener -> {
            return Interface.builder().direction(in).type(jms).group(componentName).name(jmsClientListener.destination).build();
        });

        var dependencies = feignClient != null ? List.<Component>of() : stream(context.getBeanFactory()
                .getDependenciesForBean(componentName))
                .map(dep -> newComponent(dep, rootPackage, cache))
                .collect(toList());

        var outHttpInterface = ofNullable(feignClient).flatMap(client -> ofNullable(client.getHttpMethods())
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(httpMethod -> ConnectionsExtractorUtils.getHttpInterfaceName(httpMethod.getMethod(), httpMethod.getUrl()))
                .map(interfaceName -> Interface.builder().direction(out).type(http).group(client.getUrl()).name(interfaceName).build())
        );

        var inHttpInterfaces = extractControllerHttpMethods(componentType).stream()
                .map(httpMethod -> ConnectionsExtractorUtils.getHttpInterfaceName(httpMethod.getMethod(), httpMethod.getUrl()))
                .map(interfaceName -> Interface.builder().direction(in).type(http).name(interfaceName).build());

        var name = feignClient != null && !feignClient.name.equals(feignClient.url) ? feignClient.name : componentName;

        var component = Component.builder()
                .name(name)
                .path(getComponentPath(rootPackage, componentType))
                .type(componentType)
                .interfaces(Stream.of(inHttpInterfaces, inJmsInterface, outHttpInterface).flatMap(s -> s).collect(toList()))
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


    @Data
    @Builder(toBuilder = true)
    public static class HttpMethod {
        private String url;
        private String method;
    }

    @Data
    @Builder
    public static class JmsClientListener {
        private String name;
        private String destination;
        private Type type;

        public enum Type {
            JmsListenerMethod
        }
    }
}
