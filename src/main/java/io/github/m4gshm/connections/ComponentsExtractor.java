package io.github.m4gshm.connections;

import io.github.m4gshm.connections.CallPointsHelper.CallPointsProvider;
import io.github.m4gshm.connections.ComponentsExtractor.Options.BeanFilter;
import io.github.m4gshm.connections.eval.bytecode.*;
import io.github.m4gshm.connections.eval.bytecode.EvalContextFactoryImpl.DependentProvider;
import io.github.m4gshm.connections.eval.result.Resolver;
import io.github.m4gshm.connections.eval.result.Result;
import io.github.m4gshm.connections.eval.result.Result.RelationsAware;
import io.github.m4gshm.connections.model.*;
import io.github.m4gshm.connections.model.Component.ComponentKey;
import io.github.m4gshm.connections.model.Interface.Direction;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.generic.Type;
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

import java.lang.Package;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.CallPointsHelper.getMethods;
import static io.github.m4gshm.connections.CallPointsHelper.isObject;
import static io.github.m4gshm.connections.ComponentsExtractorUtils.*;
import static io.github.m4gshm.connections.UriUtils.joinURI;
import static io.github.m4gshm.connections.Utils.*;
import static io.github.m4gshm.connections.client.JmsOperationsUtils.extractJmsClients;
import static io.github.m4gshm.connections.client.RestOperationsUtils.extractRestOperationsUris;
import static io.github.m4gshm.connections.client.WebsocketClientUtils.extractWebsocketClientUris;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.lookupClassInheritanceHierarchy;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.unproxy;
import static io.github.m4gshm.connections.eval.bytecode.EvalContextFactoryImpl.getCallPoints;
import static io.github.m4gshm.connections.eval.bytecode.StringifyResolver.Level.full;
import static io.github.m4gshm.connections.eval.bytecode.StringifyResolver.Level.varOnly;
import static io.github.m4gshm.connections.model.Component.ComponentKey.newComponentKey;
import static io.github.m4gshm.connections.model.Interface.Direction.*;
import static io.github.m4gshm.connections.model.Interface.Type.*;
import static io.github.m4gshm.connections.model.StorageEntity.Engine.jpa;
import static io.github.m4gshm.connections.model.StorageEntity.Engine.mongo;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableSet;
import static java.util.Map.entry;
import static java.util.function.UnaryOperator.identity;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.*;
import static lombok.AccessLevel.PRIVATE;
import static org.springframework.beans.factory.BeanFactory.FACTORY_BEAN_PREFIX;

@Slf4j
public class ComponentsExtractor {
    private static final Class<WebSocketConfigurationSupport> webSocketConfigClass;
    private static final Class<Repository> repositoryClass;

    static {
        repositoryClass = loadedClass(() -> Repository.class);
        if (repositoryClass == null) {
            log.info("Sprint Data Repository is not supported");
        }
        webSocketConfigClass = loadedClass(() -> WebSocketConfigurationSupport.class);
        if (webSocketConfigClass == null) {
            log.info("Sprint Websocket WebSocketConfigurationSupport is not supported");
        }
    }

    private final ConfigurableApplicationContext context;
    private final Options options;

    public ComponentsExtractor(ConfigurableApplicationContext context, Options options) {
        this.context = context;
        this.options = options != null ? options : Options.DEFAULT;
    }

    private static Set<Interface> getOutFeignHttpInterfaces(FeignClient feignClient) {
        return ofNullable(feignClient).flatMap(client -> ofNullable(client.getHttpMethods())
                .filter(Objects::nonNull).flatMap(Collection::stream).map(httpMethod -> {
                    var clientUrl = client.getUrl();
                    var methodUrl = httpMethod.getPath();
                    if (clientUrl != null && !clientUrl.startsWith(methodUrl)) {
                        var joinURI = joinURI(clientUrl, methodUrl);
                        httpMethod = httpMethod.toBuilder().path(joinURI).build();
                    }
                    return Interface.builder().direction(out).type(http).core(httpMethod)
                            .methodSource(httpMethod.getMethodSource())
                            .build();
                })).collect(toLinkedHashSet());
    }

