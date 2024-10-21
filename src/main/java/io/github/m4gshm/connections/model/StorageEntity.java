package io.github.m4gshm.connections.model;

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


