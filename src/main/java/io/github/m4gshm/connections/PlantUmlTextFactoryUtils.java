package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
import io.github.m4gshm.connections.model.Interface;
import io.github.m4gshm.connections.model.Package;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.StringTokenizer;

import static com.google.common.collect.Lists.reverse;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

@UtilityClass
public class PlantUmlTextFactoryUtils {
    public static final String SCHEME_DELIMITER = "://";
    public static final String PATH_DELIMITER = "/";

    public static List<String> splitUrl(String url) {
        final String scheme, path;
        int schemeEnd = url.indexOf(SCHEME_DELIMITER);
        if (schemeEnd >= 0) {
            scheme = url.substring(0, schemeEnd);
            path = url.substring(schemeEnd + SCHEME_DELIMITER.length());
        } else {
            scheme = null;
            path = url;
        }

        var parts = new ArrayList<String>();

        if (!path.isBlank()) {
            var first = true;
            var tokenizer = new StringTokenizer(path, PATH_DELIMITER, false);
            while (tokenizer.hasMoreTokens()) {
                var part = tokenizer.nextToken();
                if (first && scheme != null) {
                    part = scheme + SCHEME_DELIMITER + part;
                } else {
                    part = PATH_DELIMITER + part;
                }
                parts.add(part);
                first = false;
            }
        }
        return parts;
    }

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

    public static HttpMethod.Group newEmptyGroup(String part) {
        return HttpMethod.Group.builder()
                .path(part)
                .groups(new LinkedHashMap<>())
                .build();
    }

    public static String renderAs(Interface.Type type) {
        return type == Interface.Type.storage ? "entity" : "interface";
    }

    public static HttpMethod.Group getLastGroup(HttpMethod.Group group, HttpMethod httpMethod) {
        var url = httpMethod.getUrl();
        url = url.startsWith(PATH_DELIMITER) ? url.substring(1) : url;

        var parts = splitUrl(url);

        var nexGroupsLevel = group.getGroups();
        var currentGroup = group;
        for (var part : parts) {
            currentGroup = nexGroupsLevel.computeIfAbsent(part, k -> newEmptyGroup(part));
            nexGroupsLevel = currentGroup.getGroups();
        }
        return currentGroup;
    }

    protected static String regExp(List<String> strings) {
        return strings.stream().map(v -> "\\" + v).reduce((l, r) -> l + "|" + r).orElse("");
    }
}
