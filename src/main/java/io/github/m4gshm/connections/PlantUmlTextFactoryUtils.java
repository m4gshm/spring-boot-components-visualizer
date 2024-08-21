package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.HttpMethodsGroup;
import io.github.m4gshm.connections.model.Interface;
import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.List;

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
}
