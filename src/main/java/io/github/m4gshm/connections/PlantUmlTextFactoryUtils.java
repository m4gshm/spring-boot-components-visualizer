package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod.Group;
import io.github.m4gshm.connections.model.Interface;
import io.github.m4gshm.connections.model.Package;
import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.List;

import static com.google.common.collect.Lists.reverse;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

@UtilityClass
public class PlantUmlTextFactoryUtils {

    public static Group newEmptyGroup(String part, String path) {
        return Group.builder()
                .part(part)
                .path(path)
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
