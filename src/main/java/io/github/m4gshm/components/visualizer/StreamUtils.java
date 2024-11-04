package io.github.m4gshm.components.visualizer;

import lombok.NonNull;

import java.util.Optional;
import java.util.stream.Stream;

public class StreamUtils {
    public static <T> Stream<T> ofNullable(T value) {
        return value != null ? Stream.of(value) : Stream.empty();
    }

    public static <T> Stream<T> stream(@NonNull Optional<T> optional) {
        return optional.map(Stream::of).orElse(Stream.empty());
    }
}
