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

    public static Package getComponentPackage(Component component) {
        var componentPath = component.getPath();

        var reversePathBuilders = reverse(asList(componentPath.split("\\."))).stream()
                .map(packageName -> Package.builder().name(packageName))
                .collect(toList());

        reversePathBuilders.stream().findFirst().ifPresent(packageBuilder -> packageBuilder.components(singletonList(component)));

        return reversePathBuilders.stream().reduce((l, r) -> {
            var lPack = l.build();
            r.packages(singletonList(lPack));
            return r;
        }).map(Package.PackageBuilder::build).orElse(
                Package.builder().name(componentPath).components(singletonList(component)).build()
        );
    }

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
