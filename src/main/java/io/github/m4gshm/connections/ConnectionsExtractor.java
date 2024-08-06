package io.github.m4gshm.connections;

import io.github.m4gshm.connections.bytecode.EvalException;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.Interface;
import io.github.m4gshm.connections.model.Interface.Direction;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jms.core.JmsOperations;
import org.springframework.web.client.RestOperations;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurationSupport;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ConnectionsExtractorUtils.extractControllerHttpMethods;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.extractFeignClient;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.extractMethodJmsListeners;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.findDependencyByType;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.getComponentPath;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.getHttpInterfaceName;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.isIncluded;
import static io.github.m4gshm.connections.ConnectionsExtractorUtils.isSpringBootMainClass;
import static io.github.m4gshm.connections.ReflectionUtils.getFieldValue;
import static io.github.m4gshm.connections.Utils.toLinkedHashSet;
import static io.github.m4gshm.connections.client.JmsOperationsUtils.extractJmsClients;
import static io.github.m4gshm.connections.client.RestOperationsUtils.extractRestOperationsUris;
import static io.github.m4gshm.connections.client.WebsocketClientUtils.extractWebsocketClientUris;
import static io.github.m4gshm.connections.model.Interface.Direction.in;
import static io.github.m4gshm.connections.model.Interface.Direction.out;
import static io.github.m4gshm.connections.model.Interface.Type.http;
import static io.github.m4gshm.connections.model.Interface.Type.jms;
import static io.github.m4gshm.connections.model.Interface.Type.ws;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.of;
import static java.util.stream.Stream.ofNullable;

@Slf4j
@RequiredArgsConstructor
public class ConnectionsExtractor {
    private final ConfigurableApplicationContext context;

    private static UriComponents getUriComponents(String url) {
        try {
            return UriComponentsBuilder.fromUriString(url).build();
        } catch (Exception e) {
            log.error(url, e);
            return null;
        }
    }

    private static HttpNameAndGroup extractNameAndGroup(HttpMethod httpMethod) {
        var url = httpMethod.getUrl();
        var uriComponents = getUriComponents(url);

        var methodUrl = getMethodUrl(uriComponents, url);
        var httpInterfaceName = getHttpInterfaceName(httpMethod.getMethod(), methodUrl);
        var group = uriComponents != null ? uriComponents.getScheme() + "://" + uriComponents.getHost() : null;

        return new HttpNameAndGroup(httpInterfaceName, group);
    }

    private static String getMethodUrl(UriComponents uriComponents, String url) {
        var methodUrl = uriComponents != null ? uriComponents.getPath() : url;
        if (methodUrl == null || methodUrl.isBlank()) {
            methodUrl = "/";
        }
        return methodUrl;
    }

    private static Interface newInterface(JmsClient jmsClient, String group) {
        var directions = jmsClient.types.stream().map(type -> type.direction).collect(toSet());
        return Interface.builder().directions(directions).type(jms).group(group).name(jmsClient.destination).build();
    }

    public Components getComponents() {
        var allBeans = asList(context.getBeanDefinitionNames());

        var componentCache = new HashMap<String, Set<Component>>();
        var rootComponent = allBeans.stream()
                .filter(beanName -> isSpringBootMainClass(context.getType(beanName)))
                .flatMap(beanName -> getComponents(beanName, null, componentCache))
                .filter(Objects::nonNull).findFirst().orElse(null);
        var rootPackage = rootComponent != null ? rootComponent.getType().getPackage() : null;

        var components = filterByRootPackage(getRootPackage(rootComponent), allBeans.stream())
                .flatMap(beanName -> getFilteredComponents(beanName, rootPackage, componentCache))
                .collect(toList());

        return Components.builder().components(components).build();
    }

    private Stream<Component> getFilteredComponents(
            String beanName, Package rootPackage, Map<String, Set<Component>> componentCache
    ) {
        return getComponents(beanName, rootPackage, componentCache)
                .filter(Objects::nonNull)
                .filter(component -> isIncluded(component.getType()));
    }

