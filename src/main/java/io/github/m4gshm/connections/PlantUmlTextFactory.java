package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.*;
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
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.reverse;
import static io.github.m4gshm.connections.PlantUmlTextFactory.DirectionGroup.*;
import static io.github.m4gshm.connections.PlantUmlTextFactory.HtmlMethodsGroupBy.path;
import static io.github.m4gshm.connections.PlantUmlTextFactory.UnionBorder.*;
import static io.github.m4gshm.connections.PlantUmlTextFactoryUtils.*;
import static io.github.m4gshm.connections.UriUtils.PATH_DELIMITER;
import static io.github.m4gshm.connections.Utils.*;
import static io.github.m4gshm.connections.model.HttpMethodsGroup.makeGroupsHierarchyByHttpMethodUrl;
import static io.github.m4gshm.connections.model.Interface.Type.*;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.*;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.concat;
import static lombok.AccessLevel.PRIVATE;

@Slf4j
public class PlantUmlTextFactory implements io.github.m4gshm.connections.SchemaFactory<String> {

    public static final String INDENT = "  ";
    public static final Map<String, List<String>> DEFAULT_ESCAPES = Map.of("", List.of(" "), ".", List.of("-", PATH_DELIMITER, ":", "?", "=", ",", "&", "*", "$", "{", "}", "(", ")", "[", "]", "#", "\"", "'", "%"));
    public static final String LINE_DOTTED_TEXT_GRAY = "line.dotted;text:gray";
    public static final String LINE_DOTTED_LINE_GRAY = "line.dotted;line:gray;";
    public static final String LINE_DOTTED = "line.dotted;";
    public static final String SHORT_ARROW = "..>";
    public static final String MIDDLE_ARROW = "....>";
    public static final String LONG_ARROW = "......>";
    public static final String TABLE_TRANSPARENT = "<#transparent,transparent>";

    protected final String applicationName;
    protected final Options options;
    protected final Map<String, String> concatenatedComponents = new HashMap<>();
    protected final Map<String, String> concatenatedInterfaces = new HashMap<>();
    protected final Map<String, Set<String>> printedConcatenatedComponentRelations = new HashMap<>();
    protected final Map<String, Set<String>> printedInterfaceRelations = new HashMap<>();
    protected final Map<String, Object> uniques = new HashMap<>();
    protected final Set<Component> printedComponents = new LinkedHashSet<>();

    public PlantUmlTextFactory(String applicationName) {
        this(applicationName, null);
    }

    public PlantUmlTextFactory(String applicationName, Options options) {
        this.applicationName = applicationName;
        this.options = options != null ? options : Options.DEFAULT;
    }

    protected static <T> List<List<T>> splitOnTableParts(int columns, int rows, List<T> elements) {
        var size = elements.size();
        var maxElements = columns * rows;
        if (size <= maxElements) {
            return List.of(elements);
        }
        var result = new ArrayList<List<T>>();
        for (int first = 0; ; ) {
            var tail = size - first;
            var last = first + min(maxElements, tail);
            List<T> elementsPart = elements.subList(first, last);

            result.add(elementsPart);
            if (last >= size) {
                break;
            }
            first = last;
        }
        return result;
    }

