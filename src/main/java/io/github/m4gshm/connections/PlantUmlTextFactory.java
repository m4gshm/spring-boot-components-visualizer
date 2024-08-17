package io.github.m4gshm.connections;

import io.github.m4gshm.connections.PlantUmlTextFactory.Options.UnionStyle;
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
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.reverse;
import static io.github.m4gshm.connections.PlantUmlTextFactory.UnionBorder.*;
import static io.github.m4gshm.connections.PlantUmlTextFactoryUtils.*;
import static io.github.m4gshm.connections.UriUtils.PATH_DELIMITER;
import static io.github.m4gshm.connections.UriUtils.subURI;
import static io.github.m4gshm.connections.model.Interface.Type.*;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.*;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNullElseGet;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.concat;
import static lombok.AccessLevel.PRIVATE;

@Slf4j
public class PlantUmlTextFactory implements io.github.m4gshm.connections.SchemaFactory<String> {

    public static final String INDENT = "  ";
    public static final Map<String, List<String>> DEFAULT_ESCAPES = Map.of(
            "", List.of(" "),
            ".", List.of("-", PATH_DELIMITER, ":", "?", "=", ",", "&", "*", "$", "{", "}", "(", ")", "[", "]", "#", "\"", "'")
    );
    public static final String DIRECTION_INPUT = "input";
    public static final String DIRECTION_OUTPUT = "output";
    public static final String LINE_DOTTED_TEXT_GRAY = "line.dotted;text:gray";
    public static final String LINE_DOTTED_LINE_GRAY = "line.dotted;line:gray;";
    public static final String LINE_DOTTED = "line.dotted;";
    private final String applicationName;
    private final Options options;
    @Getter
    private final Map<String, String> collapsedComponents = new HashMap<>();
    //    private final Map<String, String> collapsedInterfaces = new HashMap<>();
    private final Map<String, Set<String>> printedComponentRelations = new HashMap<>();
    private final Map<String, Set<String>> printedInterfaceRelations = new HashMap<>();
    private final Map<String, Object> uniques = new HashMap<>();

    public PlantUmlTextFactory(String applicationName) {
        this(applicationName, null);
    }

    public PlantUmlTextFactory(String applicationName, Options options) {
        this.applicationName = applicationName;
        this.options = options != null ? options : Options.DEFAULT;
    }

    protected static void populateLastGroupByHttpMethods(Group rootGroup, HttpMethod httpMethod) {
        var url = httpMethod.getUrl();
        url = url.startsWith(PATH_DELIMITER) ? url.substring(1) : url;

        var parts = UriUtils.splitURI(url);

        Map<String, Group> prevGroupsLevel = null;
        var nexGroupsLevel = rootGroup.getGroups();
        var currentGroup = rootGroup;

        var path = new StringBuilder();
        for (var part : parts) {
            path.append(part);
            currentGroup = nexGroupsLevel.computeIfAbsent(part, k -> newEmptyGroup(part, path.toString()));
            prevGroupsLevel = nexGroupsLevel;
            nexGroupsLevel = currentGroup.getGroups();
        }

        var oldMethods = currentGroup.getMethods();
        var methods = new LinkedHashSet<>(oldMethods != null ? oldMethods : Set.of());
        methods.add(httpMethod);
        if (prevGroupsLevel != null) {
            //replace
            var groupWitMethods = currentGroup.toBuilder().methods(methods).build();
            prevGroupsLevel.put(currentGroup.getPart(), groupWitMethods);
        }
    }

    @Override
    public String create(Components components) {
        var out = new StringBuilder();

        out.append("@startuml\n");
        var head = options.getHead();
        if (head != null) {
            out.append(head);
            out.append("\n");
        }

//        out.append(format("component \"%s\" as %s\n", applicationName, pumlAlias(applicationName)));

        printBody(out, components.getComponents());
        var bottom = options.getBottom();
        if (bottom != null) {
            out.append(bottom);
            out.append("\n");
        }
        out.append("@enduml\n");
        return out.toString();
    }

    protected void checkUniqueId(String id, Object object) {
        if (options.isCheckUniqueViolation()) {
            var exists = uniques.get(id);
            if (exists != null) {
                throw new PalmUmlTextFactoryException("not unique id is detected: id '" + id +
                        "', object:" + object + ", exists:" + exists);
            } else {
                uniques.put(id, object);
            }
        }
    }

