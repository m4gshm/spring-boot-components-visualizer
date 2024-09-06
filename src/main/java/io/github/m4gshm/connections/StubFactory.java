package io.github.m4gshm.connections;

public interface StubFactory {
    StubFactory DEFAULT = new StubFactoryImpl();
    Object create(Class<?> type);
}
