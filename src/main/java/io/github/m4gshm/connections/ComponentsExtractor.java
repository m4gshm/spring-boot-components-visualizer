package io.github.m4gshm.connections;

import io.github.m4gshm.connections.ComponentsExtractor.Options.BeanFilter;
import io.github.m4gshm.connections.bytecode.EvalException;
import io.github.m4gshm.connections.model.*;
import io.github.m4gshm.connections.model.Interface.Direction;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;
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
import java.lang.Package;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ComponentsExtractor.ComponentKey.newComponentKey;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.*;
import static io.github.m4gshm.connections.UriUtils.joinURI;
import static io.github.m4gshm.connections.Utils.*;
import static io.github.m4gshm.connections.bytecode.EvalUtils.unproxy;
import static io.github.m4gshm.connections.client.JmsOperationsUtils.extractJmsClients;
import static io.github.m4gshm.connections.client.RestOperationsUtils.extractRestOperationsUris;
import static io.github.m4gshm.connections.client.WebsocketClientUtils.extractWebsocketClientUris;
import static io.github.m4gshm.connections.model.Interface.Direction.*;
import static io.github.m4gshm.connections.model.Interface.Type.*;
import static io.github.m4gshm.connections.model.Storage.Engine.jpa;
import static io.github.m4gshm.connections.model.Storage.Engine.mongo;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableSet;
import static java.util.Map.entry;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.*;
import static lombok.AccessLevel.PRIVATE;
import static org.springframework.beans.factory.BeanFactory.FACTORY_BEAN_PREFIX;

@Slf4j
//@RequiredArgsConstructor
public class ComponentsExtractor {
    private final ConfigurableApplicationContext context;
    private final Options options;

    public ComponentsExtractor(ConfigurableApplicationContext context, Options options) {
        this.context = context;
        this.options = options != null ? options : Options.DEFAULT;
    }

    public static Interface newInterface(JmsClient jmsClient) {
        return Interface.builder().direction(jmsClient.direction).type(jms).name(jmsClient.destination).core(
                JmsClient.Destination.builder().destination(jmsClient.destination).direction(jmsClient.direction).build()
        ).build();
    }

