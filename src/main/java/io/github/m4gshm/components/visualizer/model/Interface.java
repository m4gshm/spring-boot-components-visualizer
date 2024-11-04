package io.github.m4gshm.components.visualizer.model;

import io.github.m4gshm.components.visualizer.eval.result.Result;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;


@Data
@Builder(toBuilder = true)
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Interface {
    Object core;
    CharSequence name;
    String id;
    Direction direction;
    Type type;
    Call call;
    MethodId methodSource;
    Result evalSource;

    public CharSequence getName() {
        var name = this.name != null ? this.name : core != null
                ? core instanceof CharSequence ? (CharSequence) core : core.toString() : null;
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

    public Key toKey() {
        return Key.builder().id(getId()).direction(getDirection()).type(getType()).build();
    }

    public enum Call {
        external, scheduled;
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
        http, ws("web socket"), grpc, jms, kafka, storage, scheduler;

        public final String fullName;

        Type() {
            fullName = this.name();
        }
    }

    public static class InterfaceBuilder {

    }

    @Data
    @Builder
    @FieldDefaults(level = PRIVATE, makeFinal = true)
    public static class Key {
        Object core;
        CharSequence name;
        String id;
        Direction direction;
        Type type;
        boolean externalCallable;
    }

}