    protected void printBody(StringBuilder dest, Collection<Component> components) {
        var out = new IndentStringAppender(dest, INDENT);

        var packages = toPackagesHierarchy(components);

//        if (packages.size() < 2) {
//            printPackages(out, packages);
//        } else {
        printUnion(out, null, null, UnionStyle.builder().unionBorder(together).build(), () -> printPackages(out, packages));
//        }

        for (var component : components) {
            printComponentReferences(out, component);
        }

        printInterfaces(out, components);
    }

    protected void printInterfaces(IndentStringAppender out, Collection<Component> components) {
        var groupedInterfaces = components.stream()
                .flatMap(component -> Stream.ofNullable(component.getInterfaces())
                        .flatMap(Collection::stream)
                        .map(anInterface -> entry(anInterface.getDirection(), entry(anInterface, component)))
                )
                .collect(groupingBy(directionEntryEntry -> options.directionGroup.apply(directionEntryEntry.getKey()),
                        mapping(Entry::getValue, groupingBy(entry -> entry.getKey().getType(),
                                groupingBy(Entry::getKey, LinkedHashMap::new, mapping(Entry::getValue, toList()))))
                ));

        var directionGroups = stream(Direction.values()).map(options.getDirectionGroup()).distinct().collect(toList());
        for (var directionGroup : directionGroups) {
            var byType = groupedInterfaces.getOrDefault(directionGroup, Map.of());
            if (!byType.isEmpty()) {
                var directionGroupStyle = options.getDirectionGroupAggregate().apply(directionGroup);
                var directionGroupPackageName = directionGroup.isBlank() ? null : directionGroup;
                printUnion(out, directionGroupPackageName, directionGroup, directionGroupStyle, () -> {
                    for (var type : Type.values()) {
                        var interfaceRelations = Optional.<Map<Interface, List<Component>>>ofNullable(byType.get(type)).orElse(Map.of());
                        if (!interfaceRelations.isEmpty()) {
                            var directionGroupTypeId = getElementId(directionGroup, type.code);
                            printUnion(out, type.code, directionGroupTypeId, options.getInterfaceAggregate().apply(type), () -> {
                                printInterfaces(out, directionGroupTypeId, type, interfaceRelations);
                            });
                        }
                    }
                });
            }
        }
    }

    private void printInterfaces(IndentStringAppender out, String directionGroupTypeId, Type type,
                                 Map<Interface, List<Component>> interfaceRelations) {
        var group = options.supportGroups.test(type);
        if (type == http && group) {
            //merge by url parts
            var httpMethods = extractHttpMethodsFromInterfaces(interfaceRelations.keySet());
            var finalGroup = groupByUrlParts(httpMethods);
            var unionStyle = options.getInterfaceSubgroupAggregate().apply(type);
            printHttpMethodGroup(out, finalGroup, unionStyle, interfaceRelations, httpMethods);
        } else if (group) {
            printGroupedInterfaces(out, directionGroupTypeId, type, interfaceRelations);
        } else {
            printInterfaces(out, directionGroupTypeId, interfaceRelations);
        }
    }

    protected void printGroupedInterfaces(IndentStringAppender out, String directionGroupTypeId, Type type,
                                          Map<Interface, List<Component>> interfaceRelations) {
        var unionStyle = options.getInterfaceSubgroupAggregate().apply(type);
        var groupedInterfaces = groupInterfacesByComponents(interfaceRelations);
        groupedInterfaces.forEach((group, interfaceRelationsOfGroup) -> {
            var groupName = interfaceRelationsOfGroup.size() > 1 ? group : null;
            var groupId = getElementId(directionGroupTypeId, type.code, groupName);
            printUnion(out, groupName, groupId, unionStyle, () -> {
                printInterfaces(out, groupName, interfaceRelationsOfGroup);
            });
        });
    }

    protected void printInterfaces(IndentStringAppender out, String groupName, Map<Interface, List<Component>> interfaceRelations) {
        var collapseInterfacesMoreThan = options.getCollapseInterfacesMoreThan();
        if (collapseInterfacesMoreThan != null && interfaceRelations.size() > collapseInterfacesMoreThan) {
            printCollapsedInterfaces(out, groupName, interfaceRelations);
        } else {
            interfaceRelations.forEach((anInterface, components) -> printInterface(out, anInterface, components));
        }
    }

