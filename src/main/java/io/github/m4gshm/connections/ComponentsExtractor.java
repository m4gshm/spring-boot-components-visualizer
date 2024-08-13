package io.github.m4gshm.connections;

import io.github.m4gshm.connections.ComponentsExtractor.Options.BeanFilter;
import io.github.m4gshm.connections.bytecode.EvalException;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
import io.github.m4gshm.connections.model.Interface;
import io.github.m4gshm.connections.model.Interface.Direction;
import io.github.m4gshm.connections.model.Storage;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.metamodel.spi.MetamodelImplementor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.support.JpaMetamodelEntityInformation;
import org.springframework.data.mongodb.repository.query.MongoEntityInformation;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import org.springframework.jms.core.JmsOperations;
import org.springframework.web.client.RestOperations;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurationSupport;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import javax.persistence.metamodel.Metamodel;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractorUtils.*;
import static io.github.m4gshm.connections.ReflectionUtils.getFieldValue;
import static io.github.m4gshm.connections.Utils.*;
import static io.github.m4gshm.connections.bytecode.EvalUtils.unproxy;
import static io.github.m4gshm.connections.client.JmsOperationsUtils.extractJmsClients;
import static io.github.m4gshm.connections.client.RestOperationsUtils.extractRestOperationsUris;
import static io.github.m4gshm.connections.client.WebsocketClientUtils.extractWebsocketClientUris;
import static io.github.m4gshm.connections.model.Interface.Direction.*;
import static io.github.m4gshm.connections.model.Interface.Type.*;
import static io.github.m4gshm.connections.model.Storage.Engine.jpa;
import static io.github.m4gshm.connections.model.Storage.Engine.mongo;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableSet;
import static java.util.Map.entry;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.of;
import static java.util.stream.Stream.ofNullable;
import static org.springframework.beans.factory.BeanFactory.FACTORY_BEAN_PREFIX;

@Slf4j
@RequiredArgsConstructor
public class ComponentsExtractor {
    private final ConfigurableApplicationContext context;
    private final Options options;

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
    private static Map<String, Component> mergeComponents(Collection<Component>... components) {
        return of(components).flatMap(Collection::stream).collect(toMap(Component::getName, c -> c, (l, r) -> {
            var lInterfaces = l.getInterfaces();
            var lDependencies = l.getDependencies();
            var rInterfaces = r.getInterfaces();
            var rDependencies = r.getDependencies();
            var dependencies = new LinkedHashSet<>(lDependencies);
            dependencies.addAll(rDependencies);
            var interfaces = mergeInterfaces(lInterfaces, rInterfaces);
            return l.toBuilder()
                    .dependencies(unmodifiableSet(new LinkedHashSet<>(dependencies)))
                    .interfaces(unmodifiableSet(interfaces))
                    .build();
        }, LinkedHashMap::new));
    }

    @SafeVarargs
    private static LinkedHashSet<Interface> mergeInterfaces(Collection<Interface>... interfaces) {
        return of(interfaces).flatMap(Collection::stream).collect(toLinkedHashSet());
    }

    private static boolean isPackageMatchAny(Class<?> type, Set<String> regExps) {
        return isMatchAny(type.getPackage().getName(), regExps);
    }

    private static boolean isMatchAny(String value, Set<String> regExps) {
        return regExps.stream().anyMatch(value::matches);
    }

    private static Stream<String> toFilteredByName(Set<String> excludeBeanNames, Stream<String> beanDefinitionNames) {
        if (!excludeBeanNames.isEmpty()) {
            beanDefinitionNames = beanDefinitionNames.filter(beanName -> {
                //log
                return !isMatchAny(beanName, excludeBeanNames);
            });
        }
        return beanDefinitionNames;
    }

    private static void handleError(String errMsg, String componentName, EvalException e, boolean failFast) {
        if (failFast) {
            log.error("{} {}", errMsg, componentName, e);
            throw e;
        } else if (log.isDebugEnabled()) {
            log.debug("{} {}", errMsg, componentName, e);
        } else {
            log.info("{} {}, message '{}'", errMsg, componentName, e.getLocalizedMessage());
        }
    }

