package io.github.m4gshm.connections.model;

import io.github.m4gshm.connections.UriUtils;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.m4gshm.connections.PlantUmlTextFactoryUtils.newEmptyGroup;
import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class HttpMethodsGroup {
    String name;
    Map<HttpMethod, HttpMethod> methods;
    Map<String, HttpMethodsGroup> groups;

    public static void makeGroupsHierarchyByHttpMethodUrl(HttpMethodsGroup rootGroup, HttpMethod httpMethod) {
        var url = httpMethod.getPath();

        var parts = UriUtils.splitURI(url);

        Map<String, HttpMethodsGroup> prevGroupsLevel = null;
        var nexGroupsLevel = rootGroup.getGroups();
        var currentGroup = rootGroup;

        for (var part : parts) {
            currentGroup = nexGroupsLevel.computeIfAbsent(part, k -> newEmptyGroup(part));
            prevGroupsLevel = nexGroupsLevel;
            nexGroupsLevel = currentGroup.getGroups();
        }

        var oldMethods = currentGroup.getMethods();
        var methods = new LinkedHashMap<>(oldMethods != null ? oldMethods : Map.of());
        var groupedMethod = httpMethod.toBuilder().path("").build();
        methods.put(groupedMethod, httpMethod);
        if (prevGroupsLevel != null) {
            //replace
            var currentGroupWitMethods = currentGroup.toBuilder().methods(methods).build();
            prevGroupsLevel.put(currentGroup.getName(), currentGroupWitMethods);
        }
    }
}
