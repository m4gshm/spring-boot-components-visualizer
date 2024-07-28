package io.github.m4gshm.connections;

import feign.Target;
import io.github.m4gshm.connections.Components.HttpInterface;
import io.github.m4gshm.connections.model.Component;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.annotation.JmsListeners;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.Components.HttpClient.Type.Feign;
import static io.github.m4gshm.connections.Components.HttpClient.Type.RestTemplateBased;
import static io.github.m4gshm.connections.Components.HttpInterface.Type.Controller;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static java.lang.reflect.Proxy.isProxyClass;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;

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
                    Stream.ofNullable(m.getAnnotation(JmsListener.class)),
                    Stream.ofNullable(m.getAnnotation(JmsListeners.class))
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

    private static HttpInterface extractHttpInterface(String beanName, Class<?> beanType) {
        try {
            var restController = beanType.getAnnotation(RestController.class);
            if (restController != null) {
                var requestMapping = ofNullable(beanType.getAnnotation(RequestMapping.class));
                var httpInterface = HttpInterface.builder()
                        .type(Controller)
                        .name(restController.value())
                        .paths(requestMapping.map(r -> of(r.path()).orElse(r.value())).orElse(new String[0]))
                        .build();
                return httpInterface;
            }
        } catch (NoClassDefFoundError e) {
            log.debug("extractHttpInterface bean {}", beanName, e);
        }
        return null;
    }

    private static boolean hasMainMethod(Class<?> beanType) {
        return Stream.of(beanType.getMethods()).anyMatch(method -> method.getName().equals("main")
                && isOnlyOneArgStringArray(method)
                && method.getReturnType().equals(void.class)
                && isStatic(method.getModifiers()) && isPublic(method.getModifiers()));
    }

    private static boolean isOnlyOneArgStringArray(Method method) {
        var parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 1 && String[].class.equals(parameterTypes[0]);
    }

    public Components getComponents() {
        var beanDependencies = new LinkedHashMap<Component, List<String>>();
        var httpInterfaces = new LinkedHashMap<String, HttpInterface>();
        var httpClients = new LinkedHashMap<String, Components.HttpClient>();
        var jmsListeners = new HashMap<String, Components.JmsListener>();
        var beanFactory = context.getBeanFactory();
        var allBeans = asList(context.getBeanDefinitionNames());

        var rootBean = allBeans.stream().filter(this::isSpringBootMainClass)
                .map(beanName -> newBean(beanName, context.getType(beanName), null))
                .findFirst().orElse(null);
        var rootPackage = rootBean != null ? rootBean.getType().getPackage() : null;

        var beanStream = allBeans.stream()
                .map(beanName -> newBean(beanName, context.getType(beanName), rootPackage))
                .filter(bean -> isIncluded(bean.getType()));

        //todo config
        beanStream = filterByRootPackage(rootBean, beanStream);

        var beans = beanStream.collect(toList());

        for (var bean : beans) {
            var beanName = bean.getName();
            try {
                var beanType = bean.getType();
                var httpInterface = extractHttpInterface(beanName, beanType);
                if (httpInterface != null) {
                    httpInterfaces.put(beanName, httpInterface);
                }

                var dependenciesForBean = List.of(beanFactory.getDependenciesForBean(beanName));
                beanDependencies.put(bean, dependenciesForBean.stream()
                        .filter(dependencyBeanName -> isIncluded(context.getType(dependencyBeanName)))
                        .collect(toList())
                );

                if (!dependenciesForBean.isEmpty()) {
                    boolean useRestTemplate;
                    try {
                        useRestTemplate = dependenciesForBean.stream().map(context::getType)
                                .filter(Objects::nonNull).anyMatch(RestTemplate.class::isAssignableFrom);
                    } catch (NoSuchBeanDefinitionException e) {
                        log.trace("useRestTemplate, bean {}", beanName, e);
                        useRestTemplate = false;
                    }
                    if (useRestTemplate) {
                        httpClients.put(beanName, Components.HttpClient.builder()
                                .name(beanName)
                                .type(RestTemplateBased)
                                .build());
                        log.debug("rest template dependent bean {}, type {}", beanName, beanType);
                    }
                }

                var feignClient = extractFeignClient(beanName);
                if (feignClient != null) {
                    httpClients.put(beanName, Components.HttpClient.builder()
                            .name(feignClient.getName())
                            .url(feignClient.getUrl())
                            .type(Feign)
                            .build());
                }

                var beanJmsListeners = getMethodJmsListeners(beanType);
                if (!beanJmsListeners.isEmpty()) {
                    log.debug("jms method listeners, class {}, amount {}", beanType, jmsListeners.size());
                    for (var beanJmsListener : beanJmsListeners) {
                        jmsListeners.put(beanName + "." + beanJmsListener.getName(), beanJmsListener);
                    }
                }
            } catch (NoClassDefFoundError e) {
                log.debug("bad bean {}", beanName, e);
            }
        }
        return Components.builder()
                .beanDependencies(beanDependencies)
                .httpClients(httpClients)
                .httpInterfaces(httpInterfaces)
                .jmsListeners(jmsListeners)
                .build();

    }

    private static boolean isIncluded(Class<?> type) {
        return !(isSpringBootTest(type) || isSpringConfiguration(type) || isStorage(type));
    }

    private static boolean isSpringBootTest(Class<?> beanType) {
        return hasAnnotation(beanType, () -> SpringBootTest.class);
    }

    private static boolean isSpringConfiguration(Class<?> beanType) {
        return hasAnnotation(beanType, () -> Configuration.class);
    }

    private static boolean isStorage(Class<?> beanType) {
        return OnApplicationReadyEventConnectionsVisualizeGenerator.Storage.class.isAssignableFrom(beanType);
    }

    private static <T extends Annotation> boolean hasAnnotation(Class<?> beanType, Supplier<Class<T>> supplier) {
        try {
            var annotationClass = supplier.get();
            boolean match;
            while (!(match = beanType.getAnnotation(annotationClass) != null)) {
                beanType = beanType.getSuperclass();
                if (beanType == null || Object.class.equals(beanType)) {
                    break;
                }
            }
            return match;
        } catch (Error e) {
            log.error("hasAnnotation error", e);
            return false;
        }
    }


    private static Component newBean(String beanName, Class<?> beanType, Package rootPackage) {
        return Component.newBean(beanName, getBeanPath(rootPackage, beanType), beanType);
    }

    private static String getBeanPath(Package rootPackage, Class<?> beanType) {
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

    private Stream<Component> filterByRootPackage(Component rootComponent, Stream<Component> beanStream) {
        var rootPackage = rootComponent != null ? rootComponent.getType().getPackage() : null;
        if (rootPackage != null) {
            beanStream = beanStream.filter(bean -> bean.getType().getPackage().getName().startsWith(rootPackage.getName()));
        }
        return beanStream;
    }

    private boolean isSpringBootMainClass(String beanName) {
        var beanType = context.getType(beanName);
        var hasMainMethod = hasMainMethod(beanType);
        var isSpringBootApp = beanType.getAnnotation(SpringBootApplication.class) != null;
        return hasMainMethod && isSpringBootApp;
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
