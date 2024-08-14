package io.github.m4gshm.connections;

import io.github.m4gshm.connections.PlantUmlVisualizer.Options.UnionStyle;
import io.github.m4gshm.connections.model.*;
import io.github.m4gshm.connections.model.HttpMethod.Group;
import io.github.m4gshm.connections.model.Interface.Direction;
import io.github.m4gshm.connections.model.Package;
import io.github.m4gshm.connections.model.Interface.Type;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.reverse;
import static io.github.m4gshm.connections.PlantUmlVisualizer.UnionBorder.*;
import static io.github.m4gshm.connections.model.Interface.Type.*;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Map.entry;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.concat;
import static lombok.AccessLevel.PRIVATE;

@Slf4j
public class PlantUmlVisualizer implements Visualizer<String> {

    public static final String INDENT = "  ";
    public static final String SCHEME_DELIMETER = "://";
    public static final String PATH_DELIMITER = "/";
    public static final Map<String, List<String>> DEFAULT_ESCAPES = Map.of(
            "", List.of("*", "$", "{", "}", " ", "(", ")", "[", "]", "#", "\"", "'"),
            ".", List.of("-", PATH_DELIMITER, ":", "?", "=", ",")
    );
    public static final String DIRECTION_INPUT = "input";
    public static final String DIRECTION_OUTPUT = "output";
    public static final Options DEFAULT_OPTIONS = Options.builder()
            .idCharReplaces(DEFAULT_ESCAPES)
            .directionGroup(PlantUmlVisualizer.Options::defaultDirectionGroup)
            .directionGroupAggregate(PlantUmlVisualizer.Options::getAggregateOfDirectionGroup)
            .interfaceAggregate(PlantUmlVisualizer.Options::getAggregateStyle)
            .interfaceSubgroupAggregate(PlantUmlVisualizer.Options::getAggregateOfSubgroup)
            .packagePathAggregate(PlantUmlVisualizer.Options::getPackagePath)
            .build();
    private final String applicationName;
    private final Options options;
    @Getter
    private final Map<String, String> collapsedComponents = new HashMap<>();
    private final Map<String, Set<String>> printedComponentRelations = new HashMap<>();

    public PlantUmlVisualizer(String applicationName) {
        this(applicationName, null);
    }

    public PlantUmlVisualizer(String applicationName, Options options) {
        this.applicationName = applicationName;
        this.options = options != null ? options : DEFAULT_OPTIONS;
    }

    private static List<String> splitUrl(String url) {
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
        return parts;
    }

    protected String renderAs(Interface.Type type) {
        return type == Type.storage ? "entity" : "interface";
    }

    protected void printUnion(IndentStringAppender out, UnionBorder border, Runnable internal) {
        printUnion(out, null, null, UnionStyle.builder().unionBorder(border).build(), internal);
    }

