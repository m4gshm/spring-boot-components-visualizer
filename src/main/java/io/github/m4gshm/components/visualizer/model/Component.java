package io.github.m4gshm.components.visualizer.model;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.unproxy;
import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ToString(onlyExplicitlyIncluded = true)
public class Component implements ComponentDependency {
    String name;
    @NonNull
    Object bean;
    @ToString.Include(rank = -1)
    String path;
    Class<?> type;
    boolean configuration;
    List<Interface> interfaces;
    Set<Component> dependencies;

    @Override
    public boolean isManaged() {
        return name != null;
    }

    @ToString.Include(name = "name")
    public String getName() {
        return isManaged() ? name : /*todo must be unique, make incremental int sequence*/bean.getClass().getSimpleName();
    }

    public Class<?> getType() {
        return type != null ? type : unproxy(bean.getClass());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Component component = (Component) o;
        return Objects.equals(name, component.name) && Objects.equals(bean, component.bean);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, bean);
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class ComponentKey {
        String name;
        Object unmanagedInstance;

        public static ComponentKey newComponentKey(Component component) {
            return new ComponentKey(component.getName(), component.getBean());
        }
    }
}