    private Stream<Component> getComponents(String beanName, Package rootPackage, Map<String, Set<Component>> cache) {
        var cached = cache.get(beanName);
        if (cached != null) {
            //log
            return cached.stream();
        }
        Class<?> componentType;
        try {
            componentType = context.getType(beanName);
        } catch (NoSuchBeanDefinitionException e) {
            //log
            componentType = null;
        }

        var feignClient = extractFeignClient(beanName, context);
        if (feignClient != null) {
            //log
            componentType = feignClient.getType();
        }

        if (componentType == null) {
            return Stream.empty();
        }

        var websocketHandlers = extractInWebsocketHandlers(beanName, componentType, rootPackage, cache);
        if (!websocketHandlers.isEmpty()) {
            return websocketHandlers.stream();
        } else {
            var result = new ArrayList<Component>();

            var inJmsInterface = extractMethodJmsListeners(componentType).stream().map(jmsClient -> newInterface(jmsClient, beanName));

            var dependencies = feignClient != null ? Set.<Component>of() : getDependencies(beanName, rootPackage, cache);

            var outJmsInterfaces = getOutJmsInterfaces(beanName, componentType, dependencies);
            var outWsInterfaces = getOutWsInterfaces(beanName, componentType, dependencies);
            var outRestOperationsHttpInterface = getOutRestTemplateInterfaces(beanName, componentType, dependencies);

            var outFeignHttpInterface = ofNullable(feignClient).flatMap(client -> ofNullable(client.getHttpMethods())
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .map(httpMethod -> getHttpInterfaceName(httpMethod.getMethod(), httpMethod.getUrl()))
                    .map(interfaceName -> Interface.builder().directions(Set.of(out)).type(http).group(client.getUrl()).name(interfaceName).build())
            );

            var inHttpInterfaces = extractControllerHttpMethods(componentType).stream()
                    .map(httpMethod -> getHttpInterfaceName(httpMethod.getMethod(), httpMethod.getUrl()))
                    .map(interfaceName -> Interface.builder().directions(Set.of(in)).type(http).name(interfaceName).build());

            var name = feignClient != null && !feignClient.name.equals(feignClient.url) ? feignClient.name : beanName;

            var component = Component.builder()
                    .name(name)
                    .path(getComponentPath(rootPackage, componentType))
                    .type(componentType)
                    .interfaces(of(
                            inHttpInterfaces, inJmsInterface, outFeignHttpInterface,
                            outRestOperationsHttpInterface.stream(), outWsInterfaces.stream(), outJmsInterfaces.stream()
                    ).flatMap(s -> s).collect(toLinkedHashSet()))
                    .dependencies(dependencies)
                    .build();

            cache.put(beanName, Set.of(component));
            result.add(component);
            return result.stream();
        }
    }

    private Set<Interface> getOutJmsInterfaces(String componentName, Class<?> componentType, Collection<Component> dependencies) {
        var jmsTemplate = findDependencyByType(dependencies, JmsOperations.class);
        if (jmsTemplate != null) try {
            var jmsClients = extractJmsClients(componentName, componentType, context);

            return jmsClients.stream()
                    .map(jmsClient -> newInterface(jmsClient, componentName))
                    .collect(toLinkedHashSet());

        } catch (EvalException | NoClassDefFoundError e) {
            if (log.isDebugEnabled()) {
                log.debug("jms client getting error, component {}", componentName, e);
            } else {
                log.info("jms client getting error, component {}, message '{}'", componentName, e.getLocalizedMessage());
            }
        }
        return Set.of();
    }

    private Set<Interface> getOutWsInterfaces(String componentName, Class<?> componentType, Collection<Component> dependencies) {
        var wsClient = findDependencyByType(dependencies, WebSocketClient.class);
        if (wsClient != null) try {
            var wsClientUris = extractWebsocketClientUris(componentName, componentType, context);

            return wsClientUris.stream()
                    .map(uri -> Interface.builder().directions(Set.of(out)).type(ws).name(uri).build())
                    .collect(toLinkedHashSet());

        } catch (EvalException | NoClassDefFoundError e) {
            if (log.isDebugEnabled()) {
                log.debug("ws client getting error, component {}", componentName, e);
            } else {
                log.info("ws client getting error, component {}, message '{}'", componentName, e.getLocalizedMessage());
            }
        }
        return Set.of();
    }

    private Set<Interface> getOutRestTemplateInterfaces(String componentName, Class<?> componentType, Collection<Component> dependencies) {
        var restTemplate = findDependencyByType(dependencies, RestOperations.class);
        if (restTemplate != null) try {
            var httpMethods = extractRestOperationsUris(componentName, componentType, context);
            return httpMethods.stream().map(httpMethod -> {
                var result = extractNameAndGroup(httpMethod);
                return Interface.builder().directions(Set.of(out)).type(http).name(result.name).group(result.group).build();
            }).collect(toLinkedHashSet());
        } catch (EvalException | NoClassDefFoundError e) {
            if (log.isDebugEnabled()) {
                log.debug("rest operations client getting error, component {}", componentName, e);
            } else {
                log.info("rest operations client getting error, component {}, message '{}'", componentName, e.getLocalizedMessage());
            }
        }
        return Set.of();
    }

    private Set<Component> getDependencies(String componentName, Package rootPackage, Map<String, Set<Component>> cache) {
        return stream(context.getBeanFactory().getDependenciesForBean(componentName))
                .flatMap(dep -> getFilteredComponents(dep, rootPackage, cache))
                .collect(toLinkedHashSet());
    }

