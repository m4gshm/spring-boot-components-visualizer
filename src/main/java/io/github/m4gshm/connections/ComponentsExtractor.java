package io.github.m4gshm.connections;

import io.github.m4gshm.connections.bytecode.EvalException;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractorUtils.extractControllerHttpMethods;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.extractFeignClient;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.extractMethodJmsListeners;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.findDependencyByType;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.getComponentPath;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.isIncluded;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.isSpringBootMainClass;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.loadedClass;
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
import static java.util.Map.entry;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.of;
import static java.util.stream.Stream.ofNullable;

@Slf4j
@RequiredArgsConstructor
public class ComponentsExtractor {
    private final ConfigurableApplicationContext context;

    private static Interface newInterface(JmsClient jmsClient, String group) {
        return Interface.builder().direction(jmsClient.direction).type(jms).name(jmsClient.destination).build();
    }

    private static boolean isRootRelatedBean(Class<?> type, String rootPackageName) {
        if (rootPackageName != null) {
            var relatedType = Stream.ofNullable(type)
                    .flatMap(aClass -> Stream.concat(Stream.of(
                                    entry(aClass, aClass.getPackage())),
                            getInterfaces(aClass).map(c -> entry(c, c.getPackage())))
                    )
                    .filter(e -> e.getValue().getName().startsWith(rootPackageName))
                    .findFirst().orElse(null);
            if (relatedType != null) {
                log.debug("type is related to root package. type: {}, related by {}", type, relatedType.getKey());
                return true;
            }
        }
        return false;
    }

    private static Stream<Class<?>> getInterfaces(Class<?> aClass) {
        return stream(aClass.getInterfaces()).flatMap(i -> Stream.concat(Stream.of(i), getInterfaces(i))).distinct();
    }

    private static Package getPackage(Component component) {
        return component != null ? component.getType().getPackage() : null;
    }

    @SafeVarargs
    private static Collection<Component> mergeComponents(Collection<Component>... components) {
        return of(components).flatMap(Collection::stream).collect(Collectors.toMap(Component::getName, c -> c, (l, r) -> {
            var lInterfaces = l.getInterfaces();
            var lDependencies = l.getDependencies();
            var rInterfaces = r.getInterfaces();
            var rDependencies = r.getDependencies();
            var dependencies = mergeComponents(lDependencies, rDependencies);
            var interfaces = mergeInterfaces(lInterfaces, rInterfaces);
            return l.toBuilder()
                    .dependencies(unmodifiableSet(new LinkedHashSet<>(dependencies)))
                    .interfaces(unmodifiableSet(interfaces))
                    .build();
        }, LinkedHashMap::new)).values();
    }

    @SafeVarargs
    private static LinkedHashSet<Interface> mergeInterfaces(Collection<Interface>... interfaces) {
        return of(interfaces).flatMap(Collection::stream).collect(toLinkedHashSet());
    }

    public Components getComponents() {
        var allBeans = asList(context.getBeanDefinitionNames());

        var componentCache = new HashMap<String, Set<Component>>();
        var rootComponent = allBeans.stream()
                .filter(beanName -> isSpringBootMainClass(context.getType(beanName)))
                .flatMap(beanName -> getComponents(beanName, null, componentCache))
                .filter(Objects::nonNull).findFirst().orElse(null);

        var rootPackage = getPackage(rootComponent);
        var rootPackageName = rootPackage != null ? rootPackage.getName() : null;

        var rootGroupedBeans = allBeans.stream()
                .collect(groupingBy(beanName -> isRootRelatedBean(context.getType(beanName), rootPackageName)));

        var rootComponents = rootGroupedBeans.getOrDefault(true, List.of()).stream()
                .flatMap(beanName -> getComponents(beanName, rootPackage, componentCache)
                        .filter(Objects::nonNull).filter(component -> isIncluded(component.getType())))
                .collect(toList());

        var additionalComponents = rootGroupedBeans.getOrDefault(false, List.of()).stream()
                .flatMap(beanName -> extractInWebsocketHandlers(beanName, getComponentType(beanName),
                        rootPackage, componentCache).stream()).collect(toList());

        var components = mergeComponents(rootComponents, additionalComponents);

        return Components.builder().components(components).build();
    }