    public static boolean isRootRelatedBean(Class<?> type, String rootPackageName) {
        if (rootPackageName != null) {
            var relatedType = Stream.ofNullable(type)
                    .flatMap(aClass -> concat(Stream.of(entry(aClass, aClass.getPackage())), getInterfaces(aClass)
                            .map(c -> entry(c, c.getPackage()))))
                    .filter(e -> e.getValue().getName().startsWith(rootPackageName)).findFirst().orElse(null);
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
        return of(components).flatMap(Collection::stream).collect(toMap(ComponentKey::newComponentKey, c -> c, (l, r) -> {
            var lInterfaces = l.getInterfaces();
            var lDependencies = l.getDependencies();
            var rInterfaces = r.getInterfaces();
            var rDependencies = r.getDependencies();
            var dependencies = new LinkedHashSet<>(lDependencies);
            dependencies.addAll(rDependencies);
            var interfaces = mergeInterfaces(lInterfaces, rInterfaces);
            return l.toBuilder().dependencies(unmodifiableSet(new LinkedHashSet<>(dependencies))).interfaces(unmodifiableSet(interfaces)).build();
        }, LinkedHashMap::new));
    }

    @SafeVarargs
    public static LinkedHashSet<Interface> mergeInterfaces(Collection<Interface>... interfaces) {
        return of(interfaces).flatMap(Collection::stream).collect(toLinkedHashSet());
    }

    public static boolean isPackageMatchAny(Class<?> type, Set<String> regExps) {
        return isMatchAny(type.getPackage().getName(), regExps);
    }

    public static boolean isMatchAny(String value, Set<String> regExps) {
        return regExps.stream().anyMatch(value::matches);
    }

    public static Stream<String> toFilteredByName(Set<String> excludeBeanNames, Stream<String> beanDefinitionNames) {
        if (!excludeBeanNames.isEmpty()) {
            beanDefinitionNames = beanDefinitionNames.filter(beanName -> {
                //log
                return !isMatchAny(beanName, excludeBeanNames);
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
            log.info("{} {}, message '{}'", errMsg, componentName, e.getLocalizedMessage());
        }
    }

    public static Stream<Component> flatDependencies(Component component) {
        var dependencies = component.getDependencies();
        return concat(Stream.of(component), dependencies != null ? dependencies.stream() : empty());
    }

    private static Component getComponentWithFilteredDependencies(Component component,
                                                                  Map<ComponentKey, Component> componentsPerName) {
        var dependencies = component.getDependencies();
        return dependencies != null && !dependencies.isEmpty() ? component.toBuilder()
                .dependencies(dependencies.stream()
                        .filter(componentDependency -> componentsPerName.containsKey(newComponentKey(componentDependency)))
                        .collect(toLinkedHashSet()))
                .build() : component;
    }

    private static Component newManagedDependency(String oName) {
        return Component.builder().name(oName).build();
    }

    protected static String getWebsocketInterfaceId(Direction direction, String uri) {
        return direction + ":" + ws + ":" + uri;
    }

    private static Package getFieldType(Class<?> type) {
        return type.isArray() ? getFieldType(type.getComponentType()) : type.getPackage();
    }

    public Components getComponents() {
        var beanFactory = context.getBeanFactory();
        var beanDefinitionNames = asList(beanFactory.getBeanDefinitionNames());

        var allBeans = getFilteredBeanNameWithType(beanDefinitionNames.stream())
                .collect(toMap(Entry::getKey, Entry::getValue, (l, r) -> {
                    //log
                    return l;
                }, LinkedHashMap::new));

        var componentCache = new HashMap<String, Set<Component>>();
        var failFast = options.isFailFast();
        var rootComponent = findRootComponent(allBeans, componentCache);

        var rootPackage = getPackage(rootComponent);
        var rootPackageName = rootPackage != null ? rootPackage.getName() : null;

        var rootGroupedBeans = allBeans.entrySet().stream()
                .collect(groupingBy(e -> isRootRelatedBean(e.getValue(), rootPackageName)));

        var rootComponents = rootGroupedBeans.getOrDefault(true, List.of()).stream()
                .flatMap(e -> getComponents(e.getKey(), e.getValue(), rootPackage, componentCache)
                        .filter(Objects::nonNull)
                        .filter(component -> isIncluded(component.getType()))).collect(toList());

        var additionalComponents = rootGroupedBeans.getOrDefault(false, List.of()).stream()
                .flatMap(e -> extractInWebsocketHandlers(e.getKey(), e.getValue(), rootPackage, componentCache).stream())
                .collect(toList());

        var componentsPerName = mergeComponents(rootComponents, additionalComponents);
        var components = options.isIgnoreNotFoundDependencies()
                ? componentsPerName.values().stream()
                .map(component -> getComponentWithFilteredDependencies(component, componentsPerName))
                .collect(toList())
                : componentsPerName.values();
        return Components.builder().components(components).build();
    }

    protected Stream<Entry<String, Class<?>>> getFilteredBeanNameWithType(Stream<String> beanNames) {
        var exclude = Optional.ofNullable(this.options).map(Options::getExclude);
        var excludeBeanNames = exclude.map(BeanFilter::getBeanName).orElse(Set.of());
        var excludeTypes = exclude.map(BeanFilter::getType).orElse(Set.of());
        var excludePackages = exclude.map(BeanFilter::getPackageName).orElse(Set.of());

        return (excludeBeanNames.isEmpty() ? beanNames : toFilteredByName(excludeBeanNames, beanNames))
                .flatMap(componentName -> withTypeFilteredByPackage(componentName, excludePackages))
                .filter(e -> excludeTypes.stream().noneMatch(type -> type.isAssignableFrom(e.getValue())));
    }

    protected Stream<Entry<String, Class<?>>> withTypeFilteredByPackage(String componentName, Set<String> excludePackages) {
        var componentType = getComponentType(componentName);
        if (componentType == null) {
            //log
            return empty();
        } else if (isPackageMatchAny(componentType, excludePackages)) {
            //log
            return empty();
        } else {
            return Stream.of(entry(componentName, componentType));
        }
    }

    protected Component findRootComponent(Map<String, Class<?>> allBeans,
                                          Map<String, Set<Component>> componentCache) {
        return allBeans.entrySet().stream().filter(e -> isSpringBootMainClass(e.getValue()))
                .flatMap(e -> getComponents(e.getKey(), e.getValue(), null, componentCache))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    protected Stream<Component> getComponents(String componentName, Class<?> componentType,
                                              Package rootPackage, Map<String, Set<Component>> cache) {
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
            return empty();
        }

        var websocketHandlers = extractInWebsocketHandlers(componentName, componentType, rootPackage, cache);
        if (!websocketHandlers.isEmpty()) {
            return websocketHandlers.stream();
        } else {
            var result = new ArrayList<Component>();

            //log
            var inJmsInterface = extractMethodJmsListeners(componentType, context.getBeanFactory())
                    .stream().map(ComponentsExtractor::newInterface).collect(toList());

            //log
            var repositoryEntityInterfaces = getRepositoryEntityInterfaces(componentName, componentType);

            var dependencies = feignClient == null
                    ? getDependencies(componentName, rootPackage, cache)
                    : Set.<Component>of();

            //log
            var outJmsInterfaces = getOutJmsInterfaces(componentName, componentType, dependencies);
            //log
            var outWsInterfaces = getOutWsInterfaces(componentName, componentType, dependencies);
            //log
            var outRestOperationsHttpInterface = getOutRestTemplateInterfaces(componentName, componentType, dependencies);
            //log
            var outFeignHttpInterface = ofNullable(feignClient)
                    .flatMap(client -> ofNullable(client.getHttpMethods()).filter(Objects::nonNull)
                            .flatMap(Collection::stream).map(httpMethod -> {
                                var clientUrl = client.getUrl();
                                var methodUrl = httpMethod.getUrl();
                                if (clientUrl != null && !clientUrl.startsWith(methodUrl)) {
                                    httpMethod = httpMethod.toBuilder().url(joinURI(clientUrl, methodUrl)).build();
                                }
                                return Interface.builder().direction(out).type(http).core(httpMethod).build();
                            })).collect(toList());

            //log
            var inHttpInterfaces = extractControllerHttpMethods(componentType).stream()
                    .map(httpMethod -> Interface.builder().direction(in).type(http).core(httpMethod).build())
                    .collect(toList());

            var name = feignClient != null && !feignClient.name.equals(feignClient.url) ? feignClient.name : componentName;

            //log
            var component = Component.builder().name(name)
                    .path(getComponentPath(componentType, rootPackage))
                    .type(componentType)
                    .interfaces(of(
                            inHttpInterfaces.stream(), inJmsInterface.stream(), outFeignHttpInterface.stream(),
                            outRestOperationsHttpInterface.stream(), outWsInterfaces.stream(),
                            outJmsInterfaces.stream(), repositoryEntityInterfaces.stream())
                            .flatMap(s -> s)
                            .collect(toLinkedHashSet()))
                    .dependencies(dependencies).build();

            cache.put(componentName, Set.of(component));
            result.add(component);
            return result.stream();
        }
    }

    protected String getComponentPath(Class<?> componentType, Package rootPackage) {
        var typePackageName = componentType.getPackage().getName();
        final String path;
        if (options.isCropRootPackagePath()) {
            var rootPackageName = Optional.ofNullable(rootPackage).map(Package::getName).orElse("");
            path = typePackageName.startsWith(rootPackageName)
                    ? typePackageName.substring(rootPackageName.length())
                    : typePackageName;
        } else {
            path = typePackageName;
        }
        return path.startsWith(".") ? path.substring(1) : path;
    }

    protected List<Interface> getRepositoryEntityInterfaces(String componentName, Class<?> componentType) {
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
                                        .direction(internal)
                                        .core(Storage.builder()
                                                .entityType(type)
                                                .storedTo(stream(tables).map(Object::toString).collect(toList()))
                                                .engine(jpa)
                                                .build())
                                        .build());
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
                                .direction(internal)
                                .core(Storage.builder()
                                        .entityType(type)
                                        .storedTo(List.of(collectionName))
                                        .engine(mongo)
                                        .build())
                                .build());
                    }
                }
            } catch (NoClassDefFoundError e) {
                logUnsupportedClass(log, e);
            }
        }
        return repositoryEntities;
    }

    protected Class<?> getComponentType(String beanName) {
        Class<?> componentType;
        try {
            componentType = context.getType(beanName);
        } catch (NoSuchBeanDefinitionException e) {
            //log
            componentType = null;
        }
        return unproxy(componentType);
    }

    protected Set<Interface> getOutJmsInterfaces(String componentName, Class<?> componentType,
                                                 Collection<Component> dependencies) {
        var jmsTemplate = findDependencyByType(dependencies, () -> JmsOperations.class);
        if (jmsTemplate != null) try {
            var jmsClients = extractJmsClients(componentName, componentType, context);

            return jmsClients.stream().map(ComponentsExtractor::newInterface).collect(toLinkedHashSet());

        } catch (EvalException e) {
            handleError("jms client getting error, component", componentName, e, options.failFast);
        }
        return Set.of();
    }

    protected Set<Interface> getOutWsInterfaces(String componentName, Class<?> componentType,
                                                Collection<Component> dependencies) {
        var wsClient = findDependencyByType(dependencies, () -> WebSocketClient.class);
        if (wsClient != null) try {
            var wsClientUris = extractWebsocketClientUris(componentName, componentType, context);

            return wsClientUris.stream()
                    .map(uri -> Interface.builder()
                            .direction(out).type(ws).name(uri)
                            .id(getWebsocketInterfaceId(out, uri))
                            .build())
                    .collect(toLinkedHashSet());

        } catch (EvalException e) {
            handleError("jws client getting error, component", componentName, e, options.failFast);
        }
        return Set.of();
    }

    protected Set<Interface> getOutRestTemplateInterfaces(String componentName, Class<?> componentType,
                                                          Collection<Component> dependencies) {
        var restTemplate = findDependencyByType(dependencies, () -> RestOperations.class);
        if (restTemplate != null) try {
            var httpMethods = extractRestOperationsUris(componentName, componentType, context);
            return httpMethods.stream()
                    .map(httpMethod -> Interface.builder()
                            .direction(out).type(http)
                            .core(httpMethod)
                            .build())
                    .collect(toLinkedHashSet());
        } catch (EvalException e) {
            handleError("rest operations client getting error, component", componentName, e, options.failFast);
        }
        return Set.of();
    }

    protected Set<Component> getDependencies(String componentName, Package rootPackage,
                                             Map<String, Set<Component>> cache) {
        return getFilteredBeanNameWithType(stream(context.getBeanFactory().getDependenciesForBean(componentName)))
                .flatMap(e -> getComponents(e.getKey(), e.getValue(), rootPackage, cache)
                        .filter(Objects::nonNull).filter(component -> isIncluded(component.getType())))
                .collect(toLinkedHashSet());
    }

    protected Collection<Component> extractInWebsocketHandlers(String componentName, Class<?> componentType,
                                                               Package rootPackage, Map<String, Set<Component>> cache) {
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
                        return getWebsocketComponents(wsUrl, wsHandlerPath, rootPackage, cache);
                    }).filter(Objects::nonNull).collect(toLinkedHashSet());
                    cache.put(componentName, components);
                    return components;
                }
            }
        }
        return Set.of();
    }

    protected Stream<Component> getWebsocketComponents(String wsUrl, Object wsHandler, Package rootPackage,
                                                       Map<String, Set<Component>> cache) {

        var anInterface = Interface.builder()
                .direction(in)
                .type(ws)
                .id(getWebsocketInterfaceId(in, wsUrl))
                .name(wsUrl)
                .build();

        if (wsHandler instanceof WebSocketHttpRequestHandler) {
            final Stream<Component> componentStream;
            var webSocketHttpRequestHandler = (WebSocketHttpRequestHandler) wsHandler;
            var webSocketHandler = webSocketHttpRequestHandler.getWebSocketHandler();
            if (webSocketHandler instanceof WebSocketHandlerDecorator) {
                //log
                webSocketHandler = ((WebSocketHandlerDecorator) webSocketHandler).getLastHandler();
            }
            var webSocketHandlerName = findBeanName(webSocketHandler, WebSocketHandler.class);

            var managed = webSocketHandlerName != null;
            var cached = managed ? cache.get(webSocketHandlerName) : null;
            if (cached != null) {
                cached = cached.stream().map(component -> {
                    var interfaces = component.getInterfaces();
                    if (!interfaces.contains(anInterface)) {
                        //log
                        var newInterfaces = new LinkedHashSet<>(interfaces);
                        newInterfaces.add(anInterface);
                        return component.toBuilder().interfaces(unmodifiableSet(newInterfaces)).build();
                    } else return component;
                }).collect(toLinkedHashSet());

                cache.put(webSocketHandlerName, cached);
                componentStream = cached.stream();
            } else {
                var webSocketHandlerClass = webSocketHandler.getClass();
                var webSocketHandlerComponentBuilder = (
                        (managed)
                                ? Component.builder().name(webSocketHandlerName)
                                : Component.builder().unmanagedInstance(webSocketHandler)
                )
                        .type(webSocketHandlerClass)
                        .path(getComponentPath(webSocketHandlerClass, rootPackage));

                var unmanagedDependencies = getUnmanagedDependencies(webSocketHandlerClass, webSocketHandler, new HashMap<>());
                final var dependencies = !managed ? unmanagedDependencies : Stream.concat(
                        getDependencies(webSocketHandlerName, rootPackage, cache).stream(),
                        unmanagedDependencies.stream()).collect(toLinkedHashSet());

                var webSocketHandlerComponent = webSocketHandlerComponentBuilder
                        .path(getComponentPath(webSocketHandlerClass, rootPackage))
                        .interfaces(Set.of(anInterface))
                        .dependencies(unmodifiableSet(dependencies)).build();

                componentStream = flatDependencies(webSocketHandlerComponent);
            }
            return componentStream;
        } else {
            return empty();
        }
    }

    protected Set<Component> getUnmanagedDependencies(Class<?> componentType, Object unmanagedInstance,
                                                      Map<Object, Set<Component>> touched) {

        if (isIgnoreUnmanagedTypes(componentType)) {
            //log trace
            return Set.of();
        }
        var dependencies = new LinkedHashSet<Component>();
        var isManaged = unmanagedInstance == null;
        var isUnmanaged = !isManaged;
        while (componentType != null && !Object.class.equals(componentType)) {
            var declaredFields = componentType.getDeclaredFields();
            of(declaredFields).filter(field -> {
                        var type = field.getType();
                        var typePackage = getFieldType(type);

                        return !type.isPrimitive() && !field.isSynthetic() && !isStatic(field.getModifiers()) &&
                                (type.equals(Objects.class) || !typePackage.equals(String.class.getPackage()));
                    })
                    .map(field -> {
                        //log
                        return getFieldValue(unmanagedInstance, field, options.failFast);
                    })
                    .filter(Objects::nonNull).forEach(value -> {
                        var alreadyTouched = touched.get(value);
                        if (alreadyTouched != null) {
                            //log
                            dependencies.addAll(alreadyTouched);
                        } else {
                            var managedDependencyName = findBeanName(value);
                            if (managedDependencyName != null && isUnmanaged) {
                                dependencies.add(newManagedDependency(managedDependencyName));
                            } else if (value instanceof Collection<?>) {
                                //log
                                var collection = (Collection<?>) value;
                                var aggregated = collection.stream().map(o -> {
                                    var oName = findBeanName(value);
                                    //todo check o is Collection
                                    return oName != null && isUnmanaged
                                            ? newManagedDependency(oName)
                                            : oName == null ? newUnmanagedDependency(o) : null;
                                }).filter(Objects::nonNull).collect(toList());
                                dependencies.addAll(aggregated);
                            } else {
                                dependencies.add(newUnmanagedDependency(value));
                            }
                            touched.put(value, new LinkedHashSet<>(dependencies));
                        }
                    });
            componentType = componentType.getSuperclass();
        }
        return dependencies;
    }

    protected boolean isIgnoreUnmanagedTypes(Class<?> componentType) {
        return componentType == null
                || Collection.class.isAssignableFrom(componentType)
                || Map.class.isAssignableFrom(componentType);
    }

    protected Component newUnmanagedDependency(Object value) {
        return Component.builder()
                .unmanagedInstance(value)
                .dependencies(getUnmanagedDependencies(value.getClass(), value, new LinkedHashMap<>()))
                .build();
    }

    protected String findBeanName(Object object) {
        return findBeanName(object, object.getClass());
    }

    protected String findBeanName(Object object, Class<?> expectedType) {
        return of(context.getBeanNamesForType(expectedType)).filter(name -> {
            try {
                var bean = context.getBean(name);
                return object == bean;
            } catch (NoSuchBeanDefinitionException e) {
                //log
                return false;
            }
        }).findFirst().orElse(null);
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class ComponentKey {
        String name;
        Object unmanagedInstance;

        public static ComponentKey newComponentKey(Component componentDependency) {
            return new ComponentKey(componentDependency.getName(), componentDependency.getUnmanagedInstance());
        }

    }

    @Data
    @Builder(toBuilder = true)
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class Options {
        public static final Options DEFAULT = Options.builder().build();
        BeanFilter exclude;
        boolean failFast;
        @Builder.Default
        boolean ignoreNotFoundDependencies = true;
        boolean cropRootPackagePath;

        @Data
        @Builder
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        public static class BeanFilter {
            Set<String> packageName;
            Set<String> beanName;
            Set<Class<?>> type;
        }
    }

    @Data
    @Builder
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class FeignClient {
        Class<?> type;
        String name;
        String url;
        List<HttpMethod> httpMethods;
    }

    @Data
    @Builder
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class JmsClient {
        String name;
        String destination;
        Direction direction;

        @Data
        @Builder
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        public static class Destination {
            String destination;
            Direction direction;

            @Override
            public String toString() {
                return destination + ":" + direction;
            }
        }

    }
}
