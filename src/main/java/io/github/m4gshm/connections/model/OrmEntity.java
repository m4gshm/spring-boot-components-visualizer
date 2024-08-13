package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.Collection;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class OrmEntity {
    Class<?> entityType;
    Collection<String> storedTo;
    Engine engine;

    public enum Engine {
        jpa, mongodb;
    }
}
