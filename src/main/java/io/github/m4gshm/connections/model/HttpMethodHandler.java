package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder(toBuilder = true)
public class HttpMethodHandler {
    private Set<String> urls;
    private Set<String> methods;

    @Override
    public String toString() {
        return methods.stream().reduce("", (l, r) -> (l.isEmpty() ? "" : l + ",") + r) + ':' + urls;
    }
}
