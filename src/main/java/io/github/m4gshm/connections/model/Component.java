package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldDefaults;

import java.util.Set;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Component {
    @EqualsAndHashCode.Include
    private final String name;
    private final String path;
    private final Class<?> type;
    private final Set<Interface> interfaces;
    private final Set<String> dependencies;

}
