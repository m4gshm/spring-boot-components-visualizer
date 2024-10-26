package io.github.m4gshm.connections.model;

import io.github.m4gshm.connections.eval.result.Result;
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
    CharSequence name;
    String id;
    Direction direction;
    Type type;
    MethodId methodSource;
    Result evalSource;
    boolean externalCallable;

    public CharSequence getName() {
        var name = this.name != null ? this.name : core != null ? core instanceof CharSequence ? (CharSequence) core : core.toString() : null;
        if (name == null) {
            name = "!!NULL!!";
        } else if (name.toString().isBlank()) {
            name = "!!BLANK!!";
        }
        return name;
    }

    public String getId() {
        return id != null ? id : core != null ? core.toString() : String.valueOf(getName());
    }

    @Getter
    @RequiredArgsConstructor
    public enum Direction {
        undefined, internal, in, out, outIn(out);
        private final Direction core;

        Direction() {
            this.core = this;
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum Type {
        http, ws("web socket"), grpc, jms, kafka, storage;

        public final String fullName;

        Type() {
            fullName = this.name();
        }
    }

}
