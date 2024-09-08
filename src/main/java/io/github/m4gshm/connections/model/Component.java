package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Set;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(level = PRIVATE, makeFinal = true)
@ToString(onlyExplicitlyIncluded = true)
public class Component implements ComponentDependency {
    @EqualsAndHashCode.Include
    String name;
    @EqualsAndHashCode.Include
    Object unmanagedInstance;
    @ToString.Include(rank = -1)
    String path;
    Class<?> type;
    boolean configuration;
    Set<Interface> interfaces;
    Set<Component> dependencies;
    List<CallPoint> callPoints;

    @Override
    public boolean isManaged() {
        return unmanagedInstance == null;
    }

    @ToString.Include(name = "name")
    public String getName() {
        return isManaged() ? name : /*todo must be unique, make incremental int sequence*/unmanagedInstance.getClass().getSimpleName();
    }

    public Class<?> getType() {
        return isManaged() ? type : unmanagedInstance.getClass();
    }
}
