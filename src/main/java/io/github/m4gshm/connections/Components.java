package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Component;
import lombok.Builder;
import lombok.Data;

import java.util.Collection;

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
    @Builder(toBuilder = true)
    public static class HttpMethod {
        private String url;
        private String method;
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