    protected void printUnion(IndentStringAppender out, String name, String id,
                              UnionStyle unionStyle, Runnable internal) {
        var unionBorder = unionStyle.getUnionBorder();
        var supportNameIdStyle = unionBorder.isSupportNameIdStyle();
        var wrap = !supportNameIdStyle || name != null;
        if (wrap) {
            var text = format("%s", unionBorder);
            out.append(text);
            if (supportNameIdStyle) {
                if (!name.isBlank()) {
                    out.append(format(" \"%s\"", name));
                    if (id != null) {
                        out.append(format(" as %s", id));
                    }
                }
                var style = unionStyle.getStyle();
                if (style != null) {
                    if (!style.startsWith("#")) {
                        style = "#" + style;
                    }
                    out.append(" ").append(style);
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

    protected Group newEmptyGroup(String part) {
        return Group.builder()
                .path(part).groups(new LinkedHashMap<>())
                .build();
    }

    protected Group reduce(Group group) {
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

    protected Map<String, Group> reduceNext(Map<String, Group> groups) {
        return groups.entrySet().stream()
                .map(e -> entry(e.getKey(), reduce(e.getValue())))
                .collect(toMap(Entry::getKey, Entry::getValue, (l, r) -> {
                    log.debug("merge http method groups {} and {}", l, r);
                    return l;
                }, LinkedHashMap::new));
    }

    protected Group getLastGroup(Group group, HttpMethod httpMethod) {
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

    protected Map<HttpMethod, Interface> extractHttpMethodsFromInterfaces(Collection<Interface> interfaces) {
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

    protected Group groupByUrlParts(Map<HttpMethod, Interface> httpMethods) {
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

    protected String regExp(List<String> strings) {
        return strings.stream().map(v -> "\\" + v).reduce((l, r) -> l + "|" + r).orElse("");
    }

    private String getInterfaceId(Interface anInterface) {
        var direction = getElementId(anInterface.getDirection().name());
        return getElementId(direction, anInterface.getType().name(), anInterface.getName());
    }

    protected void printUnion(IndentStringAppender out, Package pack) {
        printUnion(out, pack, options.getPackagePathAggregate().apply(pack.getPath()));
    }

    protected void printUnion(IndentStringAppender out, Package pack, UnionStyle style) {
        var packageId = pack.getPath();
        printUnion(out, pack.getName(), packageId, style, () -> {
            var components = pack.getComponents();
            if (components != null) {
                if (options.collapseComponentsMoreThen != null && components.size() > options.collapseComponentsMoreThen) {
                    printCollapsedComponents(out, packageId, components);
                } else {
                    for (var component : components) {
                        printComponent(out, component);
                    }
                }
            }
            var packages = pack.getPackages();
            if (packages != null) for (var subPack : packages) {
                printUnion(out, subPack);
            }
        });
    }

    private Map<String, Package> distinctPackages(String parentPath, Stream<Package> packageStream) {
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

    protected void printHttpMethodGroup(IndentStringAppender out, Group group,
                                        UnionStyle style,
                                        Map<Interface, List<Component>> interfaceComponentLink,
                                        Map<HttpMethod, Interface> httpMethods,
                                        Set<String> renderedInterfaces) {
        var methods = group.getMethods();
        var subGroups = group.getGroups();
        if (subGroups.isEmpty() && methods != null && methods.size() == 1) {
            printInterfaceAndSubgroups(out, group, group.getPath(), style, interfaceComponentLink, httpMethods, renderedInterfaces);
        } else {
            printUnion(out, group.getPath(), null, style,
                    () -> printInterfaceAndSubgroups(out, group, PATH_DELIMITER, style,
                            interfaceComponentLink, httpMethods, renderedInterfaces)
            );
        }
    }

    protected void printInterfaceAndSubgroups(IndentStringAppender out,
                                              Group group, String replaceMethodUrl, UnionStyle style,
                                              Map<Interface, List<Component>> interfaceComponentLink,
                                              Map<HttpMethod, Interface> httpMethods,
                                              Set<String> renderedInterfaces) {
        var groupMethods = group.getMethods();
        if (groupMethods != null) for (var method : groupMethods) {
            var anInterface = httpMethods.get(method);
            var groupedInterface = anInterface.toBuilder()
                    .core(HttpMethod.builder()
                            .method(method.getMethod()).url(replaceMethodUrl)
                            .build())
                    .build();
            printInterface(out, groupedInterface, interfaceComponentLink.get(anInterface), renderedInterfaces);
        }
        for (var subGroup : group.getGroups().values()) {
            printHttpMethodGroup(out, subGroup, style, interfaceComponentLink, httpMethods, renderedInterfaces);
        }
    }

    protected void printInterface(IndentStringAppender out, Interface anInterface,
                                  Collection<Component> components, Set<String> renderedInterfaces) {
        var interfaceId = getInterfaceId(anInterface);
        if (renderedInterfaces.add(interfaceId)) {
            out.append(format(renderAs(anInterface.getType()) + " \"%s\" as %s\n", renderInterfaceName(anInterface), interfaceId));
        }

        printInterfaceCore(out, anInterface.getCore(), interfaceId);

        for (var component : components) {
            printDirection(out, interfaceId, anInterface, component);
        }
    }

    protected String renderInterfaceName(Interface anInterface) {
        var name = anInterface.getName();
        if (anInterface.getType() == storage) {
            var lasted = name.lastIndexOf(".");
            return lasted > 0 ? name.substring(lasted + 1) : null;
        }
        return name;
    }

    protected void printInterfaceCore(IndentStringAppender out, Object core, String interfaceId) {
        if (core instanceof Storage) {
            var ormEntity = (Storage) core;
            var storedTo = ormEntity.getStoredTo();
            var tables = storedTo.stream().reduce("", (l, r) -> (l.isBlank() ? "" : l + "\n") + r);
            out.append(format("note right of %s: %s\n", interfaceId, tables));
        }
    }

    protected void printDirection(IndentStringAppender out, String interfaceId,
                                  Interface anInterface, Component component) {
        var type = anInterface.getType();
        var componentId = plantUmlAlias(component.getName());
        var direction = anInterface.getDirection();
        switch (direction) {
            case in:
                out.append(renderIn(type, interfaceId, componentId));
                break;
            case out:
                out.append(renderOut(type, interfaceId, componentId));
                break;
            case outIn:
                out.append(renderOutIn(type, interfaceId, componentId));
                break;
            default:
                out.append(renderLink(type, interfaceId, componentId));
        }
    }

    protected String renderOut(Type type, String interfaceId, String componentId) {
        return format((type == jms ? "%s ..> %s" : "%s ..( %s") + "\n", componentId, interfaceId);
    }

    protected String renderOutIn(Type type, String interfaceId, String componentId) {
        return format("%1$s ..> %2$s\n%1$s <.. %2$s\n", componentId, interfaceId);
    }

    protected String renderIn(Type type, String interfaceId, String componentId) {
        return format("%s )..> %s\n", interfaceId, componentId);
    }

    protected String renderLink(Type type, String interfaceId, String componentId) {
        return format("%s .. %s\n", interfaceId, componentId);
    }

    protected Package populatePath(String parentPath, Package pack) {
        var elementId = getElementId(parentPath, pack.getName());
        return pack.toBuilder().path(elementId)
                .packages(ofNullable(pack.getPackages()).orElse(emptyList()).stream().map(p -> populatePath(elementId, p)).collect(toList()))
                .build();
    }

    protected void printComponent(IndentStringAppender out, Component component) {
        var componentName = component.getName();
        out.append(format("component %s as %s\n", componentName, plantUmlAlias(componentName)));
    }

    protected void printCollapsedComponents(IndentStringAppender out, String packageId, Collection<Component> components) {
        var text = components.stream().map(Component::getName).reduce("", (l, r) -> (l.isBlank() ? "" : l + "\\n\\\n") + r);
        var collapsedComponentsId = getElementId(packageId, "components");
        out.append(format("collections \"%s\" as %s\n", text, collapsedComponentsId), false);
        for (var component : components) {
            collapsedComponents.put(component.getName(), collapsedComponentsId);
        }
    }

    protected Stream<Package> mergeSubPack(Package pack) {
        var packComponents = pack.getComponents();
        var subPackages = pack.getPackages();
        return (packComponents == null || packComponents.isEmpty()) && subPackages.size() == 1
                ? subPackages.stream().map(subPack -> subPack.toBuilder()
                        .name(getElementId(pack.getName(), subPack.getName()))
                        .build())
                .flatMap(this::mergeSubPack)
                : Stream.of(pack);
    }

    protected String getElementId(String... parts) {
        return plantUmlAlias(Stream.of(parts).filter(Objects::nonNull)
                .reduce("", (parent, id) -> (!parent.isEmpty() ? parent + "." : "") + id));
    }

    protected String plantUmlAlias(String name) {
        var escaped = name;
        var replaces = this.options.getIdCharReplaces();
        for (var replacer : replaces.keySet()) {
            escaped = escaped.replaceAll(regExp(replaces.get(replacer)), replacer);
        }
        return escaped;
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

    protected void visualize(Collection<Component> components, StringBuilder dest) {
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

        var mergerPackages = packages.stream().flatMap(this::mergeSubPack).collect(toList());

        printUnion(out, together, () -> {
            for (var pack : mergerPackages) {
                printUnion(out, pack);
            }
        });

        for (var component : components) {
            printComponentDependencyRelations(out, component);
        }

        var groupedInterfaces = components.stream()
                .flatMap(component -> Stream.ofNullable(component.getInterfaces())
                        .flatMap(Collection::stream)
                        .map(anInterface -> entry(anInterface.getDirection(), entry(anInterface, component)))
                )
                .collect(groupingBy(directionEntryEntry -> options.directionGroup.apply(directionEntryEntry.getKey()),
                        mapping(Entry::getValue, groupingBy(entry -> entry.getKey().getType(),
                                groupingBy(Entry::getKey, LinkedHashMap::new, mapping(Entry::getValue, toList()))))
                ));

        var renderedInterfaces = new HashSet<String>();
        var directionGroups = stream(Direction.values()).map(options.getDirectionGroup()).distinct().collect(toList());
        for (var directionGroup : directionGroups) {
            var byType = groupedInterfaces.getOrDefault(directionGroup, Map.of());
            if (!byType.isEmpty()) {
                var directionGroupStyle = options.getDirectionGroupAggregate().apply(directionGroup);
                var directionGroupPackageName = directionGroup.isBlank() ? null : directionGroup;
                printUnion(out, directionGroupPackageName, directionGroup, directionGroupStyle, () -> {
                    for (var type : Type.values()) {
                        var interfaceComponentLink = Optional.<Map<Interface, List<Component>>>ofNullable(byType.get(type)).orElse(Map.of());
                        if (!interfaceComponentLink.isEmpty()) {
                            var elementId = getElementId(directionGroup, type.code);
                            printUnion(out, type.code, elementId, options.getInterfaceAggregate().apply(type), () -> {
                                if (type == http) {
                                    //merge by url parts
                                    var httpMethods = extractHttpMethodsFromInterfaces(
                                            interfaceComponentLink.keySet());
                                    var finalGroup = groupByUrlParts(httpMethods);
                                    printHttpMethodGroup(out, finalGroup, options.getInterfaceSubgroupAggregate().apply(type),
                                            interfaceComponentLink, httpMethods, renderedInterfaces);
                                } else {
                                    interfaceComponentLink.forEach((anInterface, component) -> printInterface(
                                            out, anInterface, component, renderedInterfaces)
                                    );
                                }
                            });
                        }
                    }
                });
            }
        }
    }

    protected void printComponentDependencyRelations(IndentStringAppender out, Component component) {
        var componentName = component.getName();
        var collapsedComponentName = checkCollapsedName(componentName);
        var collapsed = !collapsedComponentName.equals(componentName);
        for (var dependency : component.getDependencies()) {
            var dependencyName = checkCollapsedName(dependency);
            var renderedRelation = format("%s ..> %s\n", plantUmlAlias(collapsedComponentName), plantUmlAlias(dependencyName));
            var alreadyPrinted = printedComponentRelations.getOrDefault(collapsedComponentName, Set.of()).contains(dependencyName);
            if (options.reduceCollapsedElementRelations && !alreadyPrinted) {
                out.append(renderedRelation);
                printedComponentRelations.computeIfAbsent(collapsedComponentName, k -> new HashSet<>()).add(dependencyName);
            } else {
                if (options.reduceInnerCollapsedElementRelations) {
                    var selfLink = collapsedComponentName.equals(dependencyName);
                    var collapsedSelfLinked = collapsed && selfLink;
                    if (collapsedSelfLinked && !alreadyPrinted) {
                        out.append(renderedRelation);
                        printedComponentRelations.computeIfAbsent(collapsedComponentName, k -> new HashSet<>()).add(dependencyName);
                    } else if (!collapsedSelfLinked) {
                        out.append(renderedRelation);
                    }
                } else {
                    out.append(renderedRelation);
                }
            }
        }
    }

    private String checkCollapsedName(String name) {
        var collapsedName = collapsedComponents.get(name);
        return collapsedName != null ? collapsedName : name;
    }

    @Getter
    @RequiredArgsConstructor
    public enum UnionBorder {
        rectangle,
        pack("package"),
        cloud,
        queue,
        file,
        folder,
        frame,
        database,
        together(false);

        private final boolean supportNameIdStyle;
        private final String code;

        UnionBorder(String code) {
            this(true, code);
        }

        UnionBorder(boolean supportNameIdStyle) {
            this(supportNameIdStyle, null);
        }

        UnionBorder() {
            this(true, null);
        }

        public String getCode() {
            return code != null ? code : name();
        }

        @Override
        public String toString() {
            return getCode();
        }
    }

    @Data
    @Builder(toBuilder = true)
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class Options {
        @Builder.Default
        Integer collapseComponentsMoreThen = 5;
        boolean reduceCollapsedElementRelations = false;
        boolean reduceInnerCollapsedElementRelations = true;
        Map<String, List<String>> idCharReplaces;
        Function<Direction, String> directionGroup;
        Function<String, UnionStyle> directionGroupAggregate;
        Function<Type, UnionStyle> interfaceAggregate;
        Function<Type, UnionStyle> interfaceSubgroupAggregate;
        Function<String, UnionStyle> packagePathAggregate;

        private static UnionStyle newAggregateStyle(UnionBorder unionBorder) {
            return UnionStyle.builder().unionBorder(unionBorder).build();
        }

        private static UnionStyle newAggregateStyle(UnionBorder unionBorder, String style) {
            return UnionStyle.builder().unionBorder(unionBorder).style(style).build();
        }

        public static UnionStyle getPackagePath(String path) {
            return newAggregateStyle(pack, "line.dotted;text:gray");
        }

        public static UnionStyle getAggregateStyle(Type type) {
            return newAggregateStyle(getAggregate(type));
        }

        public static UnionBorder getAggregate(Type type) {
            if (type != null) switch (type) {
                case jms:
                    return queue;
                case storage:
                    return database;
            }
            return rectangle;
        }

        public static UnionStyle getAggregateOfSubgroup(Type type) {
            return newAggregateStyle(frame, "line.dotted;");
        }

        public static UnionStyle getAggregateOfDirectionGroup(String directionGroup) {
            return newAggregateStyle(UnionBorder.cloud, "line.dotted;line:gray;");
        }

        public static String defaultDirectionGroup(Direction direction) {
            switch (direction) {
                case in:
                    return DIRECTION_INPUT;
                case out:
                case outIn:
                    return DIRECTION_OUTPUT;
                default:
                    return "";
            }
        }

        @Data
        @Builder(toBuilder = true)
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        public static class UnionStyle {
            UnionBorder unionBorder;
            String style;
        }
    }
}
