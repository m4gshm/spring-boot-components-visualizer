package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Component;
import lombok.Builder;
import lombok.Data;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static java.util.Optional.ofNullable;

@Data
@Builder
public class Components {
    private final Collection<Component> components;

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
        private Collection<String> paths;
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
