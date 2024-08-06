package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

@Data
@Builder(toBuilder = true)
public class HttpMethod {
    private String url;
    private String method;

    @Data
    @Builder(toBuilder = true)
    public static class Group {
        private String path;
        private Set<HttpMethod> methods;
        private Map<String, Group> groups;

    }

    @Override
    public String toString() {
        return method + ':' + url;
    }
}
