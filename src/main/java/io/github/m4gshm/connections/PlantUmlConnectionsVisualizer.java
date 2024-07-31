package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.Interface;
import io.github.m4gshm.connections.model.Interface.Type;
import io.github.m4gshm.connections.model.Package;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.reverse;
import static com.google.common.collect.Streams.concat;
import static io.github.m4gshm.connections.PlantUmlConnectionsVisualizer.PackageOutType.cloud;
import static io.github.m4gshm.connections.PlantUmlConnectionsVisualizer.PackageOutType.queue;
import static io.github.m4gshm.connections.PlantUmlConnectionsVisualizer.PackageOutType.rectangle;
import static io.github.m4gshm.connections.model.Interface.Direction.in;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Map.entry;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@RequiredArgsConstructor
public class PlantUmlConnectionsVisualizer implements ConnectionsVisualizer<String> {

    public static final String INDENT = "  ";
    private final String applicationName;
    private final boolean simple;

    public static String pumlAlias(String name) {
        var onRemove = List.of("*", "$", "{", "}").stream().map(v -> "\\" + v).reduce((l, r) -> l + "|" + r).orElse("");
        var onEscape = List.of("-", "/", ":").stream().reduce((l, r) -> l + "|" + r).orElse("");
        String s = name.replaceAll(onRemove, "").replaceAll(onEscape, ".");
        return s;
    }

    private static String getInterfaceId(Component component, Interface anInterface) {
        return getElementId(component.getName(), getElementId(anInterface.getDirection().name(),
                anInterface.getType().name(), anInterface.getName()));
    }

    private static String getComponentId(Component component) {
        return pumlAlias(component.getName());
    }

    private static void printPackage(StringBuilder out, int indent, Package pack) {
        printPackage(out, indent, pack, PackageOutType.pack);
    }

    private static void printPackage(StringBuilder out, int indent, Package pack, PackageOutType packageType) {
        var packageName = pack.getName();
        var wrapByPack = !packageName.isEmpty();

        Runnable printInternal = () -> {
            var beans = pack.getComponents();
            if (beans != null) {
                beans.forEach(bean -> printComponent(out, INDENT.repeat(indent + 1), bean));
            }
            var packages = pack.getPackages();
            if (packages != null) {
                packages.forEach(subPack -> printPackage(out, indent + (wrapByPack ? 1 : 0), subPack));
            }
        };

        if (wrapByPack) {
            printPackage(out, indent, packageName, pack.getPath(), packageType, printInternal);
        } else {
            printInternal.run();
        }
    }


    private static void printPackage(StringBuilder out, int indent, String name, String id,
                                     PackageOutType packageType, Runnable internal) {
        printPackage(true, out, indent, name, id, packageType, internal);
    }

    private static void printPackage(boolean wrap, StringBuilder out, int indent, String name, String id,
                                     PackageOutType packageType, Runnable internal) {
        if (wrap) {
            out.append(format(INDENT.repeat(indent) + "%s \"%s\" as %s {\n", packageType.code, name, id));
        }
        internal.run();
        if (wrap) {
            out.append(INDENT.repeat(indent)).append("}\n");
        }
    }

    private static String getElementId(String... parts) {
        return pumlAlias(Stream.of(parts).reduce("", (parent, id) -> (!parent.isEmpty() ? parent + "." : "") + id));
    }

    private static LinkedHashMap<String, Package> distinctPackages(String parentPath, Stream<Package> packageStream) {
        return packageStream
                .map(p -> populatePath(parentPath, p))
                .collect(toMap(Package::getName, p -> p, (l, r) -> {
                    var lName = l.getName();
                    var rName = r.getName();
                    var validPackages = lName != null && lName.equals(rName) || rName == null;
                    if (!validPackages) {
                        throw new IllegalArgumentException("cannot merge packages with different names '" + lName + "', '" + rName + "'");
                    }

                    var components = Stream.of(l.getComponents(), r.getComponents())
                            .filter(Objects::nonNull).flatMap(Collection::stream).collect(toList());

                    var distinctPackages = distinctPackages(getElementId(parentPath, l.getName()), concat(
                            ofNullable(l.getPackages()).orElse(emptyList()).stream(),
                            ofNullable(r.getPackages()).orElse(emptyList()).stream())
                    );
                    var pack = l.toBuilder().components(components).packages(copyOf(distinctPackages.values())).build();
                    return pack;
                }, LinkedHashMap::new));
    }