    protected Map<String, Map<Interface, List<Component>>> groupInterfacesByComponents(
            Map<Interface, List<Component>> interfaceRelations
    ) {
        return interfaceRelations.entrySet().stream()
                .map(e -> entry(new LinkedHashSet<>(e.getValue()), e))
                .collect(groupingBy(e -> e.getKey().stream().map(Component::getName)
                                .reduce("", (l, r) -> (l.isBlank() ? "" : l + ",") + r),
                        mapping(Entry::getValue, toMap(Entry::getKey, Entry::getValue, (l, r) -> {
                            var s = new ArrayList<>(l);
                            s.addAll(r);
                            return unmodifiableList(s);
                        }))));
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
                        checkUniqueId(id, name);
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
        var rootGroup = newEmptyGroup(null, null);
        //create groups
        for (var httpMethod : httpMethods.keySet()) {
            populateLastGroupByHttpMethods(rootGroup, httpMethod);
        }
        //reduce groups
        var reducedGroup = reduce(rootGroup);
        return reducedGroup;
    }

    protected Group reduce(Group group) {
        var subGroups = group.getGroups();
        var part = group.getPart();
        var path = group.getPath();
        var newMethods = group.getMethods();
        while (subGroups.size() == 1 && newMethods == null) {
            var nextGroupPath = subGroups.keySet().iterator().next();
            var nextGroup = subGroups.get(nextGroupPath);
            path = nextGroup.getPath();
            part = (part != null ? part : "") + nextGroup.getPart();
            newMethods = nextGroup.getMethods();
            subGroups = nextGroup.getGroups();
        }
        var reducedGroup = group.toBuilder().part(part).path(path).methods(newMethods).groups(subGroups);
        var reducedGroupMethods = newMethods != null ? newMethods : new LinkedHashSet<HttpMethod>();
        var reducedSubGroups = subGroups.entrySet().stream()
                .map(e -> entry(e.getKey(), reduce(e.getValue())))
                .filter(e -> {
                    var subGroup = e.getValue();
                    var subGroupGroups = subGroup.getGroups();
                    var subGroupMethods = subGroup.getMethods();
                    if (subGroupGroups == null || subGroupGroups.isEmpty()) {
                        if (subGroupMethods == null || subGroupMethods.isEmpty()) {
                            //remove the subgroup
                            return false;
                        } else if (subGroupMethods.size() == 1) {
                            //move methods of the subgroup to the parent group
                            var parentGroupMethods = group.getMethods();
                            var httpMethods = new LinkedHashSet<>(parentGroupMethods != null ? parentGroupMethods : emptySet());
                            httpMethods.addAll(subGroupMethods);
                            reducedGroupMethods.addAll(httpMethods);
//                            reducedGroup.methods(httpMethods);
//                            subGroup.setMethods(Set.of());
                            //remove the subgroup
                            return false;
                        }
                    }
                    return true;
                })
                .collect(toMap(Entry::getKey, Entry::getValue, (l, r) -> {
                    log.debug("merge http method groups {} and {}", l, r);
                    return l;
                }, LinkedHashMap::new));
        return reducedGroup.methods(reducedGroupMethods).groups(reducedSubGroups).build();
    }

    protected String getInterfaceId(Interface anInterface) {
        var direction = getElementId(anInterface.getDirection().name());
        return getElementId(direction, anInterface.getId());
    }

    protected void printPackage(IndentStringAppender out, Package pack) {
        var packPath = pack.getPath();
        var style = options.getPackagePathAggregate().apply(packPath);
        printUnion(out, isPrintBorder(pack) ? pack.getName() : null, packPath, style, () -> {
            var components = pack.getComponents();
            if (isCollapseComponents(pack)) {
                printCollapsedComponents(out, packPath, components);
            } else if (components != null) {
                for (var component : components) {
                    printComponent(out, component);
                }
            }
            var packages = pack.getPackages();
            if (packages != null) printPackages(out, packages);
        });
    }

    protected boolean isPrintBorder(Package pack) {
        return options.isPrintPackageBorder();
    }

    protected boolean isCollapseComponents(Package pack) {
        var components = pack.getComponents();
        var collapseComponents = options.collapseComponents.apply(pack);
        return requireNonNullElseGet(collapseComponents, () -> options.collapseComponentsMoreThan != null
                && components != null
                && components.size() > options.collapseComponentsMoreThan);
    }

