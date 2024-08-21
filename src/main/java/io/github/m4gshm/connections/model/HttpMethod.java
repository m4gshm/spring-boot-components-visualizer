package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class HttpMethod {
    String path;
    String method;

    @Override
    public String toString() {
        return method + ':' + path;
    }
}