    private Collection<Component> extractInWebsocketHandlers(
            String componentName, Class<?> componentType, Package rootPackage, Map<String, Set<Component>> cache) {
        var cachedComponents = cache.get(componentName);
        if (cachedComponents != null) {
            return cachedComponents;
        }
        try {
            if (WebSocketConfigurationSupport.class.isAssignableFrom(componentType)) {
                var bean = (WebSocketConfigurationSupport) context.getBean(componentName);
                var handlerRegistry = getFieldValue(bean, "handlerRegistry");
                if (handlerRegistry instanceof ServletWebSocketHandlerRegistry) {
                    var handlerMapping = ((ServletWebSocketHandlerRegistry) handlerRegistry).getHandlerMapping();
                    if (handlerMapping instanceof SimpleUrlHandlerMapping) {
                        var simpleUrlHandlerMapping = (SimpleUrlHandlerMapping) handlerMapping;
                        var components = simpleUrlHandlerMapping.getUrlMap().entrySet().stream().flatMap(entry -> {
                            var wsHandlerPath = entry.getValue();
                            var wsUrl = entry.getKey();
                            return getComponentStream(wsUrl, wsHandlerPath, rootPackage, cache);
                        }).filter(Objects::nonNull).collect(toLinkedHashSet());
                        cache.put(componentName, components);
                        return components;
                    }
                }
            }
        } catch (NoClassDefFoundError e) {
            log.debug("undefined class", e);
        }
        return Set.of();
    }

    private Stream<Component> getComponentStream(String wsUrl, Object wsHandler,
                                                 Package rootPackage, Map<String, Set<Component>> cache) {
        if (wsHandler instanceof WebSocketHttpRequestHandler) {
            var webSocketHandler = ((WebSocketHttpRequestHandler) wsHandler).getWebSocketHandler();
            var webSocketHandlerName = findBeanName(webSocketHandler);

            if (webSocketHandlerName == null && webSocketHandler instanceof WebSocketHandlerDecorator) {
                //log
                webSocketHandler = ((WebSocketHandlerDecorator) webSocketHandler).getLastHandler();
                webSocketHandlerName = findBeanName(webSocketHandler);
            }
            var webSocketHandlerClass = webSocketHandler.getClass();
            var cached = cache.get(webSocketHandlerName);
            var anInterface = Interface.builder()
                    .directions(Set.of(in)).type(ws).name(wsUrl)
                    .build();
            if (cached != null) {
                cached = cached.stream().map(component -> {
                    var interfaces = component.getInterfaces();
                    if (!interfaces.contains(anInterface)) {
                        //log
                        var newInterfaces = new LinkedHashSet<Interface>(interfaces);
                        newInterfaces.add(anInterface);
                        return component.toBuilder().interfaces(unmodifiableSet(newInterfaces)).build();
                    } else
                        return component;
                }).collect(toLinkedHashSet());

                cache.put(webSocketHandlerName, cached);
                return cached.stream();
            } else {
                return of(Component.builder()
                        .type(webSocketHandlerClass)
                        .name(webSocketHandlerName != null
                                ? webSocketHandlerName :
                                webSocketHandlerClass.getSimpleName()
                        )
                        .path(getComponentPath(rootPackage, webSocketHandlerClass))
                        .interfaces(Set.of(anInterface))
                        .dependencies(Set.of()).build());
            }
        } else {
            return Stream.empty();
        }
    }

    private String findBeanName(WebSocketHandler webSocketHandler) {
        return of(context.getBeanNamesForType(WebSocketHandler.class)).filter(name -> {
            try {
                var bean = context.getBean(name);
                return webSocketHandler == bean;
            } catch (NoSuchBeanDefinitionException e) {
                //log
                return false;
            }
        }).findFirst().orElse(null);
    }

    private Stream<String> filterByRootPackage(Package rootPackage, Stream<String> benaNamesStream) {
        if (rootPackage != null) {
            var rootPackageName = rootPackage.getName();
            benaNamesStream = benaNamesStream.filter(beanName -> Optional.ofNullable(context.getType(beanName))
                    .map(Class::getPackage).map(Package::getName).orElse("")
                    .startsWith(rootPackageName));
        }
        return benaNamesStream;
    }

    private static Package getRootPackage(Component rootComponent) {
        return rootComponent != null ? rootComponent.getType().getPackage() : null;
    }

    private static class HttpNameAndGroup {
        public final String name;
        public final String group;

        public HttpNameAndGroup(String httpInterfaceName, String group) {
            this.name = httpInterfaceName;
            this.group = group;
        }
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
    public static class JmsClient {
        private String name;
        private String destination;
        private Set<Type> types;

        @RequiredArgsConstructor
        public enum Type {
            listener(in), sender(out);

            public final Direction direction;
        }
    }
}
