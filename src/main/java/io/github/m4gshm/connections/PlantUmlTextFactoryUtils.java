package io.github.m4gshm.connections;

import io.github.m4gshm.connections.PlantUmlTextFactory.RowsCols;
import io.github.m4gshm.connections.model.HttpMethodsGroup;
import io.github.m4gshm.connections.model.Interface;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static java.lang.Math.min;

@UtilityClass
public class PlantUmlTextFactoryUtils {

    public static HttpMethodsGroup newEmptyGroup(String name) {
        return HttpMethodsGroup.builder()
                .name(name)
                .groups(new LinkedHashMap<>())
                .build();
    }

    public static String renderAs(Interface.Type type) {
        return type == Interface.Type.storage ? "entity" : "interface";
    }

    public static String regExp(List<String> strings) {
        return strings.stream().map(v -> "\\" + v).reduce((l, r) -> l + "|" + r).orElse("");
    }

    public static <T> List<List<T>> splitOnTableParts(int columns, int rows, List<T> elements) {
        var size = elements.size();
        var maxElements = columns * rows;
        if (size <= maxElements) {
            return List.of(elements);
        }
        var result = new ArrayList<List<T>>();
        for (int first = 0; ; ) {
            var tail = size - first;
            var last = first + min(maxElements, tail);
            List<T> elementsPart = elements.subList(first, last);

            result.add(elementsPart);
            if (last >= size) {
                break;
            }
            first = last;
        }
        return result;
    }

    static RowsCols getRowsCols(int rows, int columns, int elementsAmount) {
        return new RowsCols(
                rows < 1 ? columns < 1 ? 1 : elementsAmount / columns : rows,
                columns < 1 ? (elementsAmount / rows) + 1 : columns
        );
    }
}