    public static List<JavaClass> getClassHierarchy(Class<?> componentType) {
        List<JavaClass> javaClasses;
        try {
            javaClasses = lookupClassInheritanceHierarchy(componentType);
        } catch (EvalBytecodeException e) {
            log.debug("getClassHierarchy {}", componentType, e);
            javaClasses = List.of();
        }
        return javaClasses;
    }

    public static Map<Component, List<Component>> getDependencyToDependentMap(Collection<Component> components) {
        return components.stream().flatMap(c -> ofNullable(c.getDependencies()).flatMap(d -> d.stream()
                        .map(dependency -> entry(dependency, c))))
                .collect(groupingBy(Entry::getKey, mapping(Entry::getValue, toList())));
    }

    private static Component filterUnusedInterfaces(Component component, ComponentProvider changedComponentProvider,
                                                    DependentProvider dependentProvider, CallPointsProvider callPointsProvider) {
        var interfaces = component.getInterfaces();
        if (interfaces == null || interfaces.isEmpty()) {
            return component;
        }
        var usedInterfaces = interfaces.stream().filter(iface -> {
            return isCalled(iface, component, changedComponentProvider, dependentProvider, callPointsProvider);
        }).collect(toLinkedHashSet());
        return !interfaces.equals(usedInterfaces)
                ? component.toBuilder().interfaces(usedInterfaces).build()
                : component;
    }

    private static boolean isCalled(Interface iface, Component component,
                                    ComponentProvider changedComponentProvider,
                                    DependentProvider dependentProvider,
                                    CallPointsProvider callPointsProvider) {
        if (iface.isExternalCallable()) {
            return true;
        }
        var result = iface.getEvalSource();
        var methodSource = iface.getMethodSource();
        if (result != null) {
            var relations = getTopRelations(result);

            var callGroups = relations.stream().collect(partitioningBy(relation -> {
                var method = relation.getMethod();
                return isUncalled(iface, component, relation.getComponent(), method.getName(), method.getArgumentTypes(),
                        dependentProvider, callPointsProvider);
            }));
            var uncalled = callGroups.get(true);

            var externalCallableGroup = uncalled.stream().collect(partitioningBy(r -> {
                var rComponent = r.getComponent();
                var componentKey = newComponentKey(rComponent);
                var changedComponent = Optional.ofNullable(changedComponentProvider.apply(componentKey)).orElse(rComponent);
                var interfaces = changedComponent.getInterfaces();
                return interfaces != null && interfaces.stream().anyMatch(Interface::isExternalCallable);
            }));
            var externalCallable = !externalCallableGroup.get(true).isEmpty();

            return externalCallable || uncalled.isEmpty();
        } else if (methodSource != null) {
            return !isUncalled(iface, component, component, methodSource.getName(), methodSource.getArgumentTypes(),
                    dependentProvider, callPointsProvider);
        }
        return true;
    }

    private static boolean isUncalled(Interface iface, Component component,
                                      Component relatedComponent, String methodName, Type[] methodArgumentTypes,
                                      DependentProvider dependentProvider, CallPointsProvider callPointsProvider) {
        var methodCallPoints = getCallPoints(relatedComponent, methodName, methodArgumentTypes,
                dependentProvider, callPointsProvider);
        var anotherDependent = methodCallPoints.keySet().stream().filter(c -> !c.equals(component)).collect(toList());
        var uncalled = anotherDependent.isEmpty();
        if (uncalled) {
            log.info("exclude unused interface {} of component {}", iface.getId(), component.getName());
        }
        return uncalled;
    }

    private static Set<Result> getTopRelations(Result result) {
        if (result instanceof RelationsAware) {
            var relations = ((RelationsAware) result).getRelations();
            if (!relations.isEmpty()) {
                return relations.stream().flatMap(r -> getTopRelations(r).stream()).collect(toSet());
            }
        }
        return Set.of(result);
    }

    private static DependentProvider newDependentProvider(Map<Component, List<Component>> dependencyToDependentWitInterfacesMap) {
        return c -> dependencyToDependentWitInterfacesMap.getOrDefault(c, List.of());
    }

    private static CallPointsProvider newCallPointsProvider(HashMap<Component, List<CallPoint>> callPointsCache) {
        return c -> {
            if (callPointsCache.containsKey(c)) {
                return callPointsCache.get(c);
            } else {
                callPointsCache.put(c, List.of());
            }

            var componentType = c.getType();
            var javaClasses = getClassHierarchy(componentType);

            List<CallPoint> points = javaClasses.stream().filter(javaClass -> !isObject(javaClass)
            ).flatMap(javaClass -> getMethods(javaClass, componentType)).filter(Objects::nonNull).collect(toList());
            callPointsCache.put(c, points);
            return points;
        };
    }

