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
public class Component implements ComponentDependency {
    @EqualsAndHashCode.Include
    String name;
    @EqualsAndHashCode.Include
    Object unmanagedInstance;
    String path;
    Class<?> type;
    Set<Interface> interfaces;
    Set<Component> dependencies;

    @Override
    public boolean isManaged() {
        return unmanagedInstance == null;
    }

    public String getName() {
        return isManaged() ? name : unmanagedInstance.getClass().getSimpleName();
    }
}
