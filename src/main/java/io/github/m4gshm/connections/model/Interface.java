package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Map;
import java.util.Set;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Interface {
    Object core;
    String name;
    Direction direction;
    Type type;

    public String getName() {
        return name != null ? name : core != null ? core.toString() : null;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Direction {
        undefined, in, out, outIn, storage;
    }

    @RequiredArgsConstructor
    public enum Type {
        http, ws("web socket"), grpc, jms, kafka, jpa;

        public final String code;

        Type() {
            code = this.name();
        }
    }

    @Data
    @Builder(toBuilder = true)
    public static class Group {
        private String path;
        private Set<Interface> interfaces;
        private Map<String, Group> groups;
    }

}
