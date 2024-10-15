package io.github.m4gshm.connections.model;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ToString(onlyExplicitlyIncluded = true)
public class Component implements ComponentDependency {
    String name;
    @NonNull
    Object object;
    @ToString.Include(rank = -1)
    String path;
    Class<?> type;
    boolean configuration;
    Set<Interface> interfaces;
    Set<Component> dependencies;

    @Override
    public boolean isManaged() {
        return name != null;
    }

    @ToString.Include(name = "name")
    public String getName() {
        return isManaged() ? name : /*todo must be unique, make incremental int sequence*/object.getClass().getSimpleName();
    }

    public Class<?> getType() {
        return type != null ? type : object.getClass();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Component component = (Component) o;
        return Objects.equals(name, component.name) && Objects.equals(object, component.object);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, object);
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class ComponentKey {
        String name;
        Object unmanagedInstance;

        public static ComponentKey newComponentKey(Component componentDependency) {
            return new ComponentKey(componentDependency.getName(), componentDependency.getObject());
        }

    }
}
