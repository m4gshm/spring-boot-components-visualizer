package io.github.m4gshm.components.visualizer;

import lombok.NonNull;

import java.util.Map;

public class MapUtils {
    public static <K, V> Map.Entry<K, V> entry(@NonNull K k, @NonNull V v) {
        return new Map.Entry<K, V>() {
            @Override
            public K getKey() {
                return k;
            }

            @Override
            public V getValue() {
                return v;
            }

            @Override
            public V setValue(V value) {
                throw new UnsupportedOperationException("Map.Entry.setValue");
            }
        };
    }
}
