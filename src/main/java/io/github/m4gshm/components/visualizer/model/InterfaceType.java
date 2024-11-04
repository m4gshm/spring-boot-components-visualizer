package io.github.m4gshm.components.visualizer.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InterfaceType {
    http, ws("web socket"), grpc, jms, kafka, storage, scheduler;

    public final String fullName;

    InterfaceType() {
        fullName = this.name();
    }
}
