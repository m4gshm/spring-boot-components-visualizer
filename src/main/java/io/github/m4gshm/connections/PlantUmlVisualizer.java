package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.HttpMethod;
import io.github.m4gshm.connections.model.HttpMethod.Group;
import io.github.m4gshm.connections.model.Interface;
import io.github.m4gshm.connections.model.Interface.Direction;
import io.github.m4gshm.connections.model.Interface.Type;
import io.github.m4gshm.connections.model.Package;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.reverse;
import static com.google.common.collect.Streams.concat;
import static io.github.m4gshm.connections.PlantUmlVisualizer.PackageOutType.*;
import static io.github.m4gshm.connections.model.Interface.Type.http;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Map.entry;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.*;

@Slf4j
@RequiredArgsConstructor
public class PlantUmlVisualizer implements Visualizer<String> {

    public static final String INDENT = "  ";
    public static final String SCHEME_DELIMETER = "://";
    public static final String PATH_DELIMITER = "/";
    private final String applicationName;

    public static String plantUmlAlias(String name) {
        var onRemove = regExp(List.of("*", "$", "{", "}", " ", "(", ")", "#"));
        var onReplace = regExp(List.of("-", PATH_DELIMITER, ":", "?"));
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

    private static void printPackage(IndentStringAppender out, Package pack) {
        printPackage(out, pack, PackageOutType.pack);
    }

    private static void printPackage(IndentStringAppender out, Package pack, PackageOutType packageType) {
        printPackage(out, pack.getName(), pack.getPath(), packageType, () -> {
            var beans = pack.getComponents();
            if (beans != null) {
                beans.forEach(bean -> printComponent(out, bean));
            }
            var packages = pack.getPackages();
            if (packages != null) {
                packages.forEach(subPack -> printPackage(out, subPack));
            }
        });
    }

    private static void printPackage(IndentStringAppender out, String name, String id,
                                     PackageOutType packageType, Runnable internal) {
        var wrap = name != null;
        if (wrap) {
            String text = format("%s", packageType.code);
            out.append(text);
            if (!name.isBlank()) {
                out.append(format(" \"%s\"", name));
                if (id != null) {
                    out.append(format(" as %s", id));
                }
            }
            out.append(" {\n");
            out.addIndent();
        }
        internal.run();
        if (wrap) {
            out.removeIndent();
            out.append("}\n");
        }
    }

    private static String getElementId(String... parts) {
        return plantUmlAlias(Stream.of(parts).filter(Objects::nonNull).reduce("", (parent, id) -> (!parent.isEmpty() ? parent + "." : "") + id));
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

    private static void printComponent(IndentStringAppender out, Component component) {
        var componentName = component.getName();
        out.append(format("[%s] as %s\n", componentName, plantUmlAlias(componentName)));
    }

    private static Stream<Package> mergeSubPack(Package pack) {
        var packComponents = pack.getComponents();
        var subPackages = pack.getPackages();
        return packComponents.isEmpty() && subPackages.size() == 1
                ? subPackages.stream().map(subPack -> subPack.toBuilder().name(getElementId(pack.getName(), subPack.getName())).build()).flatMap(PlantUmlVisualizer::mergeSubPack)
                : Stream.of(pack);
    }

    private static PackageOutType getPackageOutType(Type type) {
        return type == Type.jms ? queue : cloud;
    }

    private static String notNull(String host) {
        return ofNullable(host).orElse("");
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
            var path = Optional.ofNullable(group.getPath()).orElse("");
            var newPath = path.endsWith(PATH_DELIMITER) || nextGroupPath.startsWith(PATH_DELIMITER)
                    || nextGroupPath.contains(SCHEME_DELIMETER)
                    ? path + nextGroupPath
                    : path + PATH_DELIMITER + nextGroupPath;
            group.setPath(newPath);
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
                    log.debug("merge http method groups {} and {}", l, r);
                    return l;
                }, LinkedHashMap::new));
    }

    private static void printInterface(IndentStringAppender out, Interface anInterface,
                                       Collection<Component> components, Set<String> renderedInterfaces) {
        var interfaceId = getInterfaceId(anInterface);
        if (renderedInterfaces.add(interfaceId)) {
            out.append(format("interface \"%s\" as %s\n", anInterface.getName(), interfaceId));
        }
        for (var component : components) {
            var componentId = plantUmlAlias(component.getName());
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
    }

    private static Group getLastGroup(Group group, HttpMethod httpMethod) {
        var url = httpMethod.getUrl();
        url = url.startsWith(PATH_DELIMITER) ? url.substring(1) : url;


        final String scheme, path;
        int schemeEnd = url.indexOf(SCHEME_DELIMETER);
        if (schemeEnd >= 0) {
            scheme = url.substring(0, schemeEnd);
            path = url.substring(schemeEnd + SCHEME_DELIMETER.length());
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
                    part = scheme + SCHEME_DELIMETER + part;
                } else {
                    part = PATH_DELIMITER + part;
                }
                parts.add(part);
                first = false;
            }
        }

        var nexGroupsLevel = group.getGroups();
        var currentGroup = group;
        for (var part : parts) {
            currentGroup = nexGroupsLevel.computeIfAbsent(part, k -> newEmptyGroup(part));
            nexGroupsLevel = currentGroup.getGroups();
        }
        return currentGroup;
    }

    private static void printHttpMethodGroup(IndentStringAppender out, Group group,
                                             PackageOutType packageType,
                                             Map<Interface, List<Component>> interfaceComponentLink,
                                             Map<HttpMethod, Interface> httpMethods,
                                             Set<String> renderedInterfaces) {
        var methods = group.getMethods();
        var subGroups = group.getGroups();
        if (subGroups.isEmpty() && methods != null && methods.size() == 1) {
            printInterfaceAndSubgroups(out, group, group.getPath(), packageType, interfaceComponentLink, httpMethods, renderedInterfaces);
        } else {
            printPackage(out, group.getPath(), null, packageType,
                    () -> printInterfaceAndSubgroups(out, group, PATH_DELIMITER, packageType,
                            interfaceComponentLink, httpMethods, renderedInterfaces)
            );
        }
    }

    private static void printInterfaceAndSubgroups(IndentStringAppender out,
                                                   Group group, String replaceMethodUrl, PackageOutType packageType,
                                                   Map<Interface, List<Component>> interfaceComponentLink,
                                                   Map<HttpMethod, Interface> httpMethods,
                                                   Set<String> renderedInterfaces) {
        var groupMethods = group.getMethods();
        if (groupMethods != null) for (var method : groupMethods) {
            var anInterface = httpMethods.get(method);
            var groupedInterface = anInterface.toBuilder().core(HttpMethod.builder().method(method.getMethod()).url(replaceMethodUrl).build()).build();
            printInterface(out, groupedInterface, interfaceComponentLink.get(anInterface), renderedInterfaces);
        }
        for (var subGroup : group.getGroups().values()) {
            printHttpMethodGroup(out, subGroup, packageType, interfaceComponentLink, httpMethods, renderedInterfaces);
        }
    }

    private static Map<HttpMethod, Interface> extractHttpMethodsFromInterfaces(Collection<Interface> interfaces) {
        return interfaces.stream().map(anInterface -> {
            var core = anInterface.getCore();
            if (core instanceof HttpMethod) {
                return entry((HttpMethod) core, anInterface);
            } else {
                //log
                return null;
            }
        }).filter(Objects::nonNull).collect(toMap(Entry::getKey, Entry::getValue));
    }

    private static Group groupByUrlParts(Map<HttpMethod, Interface> httpMethods) {
        var rootGroup = newEmptyGroup(null);
        //create groups
        for (var httpMethod : httpMethods.keySet()) {
            var currentGroup = getLastGroup(rootGroup, httpMethod);
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
        return finalGroup;
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

    private void visualize(Collection<Component> components, StringBuilder dest) {
        var out = new IndentStringAppender(dest, INDENT);

        var packages = distinctPackages(null, components.stream().map(component -> {
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

        packages = packages.stream().flatMap(PlantUmlVisualizer::mergeSubPack).collect(toList());

        for (var pack : packages) {
            printPackage(out, pack);
        }

        components.forEach(component -> {
            component.getDependencies().stream().map(Component::getName).forEach(dependency -> {
                out.append(format("%s ..> %s\n", plantUmlAlias(component.getName()), plantUmlAlias(dependency)));
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
                        mapping(Entry::getValue, groupingBy(entry -> entry.getKey().getType(),
                                groupingBy(entry -> ofNullable(entry.getKey().getGroup()).orElse(""),
                                        groupingBy(e -> e.getKey(), LinkedHashMap::new, mapping(e -> e.getValue(), toList()))))
                        )));

        var renderedInterfaces = new HashSet<String>();
        var directionGroups = stream(Direction.values()).map(PlantUmlVisualizer::directionGroup).distinct().collect(toList());
        for (var directionGroup : directionGroups) {
            var byType = groupedInterfaces.getOrDefault(directionGroup, Map.of());
            if (!byType.isEmpty()) {
                printPackage(out, directionGroup, directionGroup, rectangle, () -> {
                    for (var type : Type.values()) {
                        var byGroup = ofNullable(byType.get(type)).orElse(Map.of());
                        if (!byGroup.isEmpty()) {
                            var typeName = type.code;
                            printPackage(out, typeName, getElementId(directionGroup, typeName), cloud, () -> {
                                byGroup.forEach((group, interfaceComponentLink) -> {
                                    var wrap = group != null && !group.isEmpty();
                                    var depthDelta = wrap ? 1 : 0;
                                    var packageType = getPackageOutType(type);
                                    var groupId = getElementId(directionGroup, group);

                                    var packageName = group != null && group.isBlank() ? null : group;
                                    if (type == http) {
                                        //merge by url parts
                                        var httpMethods = extractHttpMethodsFromInterfaces(
                                                interfaceComponentLink.keySet());
                                        var finalGroup = groupByUrlParts(httpMethods);
                                        printPackage(out, packageName,
                                                groupId, packageType, () -> {
                                                    printHttpMethodGroup(out, finalGroup, packageType,
                                                            interfaceComponentLink, httpMethods, renderedInterfaces);
                                                });
                                    } else {
                                        printPackage(out, packageName, groupId, packageType, () -> {
                                            interfaceComponentLink.forEach((anInterface, component) -> printInterface(
                                                    out, anInterface, component, renderedInterfaces)
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