    private void printPackages(IndentStringAppender out, List<Package> packages) {
        for (var pack : packages) {
            printPackage(out, pack);
        }
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
            return l.toBuilder().components(components).packages(copyOf(distinctPackages.values())).build();
        }, LinkedHashMap::new));
    }

    protected void printHttpMethodGroup(IndentStringAppender out, Group group,
                                        UnionStyle style,
                                        Map<Interface, List<Component>> interfaceComponentLink,
                                        Map<HttpMethod, Interface> httpMethods) {
        var methods = group.getMethods();
        var subGroups = group.getGroups();
        if (subGroups.isEmpty() && methods != null && methods.size() == 1) {
            printInterfaceAndSubgroups(out, group, style, interfaceComponentLink, httpMethods);
        } else {
            printUnion(out, group.getPart(), null, style,
                    () -> printInterfaceAndSubgroups(out, group, style, interfaceComponentLink, httpMethods)
            );
        }
    }

    protected void printInterfaceAndSubgroups(IndentStringAppender out,
                                              Group group, UnionStyle style,
                                              Map<Interface, List<Component>> interfaceComponentLink,
                                              Map<HttpMethod, Interface> httpMethods) {

        var groupMethods = group.getMethods();
        var groupInterfaces = Stream.ofNullable(groupMethods).flatMap(Collection::stream).map(method -> {
            var anInterface = httpMethods.get(method);
            var subUri = subURI(group.getPath(), method.getUrl());
            var name = HttpMethod.builder()
                    .method(method.getMethod()).url(subUri)
                    .build().toString();
            return entry(
                    anInterface.toBuilder().name(name).build(),
                    interfaceComponentLink.get(anInterface)
            );
        }).collect(toMap(Entry::getKey, Entry::getValue));

        printInterfaces(out, group.getPart(), groupInterfaces);

        for (var subGroup : group.getGroups().values()) {
            printHttpMethodGroup(out, subGroup, style, interfaceComponentLink, httpMethods);
        }

    }

    protected void printInterface(IndentStringAppender out, Interface anInterface, Collection<Component> components) {
        var interfaceId = getInterfaceId(anInterface);
        checkUniqueId(interfaceId, anInterface);
        out.append(format(renderAs(anInterface.getType()) + " \"%s\" as %s\n",
                renderInterfaceName(anInterface), interfaceId));

        printInterfaceCore(out, anInterface.getCore(), interfaceId);

        printInterfaceReferences(out, interfaceId, anInterface, components);
    }

    protected void printInterfaceReferences(IndentStringAppender out,
                                            String interfaceId, Interface anInterface,
                                            Collection<Component> components) {
        for (var component : components) {
            printInterfaceReference(out, interfaceId, anInterface, component);
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

    protected void printInterfaceReference(IndentStringAppender out, String interfaceId,
                                           Interface anInterface, Component component) {
        var type = anInterface.getType();
        var componentName = component.getName();
        var collapsedComponentId = checkCollapsedName(componentName);
        var collapsedComponent = !componentName.equals(collapsedComponentId);
        var componentId = collapsedComponent ? collapsedComponentId : plantUmlAlias(componentName);
        var printed = collapsedComponent && printedInterfaceRelations.getOrDefault(componentId, Set.of()).contains(interfaceId);
        if (printed) {
            return;
        }

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
        printedInterfaceRelations.computeIfAbsent(componentId, k -> new LinkedHashSet<>()).add(interfaceId);
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
                .packages(ofNullable(pack.getPackages()).orElse(emptyList()).stream()
                        .map(p -> populatePath(elementId, p)).collect(toList()))
                .build();
    }

    protected void printComponent(IndentStringAppender out, Component component) {
        var componentName = component.getName();
        var componentId = plantUmlAlias(componentName);
        checkUniqueId(componentId, component);
        out.append(format("component %s as %s\n", componentName, componentId));
    }

    protected void printCollapsedComponents(IndentStringAppender out, String packageId,
                                            Collection<Component> components) {
        if (components == null || components.isEmpty()) {
            return;
        }
        var text = components.stream().map(Component::getName)
                .reduce("", (l, r) -> (l.isBlank() ? "" : l + "\\n\\\n") + r);
        var collapsedComponentsId = getElementId(packageId, "components");
        checkUniqueId(collapsedComponentsId, "package:" + packageId);
        for (var component : components) {
            collapsedComponents.put(component.getName(), collapsedComponentsId);
        }
        out.append(format("collections \"%s\" as %s\n", text, collapsedComponentsId), false);
    }

    protected void printCollapsedInterfaces(IndentStringAppender out, String parentId,
                                            Map<Interface, List<Component>> interfaces) {
        if (interfaces == null || interfaces.isEmpty()) {
            return;
        }
        var text = interfaces.keySet().stream().map(Interface::getName)
                .reduce("", (l, r) -> (l.isBlank() ? "" : l + "\\n\\\n") + r);
        var collapsedId = getElementId(parentId, "interfaces");
        checkUniqueId(collapsedId, "parent:" + parentId);
        out.append(format("collections \"%s\" as %s\n", text, collapsedId), false);
        interfaces.forEach((anInterface, components) -> printInterfaceReferences(out, collapsedId, anInterface, components));
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

    protected List<Package> toPackagesHierarchy(Collection<Component> components) {
        return distinctPackages(null, components.stream()
                .map(this::getComponentPackage))
                .values().stream().flatMap(this::mergeSubPack)
                .collect(toList());
    }

    protected Package getComponentPackage(Component component) {
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

    protected void printComponentReferences(IndentStringAppender out, Component component) {
        var componentName = component.getName();
        var collapsedComponentName = checkCollapsedName(componentName);
        var collapsed = !collapsedComponentName.equals(componentName);
        for (var dependency : component.getDependencies()) {
            var dependencyName = checkCollapsedName(dependency);
            var selfLink = collapsedComponentName.equals(dependencyName);
            var renderedRelation = format("%s ..> %s\n", plantUmlAlias(collapsedComponentName), plantUmlAlias(dependencyName));
            var alreadyPrinted = printedComponentRelations.getOrDefault(collapsedComponentName, Set.of()).contains(dependencyName);

            var render = !collapsed || (selfLink
                    ? options.reduceInnerCollapsedElementRelations && !alreadyPrinted
                    : options.reduceCollapsedElementRelations && !alreadyPrinted);
            if (render) {
                printedComponentRelations.computeIfAbsent(collapsedComponentName, k -> new HashSet<>()).add(dependencyName);
                out.append(renderedRelation);
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
        public static final Options DEFAULT = Options.builder().build();
        String head, bottom;
        @Builder.Default
        boolean reduceCollapsedElementRelations = false;
        @Builder.Default
        boolean reduceInnerCollapsedElementRelations = true;
        @Builder.Default
        boolean reduceCollapsedInterfaceRelations = true;
        @Builder.Default
        boolean printPackageBorder = true;
        //debug option
        @Builder.Default
        boolean checkUniqueViolation = true;
        @Builder.Default
        Map<String, List<String>> idCharReplaces = DEFAULT_ESCAPES;
        @Builder.Default
        Function<Direction, String> directionGroup = Options::defaultDirectionGroup;
        @Builder.Default
        Function<String, UnionStyle> directionGroupAggregate = directionGroup -> newAggregateStyle(cloud, LINE_DOTTED_LINE_GRAY);
        @Builder.Default
        Function<Type, UnionStyle> interfaceAggregate = type -> newAggregateStyle(getAggregate(type));
        @Builder.Default
        Function<Type, UnionStyle> interfaceSubgroupAggregate = type -> newAggregateStyle(frame, LINE_DOTTED_TEXT_GRAY);
        @Builder.Default
        Function<String, UnionStyle> packagePathAggregate = path -> newAggregateStyle(pack, LINE_DOTTED_TEXT_GRAY);
        @Builder.Default
        Predicate<Type> supportGroups = type -> Set.of(http, jms, ws).contains(type);
        @Builder.Default
        Integer collapseComponentsMoreThan = 5;
        @Builder.Default
        Integer collapseInterfacesMoreThan = 5;
        @Builder.Default
        Function<Package, Boolean> collapseComponents = pack -> null;

        public static UnionStyle newAggregateStyle(UnionBorder unionBorder) {
            return UnionStyle.builder().unionBorder(unionBorder).build();
        }

        public static UnionStyle newAggregateStyle(UnionBorder unionBorder, String style) {
            return UnionStyle.builder().unionBorder(unionBorder).style(style).build();
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

        public static String defaultDirectionGroup(Direction direction) {
            switch (direction) {
                case in:
                    return DIRECTION_INPUT;
                case out:
                case outIn:
                    return DIRECTION_OUTPUT;
                case undefined:
                    return "";
                default:
                    return direction.name();
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