    private Stream<Component> getComponents(String beanName, Package rootPackage, Map<String, Set<Component>> cache) {
        var cached = cache.get(beanName);
        if (cached != null) {
            //log
            return cached.stream();
        }
        var componentType = getComponentType(beanName);

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

            //log
            var inJmsInterface = extractMethodJmsListeners(componentType).stream().map(jmsClient -> newInterface(jmsClient, beanName)).collect(toList());

            var dependencies = feignClient != null ? Set.<Component>of() : getDependencies(beanName, rootPackage, cache);

            //log
            var outJmsInterfaces = getOutJmsInterfaces(beanName, componentType, dependencies);
            //log
            var outWsInterfaces = getOutWsInterfaces(beanName, componentType, dependencies);
            //log
            var outRestOperationsHttpInterface = getOutRestTemplateInterfaces(beanName, componentType, dependencies);

            //log
            var outFeignHttpInterface = ofNullable(feignClient).flatMap(client -> ofNullable(client.getHttpMethods())
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .map(httpMethod -> Interface.builder().direction(out).type(http).core(httpMethod).build())
            ).collect(toList());

            //log
            var inHttpInterfaces = extractControllerHttpMethods(componentType).stream()
                    .map(httpMethod -> Interface.builder().direction(in).type(http).core(httpMethod).build()).collect(toList());

            var name = feignClient != null && !feignClient.name.equals(feignClient.url) ? feignClient.name : beanName;

            //log
            var component = Component.builder()
                    .name(name)
                    .path(getComponentPath(rootPackage, componentType))
                    .type(componentType)
                    .interfaces(of(
                            inHttpInterfaces.stream(), inJmsInterface.stream(), outFeignHttpInterface.stream(),
                            outRestOperationsHttpInterface.stream(), outWsInterfaces.stream(), outJmsInterfaces.stream()
                    ).flatMap(s -> s).collect(toLinkedHashSet()))
                    .dependencies(dependencies)
                    .build();

            cache.put(beanName, Set.of(component));
            result.add(component);
            return result.stream();
        }
    }

    private Class<?> getComponentType(String beanName) {
        Class<?> componentType;
        try {
            componentType = context.getType(beanName);
        } catch (NoSuchBeanDefinitionException e) {
            //log
            componentType = null;
        }
        return componentType;
    }

    private Set<Interface> getOutJmsInterfaces(String componentName, Class<?> componentType, Collection<Component> dependencies) {
        var jmsTemplate = findDependencyByType(dependencies, () -> JmsOperations.class);
        if (jmsTemplate != null) try {
            var jmsClients = extractJmsClients(componentName, componentType, context);

            return jmsClients.stream()
                    .map(jmsClient -> newInterface(jmsClient, componentName))
                    .collect(toLinkedHashSet());

        } catch (EvalException e) {
            if (log.isDebugEnabled()) {
                log.debug("jms client getting error, component {}", componentName, e);
            } else {
                log.info("jms client getting error, component {}, message '{}'", componentName, e.getLocalizedMessage());
            }
        }
        return Set.of();
    }

    private Set<Interface> getOutWsInterfaces(String componentName, Class<?> componentType, Collection<Component> dependencies) {
        var wsClient = findDependencyByType(dependencies, () -> WebSocketClient.class);
        if (wsClient != null) try {
            var wsClientUris = extractWebsocketClientUris(componentName, componentType, context);

            return wsClientUris.stream()
                    .map(uri -> Interface.builder().direction(out).type(ws).name(uri).build())
                    .collect(toLinkedHashSet());

        } catch (EvalException e) {
            if (log.isDebugEnabled()) {
                log.debug("ws client getting error, component {}", componentName, e);
            } else {
                log.info("ws client getting error, component {}, message '{}'", componentName, e.getLocalizedMessage());
            }
        }
        return Set.of();
    }

    private Set<Interface> getOutRestTemplateInterfaces(String componentName, Class<?> componentType, Collection<Component> dependencies) {
        var restTemplate = findDependencyByType(dependencies, () -> RestOperations.class);
        if (restTemplate != null) try {
            var httpMethods = extractRestOperationsUris(componentName, componentType, context);
            return httpMethods.stream().map(httpMethod -> Interface.builder().direction(out).type(http)
                            .core(httpMethod).build())
                    .collect(toLinkedHashSet());
        } catch (EvalException e) {
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
                .flatMap(dep -> getComponents(dep, rootPackage, cache)
                        .filter(Objects::nonNull).filter(component -> isIncluded(component.getType())))
                .collect(toLinkedHashSet());
    }

    private Collection<Component> extractInWebsocketHandlers(
            String componentName, Class<?> componentType, Package rootPackage, Map<String, Set<Component>> cache) {
        var webSocketConfigClass = loadedClass(() -> WebSocketConfigurationSupport.class);
        if (webSocketConfigClass != null && webSocketConfigClass.isAssignableFrom(componentType)) {
            var cachedComponents = cache.get(componentName);
            if (cachedComponents != null) {
                return cachedComponents;
            }
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
                    .direction(in).type(ws).name(wsUrl)
                    .build();
            if (cached != null) {
                cached = cached.stream().map(component -> {
                    var interfaces = component.getInterfaces();
                    if (!interfaces.contains(anInterface)) {
                        //log
                        var newInterfaces = new LinkedHashSet<>(interfaces);
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
            benaNamesStream = benaNamesStream.filter(beanName -> isRootRelatedBean(context.getType(beanName), rootPackageName));
        }
        return benaNamesStream;
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
    @Builder
    public static class JmsClient {
        private String name;
        private String destination;
        private Direction direction;

    }
}