    public Components getComponents() {
        var beanFactory = context.getBeanFactory();
        var beanDefinitionNames = asList(beanFactory.getBeanDefinitionNames());

        var allBeans = getFilteredBeanNameWithType(beanDefinitionNames.stream())
                .collect(toMap(Entry::getKey, Entry::getValue, (l, r) -> {
                    //log
                    return l;
                }, () -> new LinkedHashMap<String, Class<?>>()));

        var componentCache = new HashMap<String, Set<Component>>();
        var failFast = options.isFailFast();
        var rootComponent = findRootComponent(allBeans, componentCache, failFast);

        var rootPackage = getPackage(rootComponent);
        var rootPackageName = rootPackage != null ? rootPackage.getName() : null;

        var rootGroupedBeans = allBeans.entrySet().stream().collect(groupingBy(e -> isRootRelatedBean(e.getValue(), rootPackageName)));

        var rootComponents = rootGroupedBeans.getOrDefault(true, List.of()).stream()
                .flatMap(e -> getComponents(e.getKey(), e.getValue(), rootPackage, componentCache, failFast)
                        .filter(Objects::nonNull).filter(component -> isIncluded(component.getType())))
                .collect(toList());

        var additionalComponents = rootGroupedBeans.getOrDefault(false, List.of()).stream()
                .flatMap(e -> extractInWebsocketHandlers(e.getKey(), e.getValue(),
                        rootPackage, componentCache).stream()).collect(toList());

        var componentsPerName = mergeComponents(rootComponents, additionalComponents);
        var components = options.isIgnoreNotFoundDependencies() ? componentsPerName.values().stream()
                .map(component -> component.toBuilder().dependencies(component.getDependencies().stream()
                        .filter(componentsPerName::containsKey)
                        .collect(toLinkedHashSet())).build())
                .collect(toList()) : componentsPerName.values();
        return Components.builder().components(components).build();
    }

    private Stream<Entry<String, Class<?>>> getFilteredBeanNameWithType(Stream<String> beanNames) {
        var exclude = Optional.ofNullable(this.options).map(Options::getExclude);
        var excludeBeanNames = exclude.map(BeanFilter::getBeanName).orElse(Set.of());
        var excludeTypes = exclude.map(BeanFilter::getType).orElse(Set.of());
        var excludePackages = exclude.map(BeanFilter::getPackageName).orElse(Set.of());

        return (excludeBeanNames.isEmpty() ? beanNames : toFilteredByName(excludeBeanNames, beanNames))
                .flatMap(componentName -> withTypeFilteredByPackage(componentName, excludePackages))
                .filter(e -> excludeTypes.stream().noneMatch(type -> type.isAssignableFrom(e.getValue())));
    }

    private Stream<Entry<String, Class<?>>> withTypeFilteredByPackage(String componentName, Set<String> excludePackages) {
        var componentType = getComponentType(componentName);
        if (componentType == null) {
            //log
            return Stream.empty();
        } else if (isPackageMatchAny(componentType, excludePackages)) {
            //log
            return Stream.empty();
        } else {
            return Stream.of(entry(componentName, componentType));
        }
    }

