package io.github.m4gshm.connections;

import org.springframework.core.env.Environment;

public interface Visualizer<T> {
    static String getApplicationName(Environment environment) {
        return environment.getProperty("spring.application.name", "application");
    }

    T visualize(Components components);

}
