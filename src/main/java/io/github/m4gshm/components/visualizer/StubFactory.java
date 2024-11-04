package io.github.m4gshm.components.visualizer;

public interface StubFactory {
    StubFactory DEFAULT = new StubFactoryImpl();

    Object create(Class<?> type);
}
