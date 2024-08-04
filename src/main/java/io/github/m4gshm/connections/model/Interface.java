package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Set;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Interface {
    String name;
    String group;
    Set<Direction> directions;
    Type type;

    @Getter
    @RequiredArgsConstructor
    public enum Direction {
        in, out;
    }

    @RequiredArgsConstructor
    public enum Type {
        http, ws("web socket"), grpc, jms, kafka;

        public final String code;

        Type() {
            code = this.name();
        }
    }

}
