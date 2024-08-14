package io.github.m4gshm.connections;

public interface SchemaFactory<T> {

    T create(Components components);

}
