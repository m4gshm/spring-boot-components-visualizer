package io.github.m4gshm.components.visualizer;

import io.github.m4gshm.components.visualizer.model.Components;

public interface SchemaFactory<T> {

    T create(Components components);

}
