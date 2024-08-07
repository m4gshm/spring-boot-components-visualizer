package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
import io.github.m4gshm.connections.model.HttpMethod.Group;
import io.github.m4gshm.connections.model.Interface;
import io.github.m4gshm.connections.model.Interface.Direction;
import io.github.m4gshm.connections.model.Interface.Type;
import io.github.m4gshm.connections.model.Package;
import lombok.RequiredArgsConstructor;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.net.URI;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.reverse;
import static com.google.common.collect.Streams.concat;
import static io.github.m4gshm.connections.PlantUmlConnectionsVisualizer.PackageOutType.*;
import static io.github.m4gshm.connections.model.Interface.Type.http;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Map.entry;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.*;

@RequiredArgsConstructor
public class PlantUmlConnectionsVisualizer implements ConnectionsVisualizer<String> {

    public static final String INDENT = "  ";
    private final String applicationName;

    public static String pumlAlias(String name) {
        var onRemove = regExp(List.of("*", "$", "{", "}", " ", "(", ")", "#"));
        var onReplace = regExp(List.of("-", "/", ":", "?"));
        String s = name.replaceAll(onRemove, "").replaceAll(onReplace, ".");
        return s;
    }

    public static String directionGroup(Direction direction) {
        switch (direction) {
            case in:
                return "input";
            case out:
            case outIn:
                return "output";
            default:
                return String.valueOf(direction);
        }
    }

    private static String regExp(List<String> strings) {
        return strings.stream().map(v -> "\\" + v).reduce((l, r) -> l + "|" + r).orElse("");
    }

