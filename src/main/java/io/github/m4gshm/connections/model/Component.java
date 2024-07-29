package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Component {
    @EqualsAndHashCode.Include
    private final String name;
    private final String path;
    private final Class<?> type;
    private final List<Interface> interfaces;
    private final List<Component> dependencies;
}
