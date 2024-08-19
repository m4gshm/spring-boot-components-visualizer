package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Interface {
    Object core;
    String name;
    String id;
    Direction direction;
    Type type;

    public String getName() {
        return name != null ? name : core != null ? core.toString() : null;
    }

    public String getId() {
        return id != null ? id : core != null ? core.toString() : getName();
    }

    @Getter
    @RequiredArgsConstructor
    public enum Direction {
        undefined, internal, in, out, outIn;
    }

    @RequiredArgsConstructor
    public enum Type {
        http, ws("web socket"), grpc, jms, kafka, storage;

        public final String code;

        Type() {
            code = this.name();
        }
    }

}
