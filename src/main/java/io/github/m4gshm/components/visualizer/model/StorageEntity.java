package io.github.m4gshm.components.visualizer.model;
import lombok.var;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.Collection;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class StorageEntity {
    Class<?> entityType;
    Collection<String> storedTo;
    Engine engine;

    @Override
    public String toString() {
        return engine + ":" + entityType + ":" + storedTo;
    }

    public enum Engine {
        jpa, mongo;
    }
}