    private static RowsCols getRowsCols(int rows, int columns, int elementsAmount) {
        rows = rows < 1 ? columns < 1 ? 1 : elementsAmount / columns : rows;
        columns = columns < 1 ? (elementsAmount / rows) + 1 : columns;
        return new RowsCols(rows, columns);
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

        var componentComparator = options.getSort().getComponents();
        printBody(out, componentComparator != null ? components.getComponents().stream().sorted(componentComparator).collect(toList()) : components.getComponents());
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
                throw new PalmUmlTextFactoryException("not unique id is detected: id '" + id + "', object:" + object + ", exists:" + exists);
            } else {
                uniques.put(id, object);
            }
        }
    }

    protected void printBody(StringBuilder dest, Collection<Component> components) {
        var out = new IndentStringAppender(dest, INDENT);
        var packages = toPackagesHierarchy(components);
        printPackages(out, packages, null);
        for (var component : components) {
            printComponentReferences(out, component);
        }
        printInterfaces(out, components);
    }

    protected void printInterfaces(IndentStringAppender out, Collection<Component> components) {
        var groupedInterfaces = components.stream().flatMap(component -> Stream.ofNullable(component.getInterfaces()).flatMap(Collection::stream).map(anInterface -> entry(anInterface.getDirection(), entry(anInterface, component)))).collect(groupingBy(directionEntryEntry -> options.directionGroup.apply(directionEntryEntry.getKey()), mapping(Entry::getValue, groupingBy(entry -> entry.getKey().getType(), groupingBy(Entry::getKey, () -> {
            return ofNullable(options.getSort().getInterfaces()).map(c -> (Map<Interface, List<Component>>) new TreeMap<Interface, List<Component>>(c)).orElseGet(LinkedHashMap::new);
        }, mapping(Entry::getValue, toList()))))));

        var directionGroups = stream(Direction.values()).map(options.getDirectionGroup()).distinct().collect(toList());
        for (var directionGroup : directionGroups) {
            var byType = groupedInterfaces.getOrDefault(directionGroup, Map.of());
            if (!byType.isEmpty()) {
                var directionGroupStyle = options.getDirectionGroupAggregate().apply(directionGroup);
                var directionGroupName = directionGroup == null ? null : directionGroup.name();
                printUnion(out, directionGroupName, directionGroupName, directionGroupStyle, () -> {
                    for (var type : Type.values()) {
                        var interfaceRelations = Optional.<Map<Interface, List<Component>>>ofNullable(byType.get(type)).orElse(Map.of());
                        if (!interfaceRelations.isEmpty()) {
                            printUnion(out, type.code, getDirectionGroupTypeId(directionGroup, type), options.getInterfaceAggregate().apply(directionGroup, type), () -> {
                                printInterfaces(out, directionGroup, type, interfaceRelations);
                            });
                        }
                    }
                });
            }
        }
    }

    protected String getDirectionGroupTypeId(DirectionGroup directionGroup, Type type) {
        return getElementId(directionGroup == null ? null : directionGroup.name(), type.code);
    }

    protected void printInterfaces(IndentStringAppender out, DirectionGroup directionGroup, Type type, Map<Interface, List<Component>> interfaceRelations) {
        var directionGroupTypeId = getDirectionGroupTypeId(directionGroup, type);
        var group = isInterfacesSupportGroups(directionGroup, type);
        if (group) {
            if (type == http && options.htmlMethodsGroupBy.apply(directionGroup) == path) {
                //merge by url parts
                var httpMethods = extractHttpMethodsFromInterfaces(interfaceRelations);
                var finalGroup = groupByUrlParts(httpMethods);
                var unionStyle = options.getInterfaceSubgroupAggregate().apply(directionGroup, type);
                printHttpMethodGroup(out, directionGroupTypeId, finalGroup, unionStyle, interfaceRelations, httpMethods);
            } else {
                printGroupedInterfaces(out, directionGroup, type, groupInterfacesByComponents(interfaceRelations));
            }
        } else {
            printInterfaces(out, directionGroupTypeId, interfaceRelations);
        }
    }

    protected boolean isInterfacesSupportGroups(DirectionGroup directionGroup, Type type) {
        return options.supportGroups.test(directionGroup, type);
    }

    protected void printGroupedInterfaces(IndentStringAppender out, DirectionGroup directionGroup, Type type, Map<String, Map<Interface, List<Component>>> groupedInterfaceRelations) {
        var unionStyle = options.getInterfaceSubgroupAggregate().apply(directionGroup, type);
        groupedInterfaceRelations.forEach((groupName, interfaceRelationsOfGroup) -> {
            var groupId = getElementId(getDirectionGroupTypeId(directionGroup, type), groupName);
            printUnion(out, groupName, groupId, unionStyle, () -> {
                printInterfaces(out, groupName, interfaceRelationsOfGroup);
            });
        });
    }

    protected void printInterfaces(IndentStringAppender out, String parentId, Map<Interface, List<Component>> interfaceRelations) {
        var interfaces = groupConcatenatedInterfaces(interfaceRelations);
        printConcatenatedInterfaces(out, parentId, interfaces.getConcatenation());
        interfaces.getDistinct().forEach((anInterface, components) -> printInterface(out, anInterface, components));
    }

    protected ConcatenatedInterfacesGroup groupConcatenatedInterfaces(Map<Interface, List<Component>> interfaceRelations) {
        var concatenateInterfacesMoreThan = options.getConcatenateComponents().getMoreThan();
        var concatenate = concatenateInterfacesMoreThan != null && interfaceRelations.size() > concatenateInterfacesMoreThan;
        var builder = ConcatenatedInterfacesGroup.builder();
        if (concatenate) {
            builder.concatenation(interfaceRelations);
        } else {
            builder.distinct(interfaceRelations);
        }
        return builder.build();
    }

    protected Map<String, Map<Interface, List<Component>>> groupInterfacesByComponents(Map<Interface, List<Component>> interfaceRelations) {
        return interfaceRelations.entrySet().stream().map(e -> entry(new LinkedHashSet<>(e.getValue()), e)).collect(groupingBy(e -> e.getKey().stream().map(Component::getName).reduce("", (l, r) -> (l.isBlank() ? "" : l + ",") + r), mapping(Entry::getValue, toMap(Entry::getKey, Entry::getValue, (l, r) -> {
            var s = new ArrayList<>(l);
            s.addAll(r);
            return unmodifiableList(s);
        }, LinkedHashMap::new))));
    }

    protected void printUnion(IndentStringAppender out, String name, String id, UnionStyle unionStyle, Runnable internal) {
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

    protected Map<HttpMethod, Interface> extractHttpMethodsFromInterfaces(Map<Interface, List<Component>> interfaceRelations) {
        return interfaceRelations.keySet().stream().map(anInterface -> {
            var core = anInterface.getCore();
            if (core instanceof HttpMethod) {
                return entry((HttpMethod) core, anInterface);
            } else {
                return null;
            }
        }).filter(Objects::nonNull).collect(toMap(Entry::getKey, Entry::getValue, warnDuplicated(), LinkedHashMap::new));
    }

    protected HttpMethodsGroup groupByUrlParts(Map<HttpMethod, Interface> httpMethods) {
        var rootGroup = newEmptyGroup(null);
        //create groups
        for (var httpMethod : httpMethods.keySet()) {
            makeGroupsHierarchyByHttpMethodUrl(rootGroup, httpMethod);
        }
        //reduce groups
        return reduceUrlBasedHttpMethodGroups(rootGroup);
    }

    protected HttpMethodsGroup reduceUrlBasedHttpMethodGroups(HttpMethodsGroup group) {
        var subGroups = group.getGroups();
        var part = group.getName();
        var newMethods = group.getMethods();
        while (subGroups != null && subGroups.size() == 1 && newMethods == null) {
            var e = subGroups.entrySet().iterator().next();
            var nextGroup = e.getValue();
            part = (part != null ? part : "") + nextGroup.getName();
            newMethods = nextGroup.getMethods();
            subGroups = nextGroup.getGroups();
        }
        var reducedGroup = group.toBuilder().name(part).methods(newMethods).groups(subGroups);
        var reducedGroupMethods = newMethods != null ? newMethods : new LinkedHashMap<HttpMethod, HttpMethod>();
        var reducedSubGroups = Stream.ofNullable(subGroups).flatMap(m -> m.entrySet().stream()).map(e -> entry(e.getKey(), reduceUrlBasedHttpMethodGroups(e.getValue()))).filter(e -> {
            var subGroup = e.getValue();
            var subGroupGroups = subGroup.getGroups();
            var subGroupMethods = subGroup.getMethods();
            if (subGroupGroups == null || subGroupGroups.isEmpty()) {
                if (subGroupMethods == null || subGroupMethods.isEmpty()) {
                    //remove the subgroup
                    return false;
                } else if (subGroupMethods.size() == 1) {
                    //move methods of the subgroup to the parent group
                    var movedToParentGroupMethods = subGroupMethods.entrySet().stream().map(entry -> {
                        var groupMethod = entry.getKey();
                        var path = e.getKey() + groupMethod.getPath();
                        var newGroupMethod = groupMethod.toBuilder().path(path).build();
                        return entry(newGroupMethod, entry.getValue());
                    }).collect(toMap(Entry::getKey, Entry::getValue, warnDuplicated(), LinkedHashMap::new));
                    reducedGroupMethods.putAll(movedToParentGroupMethods);
                    //remove the subgroup
                    return false;
                }
            }
            return true;
        }).collect(toMap(Entry::getKey, Entry::getValue, warnDuplicated(), LinkedHashMap::new));
        return reducedGroup.methods(reducedGroupMethods).groups(reducedSubGroups).build();
    }

    protected String getInterfaceId(Interface anInterface) {
        var direction = getElementId(anInterface.getDirection().name());
        return getElementId(direction, requireNonNull(anInterface.getId()));
    }

    protected void printPackage(IndentStringAppender out, Package pack, Package parentPackage) {
        var style = options.getPackagePathAggregate().apply(pack.getPath());
        final Runnable printRoutine = () -> {
            printPackageComponents(out, pack, parentPackage);
            var packages = pack.getPackages();
            if (packages != null) printPackages(out, packages, pack);
        };
        var printBorder = isPrintBorder(pack, parentPackage);
        if (printBorder) {
            printUnion(out, pack.getName(), pack.getPath(), style, printRoutine);
        } else {
            printRoutine.run();
        }
    }

    protected void printPackageComponents(IndentStringAppender out, Package pack, Package parentPackage) {
        var concatenateComponentGroup = groupConcatenatedComponents(pack, parentPackage);
        printConcatenatedComponents(out, pack, concatenateComponentGroup.getConcatenation());
        printDistinctComponents(out, concatenateComponentGroup.getDistinct());
    }

    protected void printDistinctComponents(IndentStringAppender out, Collection<Component> components) {
        if (components != null) for (var component : components) {
            printComponent(out, component);
        }
    }

    protected boolean isPrintBorder(Package pack, Package parentPackage) {
        return options.isPrintPackageBorder();
    }

    protected ConcatenatedComponentGroup groupConcatenatedComponents(Package pack, Package parentPackage) {
        var components = pack.getComponents();
        var componentGroupBuilder = ConcatenatedComponentGroup.builder();
        var concatenatePackageComponents = options.concatenateComponents;
        var moreThan = concatenatePackageComponents.moreThan;
        if (moreThan != null && components != null && components.size() > moreThan) {
            var ignoreInterfaceRelated = concatenatePackageComponents.ignoreInterfaceRelated;
            if (ignoreInterfaceRelated) {
                var grouped = components.stream().collect(groupingBy(c -> {
                    var interfaces = c.getInterfaces();
                    return !(interfaces == null || interfaces.isEmpty());
                }, toLinkedHashSet()));
                var distinct = grouped.get(true);
                var concatenation = grouped.get(false);
                componentGroupBuilder.distinct(distinct != null ? distinct : Set.of());
                componentGroupBuilder.concatenation(concatenation != null ? concatenation : Set.of());
            } else {
                componentGroupBuilder.concatenation(components);
            }
        } else {
            componentGroupBuilder.distinct(components);
        }
        return componentGroupBuilder.build();
    }

    protected void printPackages(IndentStringAppender out, Collection<Package> packages, Package parentPackage) {
        for (var pack : packages) {
            printPackage(out, pack, parentPackage);
        }
    }

    protected Map<String, Package> distinctPackages(String parentPath, Stream<Package> packageStream) {
        return packageStream.map(p -> populatePath(parentPath, p)).collect(toMap(Package::getName, p -> p, (l, r) -> {
            var lName = l.getName();
            var rName = r.getName();
            var validPackages = lName != null && lName.equals(rName) || rName == null;
            if (!validPackages) {
                throw new IllegalArgumentException("cannot merge packages with different names '" + lName + "', '" + rName + "'");
            }

            var components = Stream.of(l.getComponents(), r.getComponents()).filter(Objects::nonNull).flatMap(Collection::stream).collect(toList());

            var distinctPackages = distinctPackages(getElementId(parentPath, l.getName()), concat(ofNullable(l.getPackages()).orElse(emptyList()).stream(), ofNullable(r.getPackages()).orElse(emptyList()).stream()));
            return l.toBuilder().components(components).packages(copyOf(distinctPackages.values())).build();
        }, LinkedHashMap::new));
    }

    protected void printHttpMethodGroup(IndentStringAppender out, String parentGroupId, HttpMethodsGroup group, UnionStyle style, Map<Interface, List<Component>> interfaceComponentLink, Map<HttpMethod, Interface> httpMethods) {
        var methods = group.getMethods();
        var subGroups = group.getGroups();
        var groupId = getElementId(parentGroupId, group.getName());
        if ((subGroups == null || subGroups.isEmpty()) && methods != null && methods.size() == 1) {
            printInterfaceAndSubgroups(out, groupId, group, style, interfaceComponentLink, httpMethods);
        } else {
            printUnion(out, group.getName(), groupId, style, () -> printInterfaceAndSubgroups(out, groupId, group, style, interfaceComponentLink, httpMethods));
        }
    }

    protected void printInterfaceAndSubgroups(IndentStringAppender out, String groupId, HttpMethodsGroup group, UnionStyle style, Map<Interface, List<Component>> interfaceComponentLink, Map<HttpMethod, Interface> httpMethods) {
        var groupMethods = group.getMethods();
        var groupInterfaces = Stream.ofNullable(groupMethods).flatMap(e -> e.entrySet().stream()).map(e -> {
            var groupHttpMethod = e.getKey();
            var origHttpMethod = e.getValue();
            var anInterface = httpMethods.get(origHttpMethod);
            var groupedInterface = anInterface.toBuilder().name(groupHttpMethod).build();
            return entry(groupedInterface, interfaceComponentLink.get(anInterface));
        }).collect(toMap(Entry::getKey, Entry::getValue, warnDuplicated(), LinkedHashMap::new));

        printInterfaces(out, groupId, groupInterfaces);

        var subGroups = group.getGroups();
        if (subGroups != null) for (var subGroup : subGroups.values()) {
            printHttpMethodGroup(out, groupId, subGroup, style, interfaceComponentLink, httpMethods);
        }
    }

    protected void printInterface(IndentStringAppender out, Interface anInterface, Collection<Component> components) {
        var interfaceId = getInterfaceId(anInterface);
        checkUniqueId(interfaceId, anInterface);
        out.append(renderInterface(anInterface, interfaceId));

        printInterfaceCore(out, anInterface.getCore(), interfaceId);
        printInterfaceReferences(out, anInterface, interfaceId, components);
    }

    protected String renderInterface(Interface anInterface, String interfaceId) {
        return format(renderAs(anInterface.getType()) + " \"%s\" as %s\n", renderInterfaceName(anInterface), interfaceId);
    }

    protected void printInterfaceReferences(IndentStringAppender out, Interface anInterface, String interfaceId, Collection<Component> components) {
        for (var component : components) {
            printInterfaceReference(out, anInterface, interfaceId, component);
        }
    }

    protected String renderInterfaceName(Interface anInterface) {
        var name = anInterface.getName().toString();
        if (anInterface.getType() == storage) {
            var lasted = name.lastIndexOf(".");
            return lasted > 0 ? name.substring(lasted + 1) : null;
        }
        return name;
    }

    protected void printInterfaceCore(IndentStringAppender out, Object core, String interfaceId) {
        if (core instanceof Storage) {
            out.append(renderStorage((Storage) core, interfaceId));
        }
    }

    protected String renderStorage(Storage storage, String interfaceId) {
        var storedTo = storage.getStoredTo();
        var tables = storedTo.stream().reduce("", (l, r) -> (l.isBlank() ? "" : l + "\n") + r);
        return format("note right of %s: %s\n", interfaceId, tables);
    }

    protected void printInterfaceReference(IndentStringAppender out, Interface anInterface, String interfaceId, Component component) {
        var type = anInterface.getType();
        var componentName = component.getName();
        if (!printedComponents.contains(component)) {
            return;
        }
        var concatenatedComponentId = getComponentName(componentName);
        var concatenatedComponent = !componentName.equals(concatenatedComponentId);
        var componentId = concatenatedComponent ? concatenatedComponentId : plantUmlAlias(componentName);
        var printed = options.reduceDuplicatedElementRelations && printedInterfaceRelations.getOrDefault(componentId, Set.of()).contains(interfaceId);
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
        return format((type == jms ? "%s ....> %s" : "%s ....( %s") + "\n", componentId, interfaceId);
    }

    protected String renderOutIn(Type type, String interfaceId, String componentId) {
        return format("%1$s ....> %2$s\n%1$s <.... %2$s\n", componentId, interfaceId);
    }

    protected String renderIn(Type type, String interfaceId, String componentId) {
        return format("%s )....> %s\n", interfaceId, componentId);
    }

    protected String renderLink(Type type, String interfaceId, String componentId) {
        return format("%s .... %s\n", interfaceId, componentId);
    }

    protected Package populatePath(String parentPath, Package pack) {
        var elementId = getElementId(parentPath, pack.getName());
        return pack.toBuilder().path(elementId).packages(ofNullable(pack.getPackages()).orElse(emptyList()).stream().map(p -> populatePath(elementId, p)).collect(toList())).build();
    }

    protected void printComponent(IndentStringAppender out, Component component) {
        var componentName = component.getName();
        var componentId = plantUmlAlias(componentName);
        checkUniqueId(componentId, component);
        out.append(format("component %s as %s\n", componentName, componentId));
        printedComponents.add(component);
    }

    protected void printConcatenatedComponents(IndentStringAppender out, Package pack, Collection<Component> components) {
        var packageId = pack.getPath();
        if (components == null || components.isEmpty()) {
            return;
        }
        var part = 0;
        var renderedParts = renderConcatenatedComponentsText(pack, components);
        for (var renderedPart : renderedParts) {
            var text = renderedPart.getKey();
            var textComponents = renderedPart.getValue();
            var concatenatedComponentsId = renderedParts.size() == 1 ? getElementId(packageId, "components") : getElementId(packageId, "components", String.valueOf(++part));
            checkUniqueId(concatenatedComponentsId, "package:" + packageId);
            for (var component : textComponents) {
                concatenatedComponents.put(component.getName(), concatenatedComponentsId);
            }
            printedComponents.addAll(components);
            out.append(format("collections \"%s\" as %s\n", text, concatenatedComponentsId), false);
        }
    }

    protected List<Entry<String, List<Component>>> renderConcatenatedComponentsText(Package pack, Collection<Component> components) {
        var opts = options.getConcatenateComponents();
        var result = getRowsCols(opts.getRows(), opts.getColumns(), components.size());
        var tableParts = splitOnTableParts(result.columns, result.rows, new ArrayList<>(components));
        return renderTables(result.columns, result.rows, tableParts, component -> component != null ? component.getName() : null);
    }

    protected <T> List<Entry<String, List<T>>> renderTables(int columns, int rows, List<List<T>> tableParts, Function<T, String> stringConverter) {
        return tableParts.stream().map(ordered -> entry(renderTable(columns, rows, ordered, stringConverter), ordered)).collect(toList());
    }

    private <T> String renderTable(int columns, int rows, List<T> ordered, Function<T, String> stringConverter) {
        var result = new StringBuilder();
        var cells = new String[columns];
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < columns; column++) {
                var i = row + column * rows;
                var cellVal = i < ordered.size() ? ordered.get(i) : null;
                var string = stringConverter.apply(cellVal);
                cells[column] = string == null || string.isEmpty() ? " " : string;
            }

            var tableRow = cells.length == 1 && " ".equals(cells[0]) ? null : renderTableRow(true, cells);
            if (tableRow != null) {
                if (result.length() != 0) {
                    result.append("\\n\\\n");
                }
                result.append(tableRow);
            }
        }
        return result.toString();
    }

    protected String renderTableRow(boolean space, String[] cells) {
        return TABLE_TRANSPARENT + "|" + renderTableCells(space, cells) + "|";
    }

    protected void printConcatenatedInterfaces(IndentStringAppender out, String parentId, Map<Interface, List<Component>> interfaces) {
        if (interfaces == null || interfaces.isEmpty()) {
            return;
        }
        var renderedParts = renderConcatenatedInterfacesText(interfaces);
        var part = 0;
        for (var renderedPart : renderedParts) {
            var text = renderedPart.getKey();
            var partInterfaces = renderedPart.getValue();
            var concatenatedId = renderedParts.size() == 1 ? getElementId(parentId, "interfaces") : getElementId(parentId, "interfaces", String.valueOf(++part));

            checkUniqueId(concatenatedId, "interfaces of " + parentId);
            out.append(format("collections \"%s\" as %s\n", text, concatenatedId), false);

            for (var partInt : partInterfaces) {
                var anInterface = partInt.getKey();
                var components = partInt.getValue();
                var interfaceId = getInterfaceId(anInterface);
                //todo may be deleted
                concatenatedInterfaces.put(interfaceId, concatenatedId);
                printInterfaceReferences(out, anInterface, concatenatedId, components);
            }
        }
    }

    protected List<Entry<String, List<Entry<Interface, List<Component>>>>> renderConcatenatedInterfacesText(Map<Interface, List<Component>> interfaces) {
        var opts = options.getConcatenateInterfaces();
        var result = getRowsCols(opts.getRows(), opts.getColumns(), interfaces.size());

        var tableParts = splitOnTableParts(result.columns, result.rows, new ArrayList<>(interfaces.entrySet()));
        return renderTables(result.columns, result.rows, tableParts, e -> ofNullable(e).map(Entry::getKey).map(this::toTableCell).orElse(null));
    }

    protected String toTableCell(Interface anInterface) {
        var interfaceName = anInterface.getName();
        var core = anInterface.getCore();
        if (interfaceName instanceof HttpMethod) {
            return toTableCell((HttpMethod) interfaceName);
        } else if (core instanceof HttpMethod) {
            return toTableCell((HttpMethod) core);
        }
        return renderTableCells(anInterface.getName());
    }

    protected String toTableCell(HttpMethod httpMethod) {
        var method = httpMethod.getMethod();
        var path = httpMethod.getPath();
        return renderTableCells("<r>" + method + ": ", path);
    }

    protected String renderTableCells(CharSequence... cells) {
        return renderTableCells(false, cells);
    }

    protected String renderTableCells(boolean space, CharSequence... cells) {
        return Stream.of(cells).map(c -> c == null ? "" : c.toString()).map(s -> s.isEmpty() ? " " : s).reduce("", (l, r) -> (l.isEmpty() ? "" : l + (space ? " " : "") + "|") + r);
    }

    protected Stream<Package> mergeSubPack(Package pack) {
        var packComponents = pack.getComponents();
        var subPackages = pack.getPackages();
        return (packComponents == null || packComponents.isEmpty()) && subPackages.size() == 1 ? subPackages.stream().map(subPack -> subPack.toBuilder().name(getElementId(pack.getName(), subPack.getName())).build()).flatMap(this::mergeSubPack) : Stream.of(pack);
    }

    protected String getElementId(String... parts) {
        var concat = Stream.of(parts).filter(Objects::nonNull).reduce((parent, id) -> (!parent.isEmpty() ? parent + "." : "") + id).orElse(null);
        return concat != null ? plantUmlAlias(concat) : null;
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
        return distinctPackages(null, components.stream().map(this::getComponentPackage)).values().stream().flatMap(this::mergeSubPack).collect(toList());
    }

    protected Package getComponentPackage(Component component) {
        var componentPath = component.getPath();

        var reversePathBuilders = reverse(asList(componentPath.split("\\."))).stream().map(packageName -> Package.builder().name(packageName)).collect(toList());

        reversePathBuilders.stream().findFirst().ifPresent(packageBuilder -> packageBuilder.components(singletonList(component)));

        return reversePathBuilders.stream().reduce((l, r) -> {
            var lPack = l.build();
            r.packages(singletonList(lPack));
            return r;
        }).map(Package.PackageBuilder::build).orElse(Package.builder().name(componentPath).components(singletonList(component)).build());
    }

    protected void printComponentReferences(IndentStringAppender out, Component component) {
        if (!printedComponents.contains(component)) {
            return;
        }

        Collection<Component> dependencies = component.getDependencies();
        if (dependencies != null) {
            var componentComparator = options.getSort().getDependencies();
            if (componentComparator != null) {
                dependencies = dependencies.stream().sorted(componentComparator).collect(toList());
            }
            for (var dependency : dependencies) {
                printComponentReference(out, component, dependency);
            }
        }
    }

    protected void printComponentReference(IndentStringAppender out, Component component, Component dependency) {
        var check = checkComponentName(component);
        var dependencyName = checkComponentName(dependency).getComponentName();
        if (canRenderRelation(check.getComponentName(), dependencyName)) {
            var finalComponentName = check.getComponentName();
            printedConcatenatedComponentRelations.computeIfAbsent(finalComponentName, k -> new HashSet<>()).add(dependencyName);
            var arrow = renderComponentRelationArrow(component, dependency);
            out.append(renderComponentRelation(finalComponentName, arrow, dependencyName));
        }
    }

    protected boolean canRenderRelation(String componentName, String dependencyName) {
        return !(options.reduceDuplicatedElementRelations && isAlreadyPrinted(componentName, dependencyName));
    }

    protected boolean isAlreadyPrinted(String finalComponentName, String dependencyName) {
        return printedConcatenatedComponentRelations.getOrDefault(finalComponentName, Set.of()).contains(dependencyName);
    }

    protected ComponentNameCheck checkComponentName(Component component) {
        var componentName = component.getName();
        var finalComponentName = getComponentName(componentName);
        var concatenated = !finalComponentName.equals(componentName);
        return new ComponentNameCheck(finalComponentName, concatenated);
    }

    protected String getComponentName(String name) {
        var concatenatedName = concatenatedComponents.get(name);
        return concatenatedName != null ? concatenatedName : name;
    }

    protected String renderComponentRelationArrow(Component component, Component dependency) {
//        var componentInterfaces = component.getInterfaces();
//        var dependencyInterfaces = dependency.getInterfaces();
//        var noInterfaces = (componentInterfaces == null || componentInterfaces.isEmpty()) &&
//                (dependencyInterfaces == null || dependencyInterfaces.isEmpty());
//        return noInterfaces ? SHORT_ARROW : MIDDLE_ARROW;
        return SHORT_ARROW;
    }

    private String renderComponentRelation(String componentName, String arrow, String dependencyName) {
        return format("%s %s %s\n", plantUmlAlias(componentName), arrow, plantUmlAlias(dependencyName));
    }

    @Getter
    @RequiredArgsConstructor
    public enum HtmlMethodsGroupBy {
        path, component
    }

    @Getter
    @RequiredArgsConstructor
    public enum DirectionGroup {
        input, output, internal
    }

    @Getter
    @RequiredArgsConstructor
    public enum UnionBorder {
        rectangle, pack("package"), cloud, queue, file, folder, frame, database, together(false);

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
    private static class RowsCols {
        public final int rows;
        public final int columns;


    }

    @Data
    public static class ComponentNameCheck {
        private final String componentName;
        private final boolean concatenated;
    }

    @Data
    @Builder
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class ConcatenatedComponentGroup {
        @Builder.Default
        Collection<Component> concatenation = Set.of();
        @Builder.Default
        Collection<Component> distinct = Set.of();
    }

    @Data
    @Builder
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class ConcatenatedInterfacesGroup {
        @Builder.Default
        Map<Interface, List<Component>> concatenation = Map.of();
        @Builder.Default
        Map<Interface, List<Component>> distinct = Map.of();
    }

    @Data
    @Builder(toBuilder = true)
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class UnionStyle {
        UnionBorder unionBorder;
        String style;

        public static UnionStyle newUnionStyle(UnionBorder unionBorder) {
            return builder().unionBorder(unionBorder).build();
        }
    }

    @Data
    @Builder(toBuilder = true)
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class Options {
        public static final Options DEFAULT = Options.builder().build();
        String head, bottom;
        @Builder.Default
        boolean reduceDuplicatedElementRelations = true;
        @Builder.Default
        boolean printPackageBorder = true;
        //debug option
        @Builder.Default
        boolean checkUniqueViolation = true;
        @Builder.Default
        Map<String, List<String>> idCharReplaces = DEFAULT_ESCAPES;
        @Builder.Default
        Function<Direction, DirectionGroup> directionGroup = Options::defaultDirectionName;
        @Builder.Default
        Function<DirectionGroup, UnionStyle> directionGroupAggregate = directionGroup -> newAggregateStyle(cloud, LINE_DOTTED_LINE_GRAY);
        @Builder.Default
        BiFunction<DirectionGroup, Type, UnionStyle> interfaceAggregate = (directionGroup, type) -> newAggregateStyle(getAggregate(type));
        @Builder.Default
        BiFunction<DirectionGroup, Type, UnionStyle> interfaceSubgroupAggregate = (directionGroup, type) -> newAggregateStyle(frame, LINE_DOTTED_TEXT_GRAY);
        @Builder.Default
        Function<String, UnionStyle> packagePathAggregate = path -> newAggregateStyle(pack, LINE_DOTTED_TEXT_GRAY);
        @Builder.Default
        BiPredicate<DirectionGroup, Type> supportGroups = (directionName, type) -> Set.of(http, jms, ws).contains(type);
        @Builder.Default
        Function<DirectionGroup, HtmlMethodsGroupBy> htmlMethodsGroupBy = directionGroup -> path;
        @Builder.Default
        ConcatenateComponentsOptions concatenateComponents = ConcatenateComponentsOptions.DEFAULT;
        @Builder.Default
        ConcatenateInterfacesOptions concatenateInterfaces = ConcatenateInterfacesOptions.DEFAULT;

        @Builder.Default
        Sort sort = Sort.builder().build();

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

        public static DirectionGroup defaultDirectionName(Direction direction) {
            switch (direction) {
                case in:
                    return input;
                case out:
                case outIn:
                    return output;
                case undefined:
                    return null;
                default:
                    return internal;
            }
        }

        @Data
        @Builder(toBuilder = true)
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        public static class Sort {
            Comparator<Component> components = (o1, o2) -> compareNullable(o1.getPath(), o2.getPath());
            Comparator<Component> dependencies = (o1, o2) -> compareNullable(o1.getName(), o2.getName());
            Comparator<Interface> interfaces = (o1, o2) -> {
                var name1 = o1.getName();
                var name2 = o2.getName();
                return name1 instanceof Comparable<?> && name2 instanceof Comparable<?> ? ((Comparable) name1).compareTo(name2) : compareNullable(name1 != null ? name1.toString() : null, name2 != null ? name2.toString() : null);
            };
        }

        @Data
        @Builder(toBuilder = true)
        @FieldDefaults(makeFinal = true, level = PRIVATE)

        public static class ConcatenateComponentsOptions {
            public static ConcatenateComponentsOptions DEFAULT = ConcatenateComponentsOptions.builder().build();
            @Builder.Default
            Integer moreThan = 3;
            int columns;
            @Builder.Default
            int rows = 20;

            @Builder.Default
            boolean ignoreInterfaceRelated = true;
        }

        @Data
        @Builder(toBuilder = true)
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        public static class ConcatenateInterfacesOptions {
            public static ConcatenateInterfacesOptions DEFAULT = ConcatenateInterfacesOptions.builder().build();
            @Builder.Default
            Integer moreThan = 3;
            int columns;
            @Builder.Default
            int rows = 8;
        }
    }
}