    private static String getInterfaceId(Interface anInterface) {
        var direction = getElementId(anInterface.getDirection().name());
        var elementId = getElementId(direction, anInterface.getType().name(), anInterface.getName());
        return getElementId(anInterface.getGroup(), elementId);
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
            if (id != null) {
                out.append(format(INDENT.repeat(indent) + "%s \"%s\" as %s {\n", packageType.code, name, id));
            } else {
                out.append(format(INDENT.repeat(indent) + "%s \"%s\" {\n", packageType.code, name));
            }
        }
        internal.run();
        if (wrap) {
            out.append(INDENT.repeat(indent)).append("}\n");
        }
    }

    private static String getElementId(String... parts) {
        return pumlAlias(Stream.of(parts).filter(Objects::nonNull).reduce("", (parent, id) -> (!parent.isEmpty() ? parent + "." : "") + id));
    }

    private static Map<String, Package> distinctPackages(String parentPath, Stream<Package> packageStream) {
        return packageStream.map(p -> populatePath(parentPath, p)).collect(toMap(Package::getName, p -> p, (l, r) -> {
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
        out.append(prefix + format("[%s] as %s\n", componentName, pumlAlias(componentName)));
    }

    private static Stream<Package> mergeSubPack(Package pack) {
        var packComponents = pack.getComponents();
        var subPackages = pack.getPackages();
        return packComponents.isEmpty() && subPackages.size() == 1
                ? subPackages.stream().map(subPack -> subPack.toBuilder().name(getElementId(pack.getName(), subPack.getName())).build()).flatMap(PlantUmlConnectionsVisualizer::mergeSubPack)
                : Stream.of(pack);
    }

    private static PackageOutType getPackageOutType(Type type) {
        return type == Type.jms ? queue : cloud;
    }

    private static URI toUri(String uriTemplate) {
        return new DefaultUriBuilderFactory().uriString(uriTemplate).build();
    }

    private static String notNull(String host) {
        return Optional.ofNullable(host).orElse("");
    }

    private static Group newEmptyGroup(String part) {
        return Group.builder()
                .path(part).groups(new LinkedHashMap<>())
                .build();
    }

    private static Group reduce(Group group) {
        var nextGroups = group.getGroups();
        while (nextGroups.size() == 1 && group.getMethods() == null) {
            var nextGroupPath = nextGroups.keySet().iterator().next();
            var nextGroup = nextGroups.get(nextGroupPath);
            var path = group.getPath();
            group.setPath(path.isBlank() ? nextGroupPath : path + "/" + nextGroupPath);
            group.setMethods(nextGroup.getMethods());
            group.setGroups(nextGroups = nextGroup.getGroups());
        }

        group.setGroups(reduceNext(group.getGroups()));

        return group;
    }

    private static LinkedHashMap<String, Group> reduceNext(Map<String, Group> groups) {
        return groups.entrySet().stream()
                .map(e -> entry(e.getKey(), reduce(e.getValue())))
                .collect(toMap(Entry::getKey, Entry::getValue, (l, r) -> {
                    return l;
                }, LinkedHashMap::new));
    }

    private static void printInterface(StringBuilder out, int depth, Interface anInterface, Component component, Set<String> renderedInterfaces) {
        var interfaceId = getInterfaceId(anInterface);
        if (renderedInterfaces.add(interfaceId)) {
            out.append(INDENT.repeat(depth));
            out.append(format("interface \"%s\" as %s\n", anInterface.getName(), interfaceId));
        }
        var componentId = pumlAlias(component.getName());
        out.append(INDENT.repeat(depth));
        var direction = anInterface.getDirection();
        switch (direction) {
            case in:
                out.append(format("%s )..> %s\n", interfaceId, componentId));
                break;
            case out:
                out.append(format("%s ..( %s\n", componentId, interfaceId));
                break;
            case outIn:
                out.append(format("%s ).. %s\n", interfaceId, componentId));
                out.append(format("%s <.. %s\n", componentId, interfaceId));
                break;
        }
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

        var packages = distinctPackages(INDENT.repeat(depth), components.stream().map(component -> {
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
        })).values();

        packages = packages.stream().flatMap(PlantUmlConnectionsVisualizer::mergeSubPack).collect(toList());

        for (var pack : packages) {
            printPackage(out, depth, pack);
        }

        components.forEach(component -> {
            component.getDependencies().stream().map(Component::getName).forEach(dependency -> {
                out.append(format("%s ..> %s\n", pumlAlias(component.getName()), pumlAlias(dependency)));
            });
        });

        Stream<Entry<Direction, Entry<Interface, Component>>> entryStream = components.stream()
                .flatMap(component -> Stream.ofNullable(component.getInterfaces())
                        .flatMap(Collection::stream)
                        .map(anInterface -> {
                            Entry<Direction, Entry<Interface, Component>> entry1 = entry(anInterface.getDirection(), entry(anInterface, component));
                            return entry1;
                        })
                );
        var groupedInterfaces = entryStream
                .collect(groupingBy(directionEntryEntry -> directionGroup(directionEntryEntry.getKey()),
                        mapping(directionEntryEntry1 -> {
                            Entry<Interface, Component> value = directionEntryEntry1.getValue();
                            return value;
                        }, groupingBy(entry -> {
                                    var key1 = entry.getKey();
                                    return key1.getType();
                                },
                                groupingBy(entry -> ofNullable(entry.getKey().getGroup()).orElse(""), toMap(e -> e.getKey(), e -> e.getValue()))))
                ));

        var renderedInterfaces = new HashSet<String>();
        var directionGroups = stream(Direction.values()).map(PlantUmlConnectionsVisualizer::directionGroup).distinct().collect(toList());
        for (var directionGroup : directionGroups) {
            var byType = groupedInterfaces.getOrDefault(directionGroup, Map.of());
            if (!byType.isEmpty()) {
                printPackage(out, depth, directionGroup, directionGroup, rectangle, () -> {
                    for (var type : Type.values()) {
                        Map<String, Map<Interface, Component>> byGroup = Optional.ofNullable(byType.get(type)).orElse(Map.of());
                        if (!byGroup.isEmpty()) {
                            var typeName = type.code;
                            printPackage(out, depth + 1, typeName, getElementId(directionGroup, typeName), cloud, () -> {
                                byGroup.forEach((group, interfaceComponentLink) -> {
                                    var wrap = group != null && !group.isEmpty();
                                    var depthDelta = wrap ? 1 : 0;
                                    var packageType = getPackageOutType(type);
                                    var groupId = getElementId(directionGroup, group);

                                    if (type == http) {
                                        //merge by url parts
                                        var httpMethods = interfaceComponentLink.keySet().stream().map(anInterface -> {
                                            var core = anInterface.getCore();
                                            if (core instanceof HttpMethod) {
                                                return Map.entry((HttpMethod) core, anInterface);
                                            } else {
                                                //log
                                                return null;
                                            }
                                        }).filter(Objects::nonNull).collect(toMap(e -> e.getKey(), e -> e.getValue()));

                                        var rootGroup = newEmptyGroup("");
                                        //create groups
                                        for (var httpMethod : httpMethods.keySet()) {
                                            var url = httpMethod.getUrl();
                                            url = url.startsWith("/") ? url.substring(1) : url;
                                            var parts = url.isBlank() ? new String[0] : url.split("/");

                                            var nexGroupsLevel = rootGroup.getGroups();
                                            var currentGroup = rootGroup;
                                            for (var part : parts) {
                                                currentGroup = nexGroupsLevel.computeIfAbsent(part, k -> newEmptyGroup(part));
                                                nexGroupsLevel = currentGroup.getGroups();
                                            }
                                            var oldMethods = currentGroup.getMethods();
                                            Set<HttpMethod> methods;
                                            if (oldMethods != null) {
                                                methods = new LinkedHashSet<>(oldMethods);
                                                methods.add(httpMethod);
                                            } else {
                                                methods = new LinkedHashSet<>();
                                                methods.add(httpMethod);
                                            }
                                            currentGroup.setMethods(methods);
                                        }
                                        //reduce groups
                                        var finalGroup = reduce(rootGroup);
                                        printPackage(wrap, out, depth + depthDelta + 1, group, groupId, packageType, () -> {
                                            printHttpMethodGroup(out, depth + depthDelta + 2, finalGroup, packageType,
                                                    interfaceComponentLink, httpMethods, renderedInterfaces);

//                                            interfaceComponentLink.forEach(entry -> printInterface(out, depth + depthDelta + 2, entry.getKey(), entry.getValue(), renderedInterfaces));
                                        });
                                    } else {
                                        printPackage(wrap, out, depth + 1 + depthDelta, group, groupId, packageType, () -> {
                                            interfaceComponentLink.forEach((anInterface, component) -> printInterface(
                                                    out, depth + depthDelta + 2, anInterface, component, renderedInterfaces)
                                            );
                                        });
                                    }
                                });
                            });
                        }
                    }
                });
            }
        }
    }

    private static void printHttpMethodGroup(StringBuilder out, int depth, Group group,
                                             PackageOutType packageType,
                                             Map<Interface, Component> interfaceComponentLink,
                                             Map<HttpMethod, Interface> httpMethods,
                                             Set<String> renderedInterfaces) {
        var path = group.getPath();
        printPackage(true, out, depth, path, null, packageType, () -> {
            var methods = group.getMethods();
            if (methods != null) {
                for (var method : methods) {
                    var anInterface = httpMethods.get(method);
                    printInterface(out, depth + 1, anInterface, interfaceComponentLink.get(anInterface), renderedInterfaces);
                }
                for (var subGroup : group.getGroups().values()) {
                    printHttpMethodGroup(out, depth + 2, subGroup, packageType, interfaceComponentLink, httpMethods, renderedInterfaces);
                }
            }
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