    private Component findRootComponent(Map<String, Class<?>> allBeans,
                                        Map<String, Set<Component>> componentCache,
                                        boolean failFast) {
        return allBeans.entrySet().stream()
                .filter(e -> isSpringBootMainClass(e.getValue()))
                .flatMap(e -> getComponents(e.getKey(), e.getValue(), null, componentCache, failFast))
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Stream<Component> getComponents(String componentName, Class<?> componentType,
                                            Package rootPackage, Map<String, Set<Component>> cache,
                                            boolean failFast) {
        var cached = cache.get(componentName);
        if (cached != null) {
            //log
            return cached.stream();
        }

        var feignClient = extractFeignClient(componentName, context);
        if (feignClient != null) {
            //log
            componentType = feignClient.getType();
        }

        if (componentType == null) {
            return Stream.empty();
        }

        var websocketHandlers = extractInWebsocketHandlers(componentName, componentType,
                rootPackage, cache);
        if (!websocketHandlers.isEmpty()) {
            return websocketHandlers.stream();
        } else {
            var result = new ArrayList<Component>();

            //log
            var inJmsInterface = extractMethodJmsListeners(componentType, context.getBeanFactory()).stream()
                    .map(jmsClient -> newInterface(jmsClient, componentName)).collect(toList());


            //log
            var repositoryEntityInterfaces = getRepositoryEntityInterfaces(componentName, componentType);

            var dependencies = feignClient != null ? Set.<Component>of() : getDependencies(componentName,
                    rootPackage, cache, failFast);

            //log
            var outJmsInterfaces = getOutJmsInterfaces(componentName, componentType, dependencies, failFast);
            //log
            var outWsInterfaces = getOutWsInterfaces(componentName, componentType, dependencies, failFast);
            //log
            var outRestOperationsHttpInterface = getOutRestTemplateInterfaces(componentName, componentType, dependencies, failFast);

            //log
            var outFeignHttpInterface = ofNullable(feignClient).flatMap(client -> ofNullable(client.getHttpMethods())
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .map(httpMethod -> Interface.builder().direction(out).type(http).core(httpMethod).build())
            ).collect(toList());

            //log
            var inHttpInterfaces = extractControllerHttpMethods(componentType).stream()
                    .map(httpMethod -> Interface.builder().direction(in).type(http).core(httpMethod).build()).collect(toList());

            var name = feignClient != null && !feignClient.name.equals(feignClient.url) ? feignClient.name : componentName;

            //log
            var component = Component.builder()
                    .name(name)
                    .path(getComponentPath(rootPackage, componentType))
                    .type(componentType)
                    .interfaces(of(
                            inHttpInterfaces.stream(), inJmsInterface.stream(), outFeignHttpInterface.stream(),
                            outRestOperationsHttpInterface.stream(), outWsInterfaces.stream(), outJmsInterfaces.stream(),
                            repositoryEntityInterfaces.stream()
                    ).flatMap(s -> s).collect(toLinkedHashSet()))
                    .dependencies(dependencies.stream().map(Component::getName).collect(toLinkedHashSet()))
                    .build();

            cache.put(componentName, Set.of(component));
            result.add(component);
            return result.stream();
        }
    }

    private List<Interface> getRepositoryEntityInterfaces(String componentName, Class<?> componentType) {
        var repositoryEntities = new ArrayList<Interface>();
        var repositoryClass = loadedClass(() -> Repository.class);
        if (repositoryClass == null) {
            //log
        } else if (repositoryClass.isAssignableFrom(componentType)) {
            var factoryComponentName = FACTORY_BEAN_PREFIX + componentName;
            Object factory;
            try {
                factory = context.getBean(factoryComponentName);
            } catch (BeansException e) {
                log.error("get factory error, {}", factoryComponentName, e);
                factory = null;
            }
            try {
                if (factory instanceof RepositoryFactoryInformation) {
                    var repoInfo = (RepositoryFactoryInformation<?, ?>) factory;
                    var persistentEntity = repoInfo.getPersistentEntity();
                    var type = persistentEntity.getTypeInformation().getType();
                    var entityClassName = type.getName();

                    var entityInformation = repoInfo.getEntityInformation();
                    if (entityInformation instanceof JpaMetamodelEntityInformation) {
                        var metamodel = (Metamodel) getFieldValue(entityInformation, "metamodel");
                        if (metamodel instanceof MetamodelImplementor) {
                            var hiberMetamodel = (MetamodelImplementor) metamodel;
                            var entityPersisters = hiberMetamodel.entityPersisters();
                            var entityPersister = entityPersisters.get(entityClassName);
                            if (entityPersister != null) {
                                var tables = entityPersister.getPropertySpaces();
                                repositoryEntities.add(Interface.builder()
                                        .name(entityClassName)
                                        .type(storage)
                                        .direction(undefined)
                                        .core(Storage.builder()
                                                .entityType(type)
                                                .storedTo(stream(tables).map(Object::toString).collect(toList()))
                                                .engine(jpa)
                                                .build()
                                        ).build());
                            } else {
                                //log
                            }
                        }
                    } else if (entityInformation instanceof MongoEntityInformation) {
                        var mongoInfo = (MongoEntityInformation<?, ?>) entityInformation;
                        var collectionName = mongoInfo.getCollectionName();
                        repositoryEntities.add(Interface.builder()
                                .name(entityClassName)
                                .type(storage)
                                .direction(undefined)
                                .core(Storage.builder()
                                        .entityType(type)
                                        .storedTo(List.of(collectionName))
                                        .engine(mongo)
                                        .build()
                                ).build());
                    }
                }
            } catch (NoClassDefFoundError e) {
                logUnsupportedClass(log, e);
            }
        }
        return repositoryEntities;
    }

    private Class<?> getComponentType(String beanName) {
        Class<?> componentType;
        try {
            componentType = context.getType(beanName);
        } catch (NoSuchBeanDefinitionException e) {
            //log
            componentType = null;
        }
        return unproxy(componentType);
    }

    private Set<Interface> getOutJmsInterfaces(String componentName, Class<?> componentType,
                                               Collection<Component> dependencies, boolean failFast) {
        var jmsTemplate = findDependencyByType(dependencies, () -> JmsOperations.class);
        if (jmsTemplate != null) try {
            var jmsClients = extractJmsClients(componentName, componentType, context);

            return jmsClients.stream()
                    .map(jmsClient -> newInterface(jmsClient, componentName))
                    .collect(toLinkedHashSet());

        } catch (EvalException e) {
            handleError("jms client getting error, component", componentName, e, failFast);
        }
        return Set.of();
    }

    private Set<Interface> getOutWsInterfaces(String componentName, Class<?> componentType, Collection<Component> dependencies, boolean failFast) {
        var wsClient = findDependencyByType(dependencies, () -> WebSocketClient.class);
        if (wsClient != null) try {
            var wsClientUris = extractWebsocketClientUris(componentName, componentType, context);

            return wsClientUris.stream()
                    .map(uri -> Interface.builder().direction(out).type(ws).name(uri).build())
                    .collect(toLinkedHashSet());

        } catch (EvalException e) {
            handleError("jws client getting error, component", componentName, e, failFast);
        }
        return Set.of();
    }

    private Set<Interface> getOutRestTemplateInterfaces(String componentName, Class<?> componentType, Collection<Component> dependencies, boolean failFast) {
        var restTemplate = findDependencyByType(dependencies, () -> RestOperations.class);
        if (restTemplate != null) try {
            var httpMethods = extractRestOperationsUris(componentName, componentType, context);
            return httpMethods.stream().map(httpMethod -> Interface.builder().direction(out).type(http)
                            .core(httpMethod).build())
                    .collect(toLinkedHashSet());
        } catch (EvalException e) {
            handleError("rest operations client getting error, component", componentName, e, failFast);
        }
        return Set.of();
    }

    private Set<Component> getDependencies(String componentName, Package rootPackage, Map<String, Set<Component>> cache, boolean failFast) {
        return getFilteredBeanNameWithType(stream(context.getBeanFactory().getDependenciesForBean(componentName)))
                .flatMap(e -> getComponents(e.getKey(), e.getValue(), rootPackage, cache, failFast)
                        .filter(Objects::nonNull).filter(component -> isIncluded(component.getType())))
                .collect(toLinkedHashSet());
    }

    private Collection<Component> extractInWebsocketHandlers(
            String componentName, Class<?> componentType, Package rootPackage, Map<String, Set<Component>> cache) {
        var webSocketConfigClass = loadedClass(() -> WebSocketConfigurationSupport.class);
        if (webSocketConfigClass == null) {
            //log
        } else if (webSocketConfigClass.isAssignableFrom(componentType)) {
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

    @Data
    @Builder
    public static class Options {
        private final BeanFilter exclude;
        private boolean failFast;
        private boolean ignoreNotFoundDependencies;

        @Data
        @Builder
        public static class BeanFilter {
            private final Set<String> packageName;
            private final Set<String> beanName;
            private final Set<Class<?>> type;
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
        private final String name;
        private final String destination;
        private final Direction direction;

    }
}