    public Components getComponents() {
        var beanFactory = context.getBeanFactory();
        var beanDefinitionNames = asList(beanFactory.getBeanDefinitionNames());

        var allBeans = getFilteredBeanNameWithType(beanDefinitionNames.stream())
                .collect(toMap(Entry::getKey, Entry::getValue, (l, r) -> {
                    log.trace("duplicated components {}", l.getName());
                    return l;
                }, LinkedHashMap::new));

        var componentCache = new HashMap<String, Set<Component>>();
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
        var components = componentsPerName.values();

        var evalCache = new HashMap<EvalContextFactoryCacheImpl.Key, Eval>();
        var callCache = new HashMap<CallCacheKey, Result>();

        var resolver = StringifyResolver.newStringify(options.getStringifyLevel(), options.isFailFast(), callCache);

        var dependentProvider = newDependentProvider(getDependencyToDependentMap(components));
        var callPointsProvider = newCallPointsProvider(new HashMap<>());

        var evalContextFactory = new EvalContextFactoryCacheImpl(
                new EvalContextFactoryImpl(callCache, dependentProvider, callPointsProvider), evalCache
        );

        var componentsWithInterfaces = components.stream().map(component -> {
            return populateInterfaces(component, evalContextFactory, resolver, callCache);
        }).collect(toList());

        var componentWithInterfacesMap = componentsWithInterfaces.stream().collect(toMap(ComponentKey::newComponentKey, identity()));

        var filteredComponentsWithInterfaces = componentsWithInterfaces.stream()
                .map(component -> !options.isIncludeUnusedOutInterfaces() ?
                        filterUnusedInterfaces(component, componentWithInterfacesMap::get, dependentProvider, callPointsProvider)
                        : component)
                .map(component -> options.isIgnoreNotFoundDependencies()
                        ? getComponentWithFilteredDependencies(component, componentsPerName)
                        : component
                ).collect(toLinkedHashSet());

        return Components.builder().components(filteredComponentsWithInterfaces).build();
    }

    private Component populateInterfaces(Component component, EvalContextFactory evalContextFactory,
                                         StringifyResolver resolver, HashMap<CallCacheKey, Result> callCache) {
        var exists = component.getInterfaces();
        var interfaces = getInterfaces(component, component.getName(), component.getType(),
                component.getDependencies(), callCache, evalContextFactory, resolver);
        if (exists == null) {
            exists = interfaces;
        } else if (interfaces != null && !interfaces.isEmpty()) {
            exists = new LinkedHashSet<>(exists);
            exists.addAll(interfaces);
        }
        return component.toBuilder().interfaces(exists).build();
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
            log.trace("null type for component {}", componentName);
            return empty();
        } else if (isPackageMatchAny(componentType, excludePackages)) {
            log.info("component is excluded by package, component {}, type {}", componentName, componentType.getName());
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
            return cached.stream();
        }

