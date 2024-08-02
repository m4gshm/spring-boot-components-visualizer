package io.github.m4gshm.connections;

import java.util.LinkedHashSet;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class Utils {
    static <T> Collector<T, ?, LinkedHashSet<T>> toLinkedHashSet() {
        return Collectors.toCollection(LinkedHashSet::new);
    }
}