    private static Package populatePath(String parentPath, Package pack) {
        var elementId = getElementId(parentPath, pack.getName());
        return pack.toBuilder().path(elementId)
                .packages(ofNullable(pack.getPackages()).orElse(emptyList()).stream().map(p -> populatePath(elementId, p)).collect(toList()))
                .build();
    }

    private static void printComponent(StringBuilder out, String prefix, Component component) {
        var componentName = component.getName();
        printUniqueComponent(out, prefix, componentName, componentName);
    }

    private static void printUniqueComponent(StringBuilder out, String prefix, String name, String id) {
        out.append(prefix + format("[%s] as %s\n", name, id));
    }

    @Override
    public String visualize(Components components) {
        var out = new StringBuilder();

        out.append("@startuml\n");

//        out.append(format("component \"%s\" as %s\n", applicationName, pumlAlias(applicationName)));

        visualize(components.getComponents(), out);

        out.append("@enduml\n");
        return out.toString();
    }

    private void visualize(Collection<Component> components, StringBuilder out) {
        var depth = 0;

        var packages = distinctPackages(INDENT.repeat(depth), components.stream().map(bean -> {
            var beanPath = bean.getPath();

            var reversePathBuilders = reverse(asList(beanPath.split("\\."))).stream()
                    .map(packageName -> Package.builder().name(packageName))
                    .collect(toList());

            reversePathBuilders.stream().findFirst().ifPresent(packageBuilder -> packageBuilder.components(singletonList(bean)));

            var aPackage = reversePathBuilders.stream().reduce((l, r) -> {
                var lPack = l.build();
                r.packages(singletonList(lPack));
                return r;
            }).map(Package.PackageBuilder::build).orElse(
                    Package.builder().name(beanPath).components(singletonList(bean)).build()
            );
            return aPackage;

        })).values();

        for (var pack : packages) {
            printPackage(out, depth, pack);
        }

        components.forEach(component -> {
            component.getDependencies().stream().map(Component::getName).forEach(dependency -> {
                out.append(format("%s ..> %s\n", getComponentId(component), pumlAlias(dependency)));
            });
        });

        var groupedInterfaces = components.stream()
                .flatMap(component -> Stream.ofNullable(component.getInterfaces())
                        .flatMap(Collection::stream).map(anInterface -> entry(anInterface, component)))
                .collect(
                        groupingBy(entry -> entry.getKey().getDirection(),
                                groupingBy(entry -> entry.getKey().getType(),
                                        groupingBy(entry -> ofNullable(entry.getKey().getGroup()).orElse(""))))
                );

        groupedInterfaces.forEach((direction, byType) -> {
            var directionName = direction.name();
            printPackage(out, depth, directionName, directionName, rectangle, () -> {
                byType.forEach((type, byGroup) -> {
                    var typeName = type.name();
                    printPackage(out, depth + 1, typeName, getElementId(directionName, typeName), cloud, () -> {
                        byGroup.forEach((group, interfaceComponentLink) -> {
                            var wrap = group != null && !group.isEmpty();
                            var depthDelta = wrap ? 1 : 0;
                            var packageType = type == Type.jms ? queue : cloud;
                            printPackage(wrap, out, depth + 1 + depthDelta, group, getElementId(directionName, group), packageType, () ->
                                    interfaceComponentLink.forEach(entry -> {
                                        var anInterface = entry.getKey();
                                        var component = entry.getValue();
                                        var interfaceId = getInterfaceId(component, anInterface);
                                        out.append(INDENT.repeat(depth + 2 + depthDelta));
                                        out.append(format("interface \"%s\" as %s\n", anInterface.getName(), interfaceId));
                                        out.append(INDENT.repeat(depth + 2 + depthDelta));
                                        var componentId = getComponentId(component);
                                        if (direction == in) {
                                            out.append(format("%s )..> %s\n", interfaceId, componentId));
                                        } else {
                                            out.append(format("%s ..( %s\n", componentId, interfaceId));
                                        }
                                    }));
                        });
                    });
                });
            });
        });
    }

    @RequiredArgsConstructor
    public enum PackageOutType {
        rectangle("rectangle"),
        pack("package"),
        cloud("cloud"),
        queue("queue"),
        database("database"),
        ;

        private final String code;
    }
}
