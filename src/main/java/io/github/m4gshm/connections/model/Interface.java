package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Interface {
    String name;
    Direction direction;
    Type type;

    public enum Direction {
        in, out;
    }

    public enum Type {
        http, ws, grpc, jms, kafka;
    }



}
