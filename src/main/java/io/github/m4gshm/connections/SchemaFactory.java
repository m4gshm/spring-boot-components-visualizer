package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Components;

public interface SchemaFactory<T> {

    T create(Components components);

}
