package io.github.m4gshm.components.visualizer;
import lombok.var;

public interface StubFactory {
    StubFactory DEFAULT = new StubFactoryImpl();
    Object create(Class<?> type);
}
