package io.github.m4gshm.components.visualizer.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class IntrfaceKey {
    Object core;
    CharSequence name;
    String id;
    Direction direction;
    InterfaceType type;
    boolean externalCallable;
}
