package io.github.m4gshm.components.visualizer.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Direction {
    undefined, internal, in, out, outIn(out);
    private final Direction core;

    Direction() {
        this.core = this;
    }
}
