package io.github.m4gshm.connections;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

import static java.util.Optional.ofNullable;

@Data
@Builder
public class Components {
    private final Map<String, List<String>> beanDependencies;
    private final Map<String, HttpInterface> httpInterfaces;
    private final Map<String, HttpClient> httpClients;
    private final Map<String, JmsListener> jmsListeners;

    @Data
    @Builder
    public static class HttpClient {
        private String name;
        private String url;
        private Type type;

        public enum Type {
            Feign,
            RestTemplateBased
        }
    }

    @Data
    @Builder
    public static class HttpInterface {
        private String name;
        private String[] paths;
        private Type type;

        public static String getHttpInterfaceName(String beanName, Components.HttpInterface httpInterface) {
            return ofNullable(httpInterface.getName()).filter(s -> !s.isEmpty()).orElse(beanName);
        }

        public enum Type {
            Controller
        }
    }

    @Data
    @Builder
    public static class JmsListener {
        private String name;
        private String destination;
        private Type type;

        public enum Type {
            Feign,
            JmsListenerMethod
        }
    }
}