        var object = context.getBean(componentName);
        var feignClient = extractFeignClient(componentName, object);
        if (feignClient != null) {
            componentType = feignClient.getType();
            var interfaces = getOutFeignHttpInterfaces(feignClient);
            var name = !feignClient.name.equals(feignClient.url) ? feignClient.name : componentName;
            var component = Component.builder()
                    .name(name)
                    .object(object)
                    .path(getComponentPath(componentType, rootPackage))
                    .type(componentType)
                    .configuration(isSpringConfiguration(componentType))
                    .interfaces(interfaces)
                    .build();
            cache.put(componentName, Set.of(component));
            return Stream.of(component);
        } else {
            var websocketHandlers = extractInWebsocketHandlers(componentName, componentType, rootPackage, cache);
            if (!websocketHandlers.isEmpty()) {
                return websocketHandlers.stream();
            } else {
                var dependencies = getDependencies(componentName, rootPackage, cache);
                var component = Component.builder()
                        .name(componentName)
                        .object(object)
                        .path(getComponentPath(componentType, rootPackage))
                        .type(componentType)
                        .configuration(isSpringConfiguration(componentType))
                        .dependencies(dependencies)
                        .build();
                cache.put(componentName, Set.of(component));
                return Stream.of(component);
            }
        }
    }

    private Set<Interface> getInterfaces(Component component, String componentName, Class<?> componentType,
                                         Set<Component> dependencies, Map<CallCacheKey, Result> callCache,
                                         EvalContextFactory evalContextFactory, Resolver resolver) {
        var inJmsInterface = extractMethodJmsListeners(componentType, context.getBeanFactory())
                .stream().map(jmsClient -> newInterface(jmsClient, true)).collect(toList());
        var inHttpInterfaces = extractControllerHttpMethods(componentType).stream()
                .map(httpMethod -> Interface.builder().direction(in).type(http).core(httpMethod)
                        .externalCallable(true).build())
                .collect(toList());

        var repositoryEntityInterfaces = getRepositoryEntityInterfaces(componentName, componentType);
        var outJmsInterfaces = getOutJmsInterfaces(component, componentName, dependencies,
                callCache, evalContextFactory, resolver);
        var outWsInterfaces = getOutWsInterfaces(component, componentName, dependencies,
                callCache, evalContextFactory, resolver);

        var outRestOperationsHttpInterface = getOutRestTemplateInterfaces(component, componentName,
                dependencies, callCache, evalContextFactory, resolver);

        return of(
                inHttpInterfaces.stream(), inJmsInterface.stream(),
                outRestOperationsHttpInterface.stream(), outWsInterfaces.stream(),
                outJmsInterfaces.stream(), repositoryEntityInterfaces.stream())
                .flatMap(s -> s)
                .collect(toLinkedHashSet());
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
        if (repositoryClass != null && repositoryClass.isAssignableFrom(componentType)) {
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
                        var metamodel = getFieldValue(entityInformation, "metamodel");
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
                                        .core(StorageEntity.builder()
                                                .entityType(type)
                                                .storedTo(stream(tables).map(Object::toString).collect(toList()))
                                                .engine(jpa)
                                                .build())
                                        .build());
                            } else {
                                log.warn("null entityPersister for entityClass {}", entityClassName);
                            }
                        } else if (metamodel != null) {
                            log.warn("unsupported jpa metamodel type {}", metamodel.getClass());
                        } else {
                            log.warn("null jpa metamodel type");
                        }
                    } else if (entityInformation instanceof MongoEntityInformation) {
                        var mongoInfo = (MongoEntityInformation<?, ?>) entityInformation;
                        var collectionName = mongoInfo.getCollectionName();
                        repositoryEntities.add(Interface.builder()
                                .name(entityClassName)
                                .type(storage)
                                .direction(internal)
                                .core(StorageEntity.builder()
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
            log.trace("getComponentType", e);
            componentType = null;
        }
        return unproxy(componentType);
    }

    protected Set<Interface> getOutJmsInterfaces(Component component, String componentName,
                                                 Collection<Component> dependencies,
                                                 Map<CallCacheKey, Result> callCache,
                                                 EvalContextFactory evalContextFactory, Resolver resolver) {
        var jmsTemplate = findDependencyByType(dependencies, () -> JmsOperations.class);
        if (jmsTemplate != null) try {
            var jmsClients = extractJmsClients(component, callCache, evalContextFactory, resolver);
            return jmsClients.stream().map(jmsClient -> newInterface(jmsClient, false)).collect(toLinkedHashSet());
        } catch (EvalBytecodeException e) {
            handleError("jms client getting error, component", componentName, e, options.isFailFast());
        }
        return Set.of();
    }

    protected Set<Interface> getOutWsInterfaces(Component component, String componentName,
                                                Collection<Component> dependencies, Map<CallCacheKey, Result> callCache,
                                                EvalContextFactory evalContextFactory, Resolver resolver) {
        var wsClient = findDependencyByType(dependencies, () -> WebSocketClient.class);
        if (wsClient != null) try {
            var wsClientUris = extractWebsocketClientUris(component, callCache, evalContextFactory, resolver);
            return wsClientUris.stream()
                    .map(uri -> Interface.builder()
                            .direction(out).type(ws).name(uri)
                            .id(getWebsocketInterfaceId(out, uri))
                            .externalCallable(false)
                            .build())
                    .collect(toLinkedHashSet());

        } catch (EvalBytecodeException e) {
            handleError("jws client getting error, component", componentName, e, options.isFailFast());
        }
        return Set.of();
    }

    protected Set<Interface> getOutRestTemplateInterfaces(
            Component component, String componentName, Collection<Component> dependencies,
            Map<CallCacheKey, Result> callCache, EvalContextFactory evalContextFactory, Resolver resolver
    ) {
        var restTemplate = findDependencyByType(dependencies, () -> RestOperations.class);
        if (restTemplate != null) try {
            var httpMethods = extractRestOperationsUris(component,
                    callCache, evalContextFactory, resolver);
            return httpMethods.stream()
                    .map(httpMethod -> Interface.builder()
                            .direction(out).type(http)
                            .core(httpMethod)
                            .evalSource(httpMethod.getEvalSource())
                            .methodSource(httpMethod.getMethodSource())
                            .build())
                    .collect(toLinkedHashSet());
        } catch (EvalBytecodeException e) {
            handleError("rest operations client getting error, component", componentName, e, options.isFailFast());
        }
        return Set.of();
    }

    protected Set<Component> getDependencies(String componentName, Package rootPackage, Map<String, Set<Component>> cache) {
        return getFilteredBeanNameWithType(stream(context.getBeanFactory().getDependenciesForBean(componentName)))
                .flatMap(e -> getComponents(e.getKey(), e.getValue(), rootPackage, cache)
                        .filter(Objects::nonNull).filter(component -> isIncluded(component.getType())))
                .collect(toLinkedHashSet());
    }

    protected Collection<Component> extractInWebsocketHandlers(String componentName, Class<?> componentType,
                                                               Package rootPackage, Map<String, Set<Component>> cache) {
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
                webSocketHandler = ((WebSocketHandlerDecorator) webSocketHandler).getLastHandler();
            }
            var webSocketHandlerName = findBeanName(webSocketHandler, WebSocketHandler.class);
            var managed = webSocketHandlerName != null;
            var cached = managed ? cache.get(webSocketHandlerName) : null;
            if (cached != null) {
                cached = cached.stream().map(component -> {
                    var interfaces = Optional.ofNullable(component.getInterfaces()).orElse(Set.of());
                    if (!interfaces.contains(anInterface)) {
                        log.trace("update cached component by interface, component {}, interface {}",
                                component.getName(), anInterface);
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
                                ? Component.builder().object(webSocketHandler).name(webSocketHandlerName)
                                : Component.builder().object(webSocketHandler)
                )
                        .type(webSocketHandlerClass)
                        .configuration(isSpringConfiguration(webSocketHandlerClass))
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
            log.trace("ignore unmanaged component type {}", componentType);
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
            }).map(field -> {
                log.trace("read field {} of object {}", field.getName(), unmanagedInstance);
                return getFieldValue(unmanagedInstance, field, options.isFailFast());
            }).filter(Objects::nonNull).forEach(value -> {
                var alreadyTouched = touched.get(value);
                if (alreadyTouched != null) {
                    dependencies.addAll(alreadyTouched);
                } else {
                    var managedDependencyName = findBeanName(value);
                    if (managedDependencyName != null && isUnmanaged) {
                        dependencies.add(newManagedDependency(managedDependencyName, value));
                    } else if (value instanceof Collection<?>) {
                        var collection = (Collection<?>) value;
                        var aggregated = collection.stream().map(o -> {
                            var oName = findBeanName(value);
                            //todo check o is Collection
                            return oName != null && isUnmanaged
                                    ? newManagedDependency(oName, o)
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
                .object(value)
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
                log.trace("findBeanName", e);
                return false;
            }
        }).findFirst().orElse(null);
    }

    public interface ComponentProvider extends Function<ComponentKey, Component> {

    }

    @Data
    @Builder(toBuilder = true)
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class Options {
        public static final Options DEFAULT = Options.builder().build();
        public boolean includeUnusedOutInterfaces;
        BeanFilter exclude;
        boolean failFast;
        @Builder.Default
        boolean ignoreNotFoundDependencies = true;
        boolean cropRootPackagePath;
        @Builder.Default
        StringifyResolver.Level stringifyLevel = varOnly;

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
        MethodId methodSource;
        Result evalSource;

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
