package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Interface {
    String name;
    String group;
    Direction direction;
    Type type;

    public enum Direction {
        in, out;
    }

    @RequiredArgsConstructor
    public enum Type {
        http, ws("web socket"), grpc, jms, kafka;
        Type() {
            code = this.name();
        }
        public final String code;
    }

}
