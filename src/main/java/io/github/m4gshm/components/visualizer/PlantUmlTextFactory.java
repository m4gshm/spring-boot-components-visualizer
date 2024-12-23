package io.github.m4gshm.components.visualizer;

import com.google.common.base.Strings;
import io.github.m4gshm.components.visualizer.model.*;
import io.github.m4gshm.components.visualizer.model.Package;
import io.github.m4gshm.components.visualizer.model.Interface.Type;
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
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.reverse;
import static io.github.m4gshm.components.visualizer.PlantUmlTextFactory.Direction.*;
import static io.github.m4gshm.components.visualizer.PlantUmlTextFactory.HtmlMethodsGroupBy.path;
import static io.github.m4gshm.components.visualizer.PlantUmlTextFactory.Options.newUnionStyle;
import static io.github.m4gshm.components.visualizer.PlantUmlTextFactory.Union.newUnion;
import static io.github.m4gshm.components.visualizer.PlantUmlTextFactory.UnionBorder.*;
import static io.github.m4gshm.components.visualizer.PlantUmlTextFactoryUtils.*;
import static io.github.m4gshm.components.visualizer.UriUtils.PATH_DELIMITER;
import static io.github.m4gshm.components.visualizer.Utils.toLinkedHashSet;
import static io.github.m4gshm.components.visualizer.Utils.warnDuplicated;
import static io.github.m4gshm.components.visualizer.model.HttpMethodsGroup.makeGroupsHierarchyByHttpMethodUrl;
import static io.github.m4gshm.components.visualizer.model.Interface.Type.*;
import static io.github.m4gshm.components.visualizer.model.StorageEntity.Engine.jpa;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.concat;
import static lombok.AccessLevel.PRIVATE;

@Slf4j
public class PlantUmlTextFactory implements SchemaFactory<String> {

    public static final String INDENT = "  ";
    public static final Map<String, List<String>> DEFAULT_ESCAPES = Map.of(
            "", List.of(" "), ".",
            List.of("-", "+", PATH_DELIMITER, ":", "?", "=", ",", "&", "*", "$", "{", "}", "(", ")", "[", "]",
                    "#", "\"", "'", "%"));
    public static final String LINE_DOTTED_TEXT_GRAY = "line.dotted;text:gray";
    public static final String LINE_DOTTED_LINE_GRAY = "line.dotted;line:gray;";
    public static final String LINE_DOTTED = "line.dotted;";
    public static final String SHORT_ARROW = "..>";
    public static final String MIDDLE_ARROW = "....>";
    public static final String LONG_ARROW = "......>";
    public static final String TABLE_TRANSPARENT = "<#transparent,transparent>";
    public static final Package NO_PACKAGE = Package.builder().build();

    protected final String applicationName;
    @Getter
    protected final Options options;
    protected final Map<String, String> concatenatedComponents = new HashMap<>();
    protected final Map<String, String> concatenatedInterfaces = new HashMap<>();
    protected final Map<String, Set<String>> printedConcatenatedComponentRelations = new HashMap<>();
    protected final Map<String, Set<String>> printedInterfaceRelations = new HashMap<>();
    protected final Map<String, Object> uniques = new HashMap<>();
    protected final Set<Component> printedComponents = new LinkedHashSet<>();

    public PlantUmlTextFactory(String applicationName, Options options) {
        this.applicationName = applicationName;
        this.options = options != null ? options : Options.DEFAULT;
    }

    private static String getStorageEntityPackageName(Interface anInterface) {
        var core = anInterface.getCore();
        if (core instanceof StorageEntity) {
            var storage = (StorageEntity) core;
            return storage.getEntityType().getPackage().getName();
        } else {
            return "";
        }
    }

    public static <T> int ignoreCaseComparator(T o1, T o2, Function<T, CharSequence> getter) {
        return compareNullable(o1, o2, getter.andThen(
                        charSequence -> charSequence != null ? charSequence.toString() : null),
                (s, str) -> s != null ? s.compareToIgnoreCase(str) : str == null ? 0 : -1);
    }

    private static <T, P> int compareNullable(T o1, T o2, Function<T, P> getter, Comparator<P> comparator) {
        var name1 = ofNullable(o1).map(getter).orElse(null);
        var name2 = ofNullable(o2).map(getter).orElse(null);
        return Utils.compareNullable(name1, name2, comparator);
    }

    public static Package extractPackage(Component component) {
        var componentPath = component.getPath();
        if (componentPath == null) {
            return NO_PACKAGE;
        }
        var reversePathBuilders = reverse(asList(componentPath.split("\\."))).stream()
                .map(packageName -> Package.builder().name(packageName)).collect(toList());

        reversePathBuilders.stream().findFirst().ifPresent(packageBuilder ->
                packageBuilder.components(singletonList(component)));

        return reversePathBuilders.stream().reduce((l, r) -> {
            var lPack = l.build();
            r.packages(singletonList(lPack));
            return r;
        }).map(Package.PackageBuilder::build).orElse(Package.builder().name(componentPath)
                .components(singletonList(component)).build());
    }

    public String create(Components components, Options options) {
        return new PlantUmlTextFactory(this.applicationName, options).create(components);
    }

    @Override
    public String create(Components components) {
        var out = new IndentStringAppender(new StringBuilder(), INDENT);

        out.append("@startuml\n");
        var head = options.getHead();
        if (head != null) {
            out.append(head);
            out.append("\n");
        }

        if (options.isRemoveUnlinked()) {
            out.append("remove @unlinked\n");
        }

        var optionsSort = options.getSort();
        var componentStream = components.getComponents().stream();
        if (options.ignoreSprintConfigurations) {
            var configGrouped = componentStream.collect(groupingBy(Component::isConfiguration));
            componentStream = configGrouped.getOrDefault(false, List.of()).stream();
            var configNames = configGrouped.getOrDefault(true, List.of())
                    .stream().map(Component::getName).collect(toSet());
            componentStream = componentStream.map(c -> {
                var dependencies = c.getDependencies();
                var withoutConfigs = dependencies != null ? dependencies.stream()
                        .filter(d -> !configNames.contains(d.getName())).collect(toLinkedHashSet()) : null;
                return withoutConfigs != null ? c.toBuilder().dependencies(withoutConfigs).build() : c;
            });
        }
        var componentComparator = optionsSort.getComponents();
        if (componentComparator != null) {
            componentStream = componentStream.sorted(componentComparator);
        }
        var sorted = componentStream.collect(toList());
        printBody(out, sorted);
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

    protected void printBody(IndentStringAppender out, Collection<Component> components) {
        var packages = toPackagesHierarchy(components);
        printPackages(out, packages, null);
        for (var component : components) {
            printComponentReferences(out, component);
        }
        printInterfaces(out, components);
    }

    protected void printInterfaces(IndentStringAppender out, Collection<Component> components) {
        var groupedInterfaces = getGroupedInterfaces(components);
        printGroupedInterfaces(out, groupedInterfaces);
    }

    protected void printGroupedInterfaces(IndentStringAppender out, Collection<InterfaceGroup> groupedInterfaces) {
        if (groupedInterfaces != null) for (var interfaceGroup : groupedInterfaces) {
            var key = interfaceGroup.getKey();
            var interfaces = interfaceGroup.getInterfaces();
            var hasInterfaces = interfaces != null && !interfaces.isEmpty();
            var groups = interfaceGroup.getGroups();
            var hasGroups = groups != null && !groups.isEmpty();
            if (hasInterfaces || hasGroups) {
                Runnable printContent = () -> {
                    if (hasInterfaces) printInterfaces(out, key, interfaces);
                    if (hasGroups) printGroupedInterfaces(out, groups);
                };

                var unions = getUnions(key);
                for (var iterator = unions.listIterator(unions.size()); iterator.hasPrevious(); ) {
                    var union = iterator.previous();
                    var pervPrintContent = printContent;
                    printContent = () -> printUnion(out, union, pervPrintContent);
                }
                printContent.run();
            }
        }
    }

    protected List<Union> getUnions(InterfaceGroup.Key key) {
        var directionGroupTypeId = getElementId(key);
        var component = key.getComponent();
        var type = key.getType();
        var direction = key.getDirection();
        var result = component != null
                ? newUnion(getElementId(directionGroupTypeId, component.getName(), "interfaces", key.getUntyped()),
                component.getName(), getComponentGroupUnionStyle(key))
                : type != null ? newUnion(directionGroupTypeId, type.getFullName(), getInterfaceTypeUnionStyle(key))
                : direction != null ? newUnion(getElementId(direction.name(), key.getUntyped()), direction.name(),
                getDirectionGroupUnionStyle(key))
                : null;
        var unions = new ArrayList<Union>();
        unions.add(result);
        return unions;
    }

    protected UnionStyle getDirectionGroupUnionStyle(InterfaceGroup.Key key) {
        return newUnionStyle(cloud, LINE_DOTTED_LINE_GRAY);
    }

    protected UnionStyle getInterfaceTypeUnionStyle(InterfaceGroup.Key key) {
        return options.interfaceTypeUnionStyle.apply(key);
    }

    protected UnionStyle getComponentGroupUnionStyle(InterfaceGroup.Key key) {
        return options.componentGroupUnionStyle.apply(key);
    }

    protected UnionStyle getInterfaceSubgroupsUnionStyle(Direction directionGroup, Type type) {
        return options.getInterfaceSubgroupsUnionStyle().apply(directionGroup, type);
    }

    protected List<InterfaceGroup> getGroupedInterfaces(Collection<Component> components) {
        var interfaceComparator = options.getSort().getInterfaces();
        var componentsByInterfaces = components.stream()
                .flatMap(component -> Stream.ofNullable(component.getInterfaces())
                        .flatMap(s -> interfaceComparator != null ? s.stream().sorted(interfaceComparator) : s.stream())
                        .map(anInterface -> entry(anInterface, component)))
                .collect(groupingBy(Entry::getKey, LinkedHashMap::new, mapping(Entry::getValue, toList())));

        var rootGroup = InterfaceGroup.builder()
                .key(InterfaceGroup.Key.builder().build())
                .interfaces(componentsByInterfaces)
                .build();

        return Stream.of(rootGroup).map(group -> options.groupByDirection
                ? groupByDirection(group)
                : options.groupByInterfaceType
                ? groupByInterfaceType(group)
                : groupByComponent(group, options.groupedByComponent)
        ).collect(toList());
    }

    protected InterfaceGroup groupByDirection(InterfaceGroup group) {
        var interfaces = group.getInterfaces();

        var groupedByDirection = interfaces.entrySet().stream().map(e -> {
            var key = group.getKey();
            var anInterface = e.getKey();
            var newKey = key.toBuilder().direction(mapDirection(anInterface.getDirection())).build();
            return Map.entry(newKey, e);
        }).collect(groupingBy(Entry::getKey, LinkedHashMap::new, mapping(Entry::getValue,
                toMap(Entry::getKey, Entry::getValue, warnDuplicated(), LinkedHashMap::new)))
        );

        var directionGroups = groupedByDirection.entrySet();

        var newGroups = directionGroups.stream().map(e -> {
            var interfaceGroup = InterfaceGroup.builder().key(e.getKey()).interfaces(e.getValue()).build();
            return options.groupByInterfaceType ? groupByInterfaceType(interfaceGroup) : groupByComponent(interfaceGroup);
        }).sorted(options.getSort().getInterfaceGroups()).collect(toList());

        return group.toBuilder().interfaces(null).groups(newGroups).build();
    }

    private InterfaceGroup groupByInterfaceType(InterfaceGroup group) {
        var groupedMap = new LinkedHashMap<InterfaceGroup.Key, Map<Interface, List<Component>>>();
        var interfaces = group.getInterfaces();
        for (var anInterface : interfaces.keySet()) {
            var key = group.getKey();
            var newKey = key.toBuilder()
                    .direction(mapDirection(anInterface.getDirection()))
                    .type(anInterface.getType())
                    .build();
            groupedMap.computeIfAbsent(
                    newKey, k -> new LinkedHashMap<>()
            ).put(anInterface, interfaces.get(anInterface));
        }
        var subGroups = groupedMap.entrySet().stream()
                .map(e -> groupByComponent(InterfaceGroup.builder().key(e.getKey()).interfaces(e.getValue()).build()))
                .sorted(options.getSort().getInterfaceGroups())
                .collect(toList());
        return group.toBuilder().interfaces(null).groups(subGroups).build();
    }

    protected InterfaceGroup groupByComponent(InterfaceGroup group) {
        return groupByComponent(group, options.groupedByComponent);
    }

    protected InterfaceGroup groupByComponent(InterfaceGroup group, Collection<Type> include) {
        var interfacesByComponent = group.getInterfaces().entrySet().stream().flatMap(entry ->
                entry.getValue().stream().map(component -> entry(component, entry.getKey()))
        ).collect(groupingBy(Entry::getKey, LinkedHashMap::new, mapping(Entry::getValue, toList())));
        var excluded = new LinkedHashMap<Interface, List<Component>>();
        var groups = interfacesByComponent.entrySet().stream().map(e -> {
            var component = e.getKey();
            var interfaces = e.getValue();
            var key = group.getKey();
            var includedAndExcluded = interfaces.stream().collect(groupingBy(i -> include.contains(i.getType()),
                    LinkedHashMap::new, toList()));
            var included = includedAndExcluded.getOrDefault(true, List.of());
            var others = includedAndExcluded.getOrDefault(false, List.of());
            for (var anInterface : others) {
                excluded.computeIfAbsent(anInterface, k -> new ArrayList<>()).add(component);
            }
            return !included.isEmpty() ? InterfaceGroup.builder()
                    .key(key.toBuilder().component(component).build())
                    .interfaces(included.stream().map(anInterface -> entry(anInterface.toBuilder()
                                    .id(getElementId(component.getName(), anInterface.getId())).build(), component))
                            .collect(groupingBy(Entry::getKey, LinkedHashMap::new, mapping(Entry::getValue, toList()))))
                    .build() : null;
        }).filter(Objects::nonNull).sorted(options.getSort().getInterfaceGroups()).collect(toList());
        return group.toBuilder().interfaces(excluded).groups(groups).build();
    }

    protected Direction mapDirection(Interface.Direction direction) {
        return options.mapDirection.apply(direction);
    }

    protected void printInterfaces(IndentStringAppender out, InterfaceGroup.Key key,
                                   Map<Interface, List<Component>> interfaceRelations) {
        if (interfaceRelations == null || interfaceRelations.isEmpty()) {
            return;
        }
        var type = key.getType();
        if (type == http) {
            //merge by url parts
            var httpMethods = extractHttpMethodsFromInterfaces(interfaceRelations);
            var finalGroup = groupByUrlParts(httpMethods);
            var unionStyle = getInterfaceSubgroupsUnionStyle(key.getDirection(), type);
            printHttpMethodGroup(out, key, getElementId(key), finalGroup, unionStyle, interfaceRelations, httpMethods);
        } else if (type == storage) {
            Map<String, Map<Interface, List<Component>>> groupedByPackage = interfaceRelations.entrySet().stream()
                    .map(e -> entry(getStorageEntityPackageName(e.getKey()), e)).collect(groupingBy(Entry::getKey,
                            TreeMap::new, mapping(Entry::getValue, toMap(Entry::getKey, Entry::getValue,
                                    warnDuplicated(), LinkedHashMap::new))));
            var commonPackPrefix = groupedByPackage.keySet().stream().reduce(Strings::commonPrefix).orElse("");
            if (!commonPackPrefix.isEmpty()) {
                groupedByPackage = groupedByPackage.entrySet().stream().collect(toMap(e -> e.getKey().substring(commonPackPrefix.length()),
                        e -> {
                            var value = e.getValue();
                            var commonInterfacePrefix = value.size() > 1
                                    ? value.keySet().stream()
                                    .map(Interface::getName)
                                    .collect(toList()).stream()
                                    .reduce(Strings::commonPrefix).orElse("")
                                    : commonPackPrefix;
                            return value.entrySet().stream().map(ee -> {
                                var anInterface = ee.getKey();
                                var interfaceName = anInterface.getName();

                                var shortName = interfaceName.subSequence(commonInterfacePrefix.length(), interfaceName.length());
                                return entry(anInterface.toBuilder().name(shortName).build(), ee.getValue());
                            }).collect(toMap(Entry::getKey, Entry::getValue, warnDuplicated(), LinkedHashMap::new));
                        }, warnDuplicated(), LinkedHashMap::new));
            }

            if (!groupedByPackage.isEmpty()) {
                groupedByPackage.forEach((packageName, interfaceCollectionMap) -> {
                    var entitiesPackId = getElementId(getElementId(key), packageName);
                    var style = newUnionStyle(packageName.isEmpty() ? rectangle : folder, LINE_DOTTED_LINE_GRAY);
                    printUnion(out, newUnion(entitiesPackId, packageName, style), () -> {
                        printInterfaces(out, entitiesPackId, interfaceCollectionMap);
                    });
                });
            } else {
                printInterfaces(out, getElementId(key), interfaceRelations);
            }
        } else {
            printInterfaces(out, getElementId(key), interfaceRelations);
        }
    }

    protected void printInterfaces(IndentStringAppender out, String parentId,
                                   Map<Interface, List<Component>> interfaceRelations) {
        var interfaces = groupConcatenatedInterfaces(interfaceRelations);
        printConcatenatedInterfaces(out, parentId, interfaces.getConcatenation());
        interfaces.getDistinct().forEach((anInterface, components) -> printInterface(out, anInterface, components));
    }

    protected ConcatenatedInterfacesGroup groupConcatenatedInterfaces(Map<Interface, List<Component>> interfaceRelations) {
        var concatenateInterfacesMoreThan = options.getConcatenateInterfaces().getMoreThan();
        var concatenate = concatenateInterfacesMoreThan != null && interfaceRelations.size() > concatenateInterfacesMoreThan;
        var builder = ConcatenatedInterfacesGroup.builder();
        if (concatenate) {
            builder.concatenation(interfaceRelations);
        } else {
            builder.distinct(interfaceRelations);
        }
        return builder.build();
    }

    protected void printUnion(IndentStringAppender out, Union union, Runnable internal) {
        if (union != null) {
            printUnion(out, union.getName(), union.getId(), union.getStyle(), internal);
        } else {
            internal.run();
        }
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
        var reducedSubGroups = Stream.ofNullable(subGroups).flatMap(m -> m.entrySet().stream()).map(e -> {
            var value = e.getValue();
            var reduced = reduceUrlBasedHttpMethodGroups(value);
            var key = reduced.getName();
            return entry(key, reduced);
        }).filter(e -> {
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
        final Runnable printRoutine = () -> {
            printPackageComponents(out, pack, parentPackage);
            var packages = pack.getPackages();
            if (packages != null) printPackages(out, packages, pack);
        };
        var printBorder = isPrintBorder(pack, parentPackage);
        if (printBorder) {
            var style = options.getPackagePathUnion().apply(pack.getPath());
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
        var concatenatePackageComponents = options.concatenateComponents;
        var moreThan = concatenatePackageComponents.moreThan;
        var compress = moreThan != null && components != null && components.size() > moreThan;
        if (compress) {
            var componentsConcatenationCondition = concatenatePackageComponents.componentsConcatenationCondition;
            if (componentsConcatenationCondition != null) {
                return newConcatenatedComponentGroup(components, componentsConcatenationCondition);
            } else {
                return ConcatenatedComponentGroup.builder().concatenation(components).build();
            }
        } else {
            return ConcatenatedComponentGroup.builder().distinct(components).build();
        }
    }

    protected ConcatenatedComponentGroup newConcatenatedComponentGroup(
            Collection<Component> components, Predicate<Component> concatenationCondition
    ) {
        var grouped = components.stream()
                .collect(groupingBy(concatenationCondition::test, LinkedHashMap::new, toLinkedHashSet()));
        var distinct = grouped.get(false);
        var concatenation = grouped.get(true);
        return ConcatenatedComponentGroup.builder()
                .distinct(distinct != null ? distinct : Set.of())
                .concatenation(concatenation != null ? concatenation : Set.of())
                .build();
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

            var components = Stream.of(l.getComponents(), r.getComponents())
                    .filter(Objects::nonNull).flatMap(Collection::stream).collect(toList());

            var distinctPackages = distinctPackages(getElementId(parentPath, l.getName()),
                    concat(ofNullable(l.getPackages()).orElse(emptyList()).stream(), ofNullable(r.getPackages()).orElse(emptyList()).stream()));
            return l.toBuilder().components(components).packages(copyOf(distinctPackages.values())).build();
        }, LinkedHashMap::new));
    }

    protected void printHttpMethodGroup(IndentStringAppender out, InterfaceGroup.Key key, String parentGroupId, HttpMethodsGroup group,
                                        UnionStyle style, Map<Interface, List<Component>> interfaceComponentLink,
                                        Map<HttpMethod, Interface> httpMethods) {
        var methods = group.getMethods();
        var subGroups = group.getGroups();
        var groupId = getElementId(parentGroupId, group.getName());
        if ((subGroups == null || subGroups.isEmpty()) && methods != null && methods.size() == 1) {
            printInterfaceAndSubgroups(out, key, groupId, group, style, interfaceComponentLink, httpMethods);
        } else {
            printUnion(out, group.getName(), groupId, style, () -> printInterfaceAndSubgroups(out, key, groupId, group,
                    style, interfaceComponentLink, httpMethods));
        }
    }

    protected void printInterfaceAndSubgroups(IndentStringAppender out, InterfaceGroup.Key key, String groupId, HttpMethodsGroup group,
                                              UnionStyle style, Map<Interface, List<Component>> interfaceComponentLink,
                                              Map<HttpMethod, Interface> httpMethods) {
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
            printHttpMethodGroup(out, key, groupId, subGroup, style, interfaceComponentLink, httpMethods);
        }
    }

    protected void printInterface(IndentStringAppender out, Interface anInterface, Collection<Component> components) {
        var interfaceId = getInterfaceId(anInterface);
        checkUniqueId(interfaceId, anInterface);
        out.append(renderInterface(anInterface, interfaceId));

        printInterfaceCore(out, anInterface.getCore(), interfaceId);
        printInterfaceReferences(out, anInterface, interfaceId, false, components);
    }

    protected String renderInterface(Interface anInterface, String interfaceId) {
        return format(renderAs(anInterface.getType()) + " \"%s\" as %s\n", renderInterfaceName(anInterface), interfaceId);
    }

    protected void printInterfaceReferences(IndentStringAppender out, Interface anInterface, String interfaceId,
                                            boolean concatenated, Collection<Component> components) {
        for (var component : components) {
            printInterfaceReference(out, anInterface, interfaceId, concatenated, component);
        }
    }

    protected String renderInterfaceName(Interface anInterface) {
        var name = anInterface.getName().toString();
        if (anInterface.getType() == storage) {
            var lasted = name.lastIndexOf(".");
            return lasted > 0 ? name.substring(lasted + 1) : name;
        }
        return name;
    }

    protected void printInterfaceCore(IndentStringAppender out, Object core, String interfaceId) {
        if (core instanceof StorageEntity) {
            out.append(renderStorage((StorageEntity) core, interfaceId));
        }
    }

    protected String renderStorage(StorageEntity storage, String interfaceId) {
        var storedTo = storage.getStoredTo();
        var tables = storedTo.stream().reduce("", (l, r) -> (l.isBlank() ? "" : l + "\n") + r);
        var noteId = getElementId(interfaceId, "table_name");
        var caption = storage.getEngine() == jpa ? "table" : "collection";
        if (storedTo.size() > 1) {
            caption += "s";
        }
        return format("note \"%1$s: %2$s\" as %3$s\n%3$s .. %4$s\n", caption, tables, noteId, interfaceId);
    }

    protected void printInterfaceReference(IndentStringAppender out, Interface anInterface,
                                           String interfaceId, boolean concatenatedInterface, Component component) {
        var type = anInterface.getType();
        var componentName = component.getName();
        if (!printedComponents.contains(component)) {
            return;
        }
        var concatenatedComponentId = getComponentName(componentName);
        var concatenatedComponent = !componentName.equals(concatenatedComponentId);
        var componentId = concatenatedComponent ? concatenatedComponentId : plantUmlAlias(componentName);
        var printed = options.reduceDuplicatedElementRelations &&
                printedInterfaceRelations.getOrDefault(componentId, Set.of()).contains(interfaceId);
        if (printed) {
            return;
        }

        var direction = anInterface.getDirection();
        switch (direction) {
            case in:
                out.append(renderIn(type, interfaceId, concatenatedInterface, componentId, concatenatedComponent));
                break;
            case out:
                out.append(renderOut(type, interfaceId, concatenatedInterface, componentId, concatenatedComponent));
                break;
            case outIn:
                out.append(renderOutIn(type, interfaceId, concatenatedInterface, componentId, concatenatedComponent));
                break;
            case internal:
                out.append(renderInternal(type, interfaceId, concatenatedInterface, componentId, concatenatedComponent));
                break;
            default:
                out.append(renderLink(type, interfaceId, concatenatedInterface, componentId, concatenatedComponent));
        }
        printedInterfaceRelations.computeIfAbsent(componentId, k -> new LinkedHashSet<>()).add(interfaceId);
    }

    protected String renderOut(Type type, String interfaceId, boolean concatenatedInterface,
                               String componentId, boolean concatenatedComponent) {
        return format((type == jms ? "%s ....> %s" : (concatenatedInterface ? "%s ....(0 %s" : "%s ....( %s")) + "\n", componentId, interfaceId);
    }

    protected String renderOutIn(Type type, String interfaceId, boolean concatenatedInterface,
                                 String componentId, boolean concatenatedComponent) {
        return format("%1$s ....> %2$s\n%1$s <.... %2$s#line.dotted\n", componentId, interfaceId);
    }

    protected String renderIn(Type type, String interfaceId, boolean concatenatedInterface,
                              String componentId, boolean concatenatedComponent) {
        return format("%s %s)....> %s\n", interfaceId, concatenatedInterface ? "0" : "", componentId);
    }

    protected String renderInternal(Type type, String interfaceId, boolean concatenatedInterface,
                                    String componentId, boolean concatenatedComponent) {
        return format("%s .. %s\n", interfaceId, componentId);
    }

    protected String renderLink(Type type, String interfaceId, boolean concatenatedInterface,
                                String componentId, boolean concatenatedComponent) {
        return format("%s .... %s\n", interfaceId, componentId);
    }

    protected Package populatePath(String parentPath, Package pack) {
        var elementId = getElementId(parentPath, pack.getName());
        return pack.toBuilder().path(elementId).packages(ofNullable(pack.getPackages())
                .orElse(emptyList()).stream().map(p -> populatePath(elementId, p)).collect(toList())).build();
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
            var concatenatedComponentsId = renderedParts.size() == 1
                    ? getElementId(packageId, "components")
                    : getElementId(packageId, "components", String.valueOf(++part));
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
        return renderTables(result.columns, result.rows, tableParts, component -> component != null ? component.getName() : null, null);
    }

    protected <T> List<Entry<String, List<T>>> renderTables(int columns, int rows, List<List<T>> tableParts, Function<T, String> stringConverter, String head) {
        return tableParts.stream().map(ordered -> entry(renderTable(columns, rows, ordered, stringConverter, head), ordered)).collect(toList());
    }

    private <T> String renderTable(int columns, int rows, List<T> ordered, Function<T, String> stringConverter, String head) {
        var result = new StringBuilder();
        var needHeadRodPrint = head != null;
        for (var row = 0; row < rows; row++) {
            if (needHeadRodPrint) {
                var headCells = new String[columns];
                Arrays.fill(headCells, head);
                needHeadRodPrint = false;
                var tableRow = renderTableRow(true, headCells);
                if (tableRow != null) {
                    result.append(tableRow);
                }
            }
            var cells = new String[columns];
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

    protected String renderTableRow(boolean space, String... cells) {
        return TABLE_TRANSPARENT + "|" + renderTableCells(space, cells) + "|";
    }

    protected void printConcatenatedInterfaces(
            IndentStringAppender out, String parentId, Map<Interface, List<Component>> interfaces
    ) {
        if (interfaces == null || interfaces.isEmpty()) {
            return;
        }

        var renderedParts = renderConcatenatedInterfacesText(interfaces);
        var part = 0;
        for (var renderedPart : renderedParts) {
            var text = renderedPart.getKey();
            var partInterfaces = renderedPart.getValue();
            var concatenatedId = renderedParts.size() == 1 ? getElementId(parentId, "interfaces")
                    : getElementId(parentId, "interfaces", String.valueOf(++part));

            checkUniqueId(concatenatedId, concatenatedId);
            out.append(format("collections \"%s\" as %s\n", text, concatenatedId), false);

            for (var partInt : partInterfaces) {
                var anInterface = partInt.getKey();
                var components = partInt.getValue();
                var interfaceId = getInterfaceId(anInterface);

                //todo may be deleted
                concatenatedInterfaces.put(interfaceId, concatenatedId);
                printInterfaceReferences(out, anInterface, concatenatedId, true, components);
            }
        }
    }

    protected List<Entry<String, List<Entry<Interface, List<Component>>>>> renderConcatenatedInterfacesText(
            Map<Interface, List<Component>> interfaces) {
        var opts = options.getConcatenateInterfaces();
        var result = getRowsCols(opts.getRows(), opts.getColumns(), interfaces.size());

        var tableParts = splitOnTableParts(result.columns, result.rows,
                new ArrayList<>(interfaces.entrySet()));
        var head = interfaces.keySet().stream().map(this::toTableHead).filter(Objects::nonNull).findFirst().orElse(null);
        return renderTables(result.columns, result.rows, tableParts, e -> ofNullable(e).map(Entry::getKey)
                .map(this::toTableCell).orElse(null), head);
    }

    protected String toTableHead(Interface anInterface) {
        var core = anInterface.getCore();
        if (core instanceof StorageEntity) {
            return toTableHead((StorageEntity) core);
        }
        return null;
    }

    protected String toTableHead(StorageEntity storageEntity) {
        var engine = storageEntity.getEngine();
        var entity = "Entity";
        var storedTo = engine == jpa ? "Table" : "Collection";
        return renderTableCells("=" + entity + " ", "=" + storedTo);
    }

    protected String toTableCell(Interface anInterface) {
        var interfaceName = anInterface.getName();
        var core = anInterface.getCore();
        if (interfaceName instanceof HttpMethod) {
            return toTableCell((HttpMethod) interfaceName);
        } else if (core instanceof HttpMethod) {
            return toTableCell((HttpMethod) core);
        } else if (core instanceof StorageEntity) {
            return toTableCell((StorageEntity) core);
        }
        return renderTableCells(anInterface.getName());
    }

    protected String toTableCell(StorageEntity storageEntity) {
        var simpleName = storageEntity.getEntityType().getSimpleName();
        var storedTo = storageEntity.getStoredTo().stream().reduce("", (l, r) -> (l.isEmpty() ? "" : l + ", ") + r);
        return renderTableCells(simpleName + " ", "<i>" + storedTo);
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
        return Stream.of(cells).map(c -> c == null ? "" : c.toString()).map(s -> s.isEmpty() ? " " : s)
                .reduce("", (l, r) -> (l.isEmpty() ? "" : l + (space ? " " : "") + "|") + r);
    }

    protected Stream<Package> mergeSubPack(Package pack) {
        var packComponents = pack.getComponents();
        var subPackages = pack.getPackages();
        var concatenateSubPackName = (packComponents == null || packComponents.isEmpty()) && subPackages.size() == 1;
        if (concatenateSubPackName) {
            //remove the pack
            return subPackages.stream().map(subPack -> getRenamedSubPack(pack, subPack)).flatMap(this::mergeSubPack);
        }
        var mergedSubPackages = subPackages.stream().flatMap(this::mergeSubPack)
                .sorted(options.getSort().packages).collect(toList());
        return Stream.of(pack.toBuilder().packages(mergedSubPackages).build());
    }

    protected Package getRenamedSubPack(Package parentPack, Package subPack) {
        return subPack.toBuilder().name(getElementId(parentPack.getName(), subPack.getName())).build();
    }

    protected String getElementId(String... parts) {
        var concat = Stream.of(parts).filter(Objects::nonNull)
                .reduce((parent, id) -> (!parent.isEmpty() ? parent + "." : "") + id)
                .orElse(null);
        return concat != null ? plantUmlAlias(concat) : null;
    }

    protected String getElementId(InterfaceGroup.Key key) {
        var direction = ofNullable(key.getDirection()).map(Enum::name).orElse(null);
        var type = ofNullable(key.getType()).map(Type::getFullName).orElse(null);
        var component = ofNullable(key.getComponent()).map(Component::getName).orElse(null);
        return getElementId(direction, type, component, key.getUntyped());
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
        return distinctPackages(null, components.stream().map(this::getComponentPackage)).values().stream()
                .flatMap(this::mergeSubPack).sorted(options.getSort().packages).collect(toList());
    }

    protected Package getComponentPackage(Component component) {
        return options.packager.apply(component);
    }

    protected void printComponentReferences(IndentStringAppender out, Component component) {
        if (!printedComponents.contains(component)) {
            return;
        }

        var dependencies = ofNullable(component.getDependencies()).map(d -> {
            var componentComparator = options.getSort().getDependencies();
            return componentComparator != null ? d.stream().sorted(componentComparator).collect(toList()) : d;
        }).orElse(Set.of());

        for (var dependency : dependencies) {
            printComponentReference(out, component, dependency);
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
    public enum Direction {
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
    public static class RowsCols {
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
    public static class Union {
        UnionStyle style;
        String id;
        String name;

        public static Union newUnion(String id, String name, UnionStyle style) {
            return Union.builder().id(id).name(name).style(style).build();
        }
    }

    @Data
    @Builder(toBuilder = true)
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class Options {
        public static final Options DEFAULT = Options.builder().build();
        String head, bottom;
        @Builder.Default
        boolean removeUnlinked = true;
        @Builder.Default
        boolean ignoreSprintConfigurations = true;
        @Builder.Default
        boolean reduceDuplicatedElementRelations = true;

        //debug option
        @Builder.Default
        boolean checkUniqueViolation = true;

        @Builder.Default
        Map<String, List<String>> idCharReplaces = DEFAULT_ESCAPES;
        @Builder.Default
        Function<Interface.Direction, Direction> mapDirection = Options::defaultDirectionName;
        @Builder.Default
        Function<InterfaceGroup.Key, UnionStyle> directionGroupUnionStyle = key -> newUnionStyle(cloud, LINE_DOTTED_LINE_GRAY);
        @Builder.Default
        Function<InterfaceGroup.Key, UnionStyle> interfaceTypeUnionStyle = key -> newUnionStyle(unionBorder(key.getType()));
        @Builder.Default
        Function<InterfaceGroup.Key, UnionStyle> componentGroupUnionStyle = key -> newUnionStyle(frame, LINE_DOTTED_TEXT_GRAY);
        @Builder.Default
        BiFunction<Direction, Type, UnionStyle> interfaceSubgroupsUnionStyle = (directionGroup, type) -> newUnionStyle(frame, LINE_DOTTED_TEXT_GRAY);
        @Builder.Default
        Function<String, UnionStyle> packagePathUnion = path -> newUnionStyle(pack, LINE_DOTTED_TEXT_GRAY);
        @Builder.Default
        BiPredicate<Direction, Type> supportGroups = (directionName, type) -> type != null && Set.of(http, jms, ws).contains(type);
        @Builder.Default
        Function<Direction, HtmlMethodsGroupBy> htmlMethodsGroupBy = direction -> path;
        @Builder.Default
        ConcatenateComponentsOptions concatenateComponents = ConcatenateComponentsOptions.DEFAULT;
        @Builder.Default
        ConcatenateInterfacesOptions concatenateInterfaces = ConcatenateInterfacesOptions.DEFAULT;
        @Builder.Default
        Sort sort = Sort.builder().build();

        @Builder.Default
        boolean printPackageBorder = true;
        @Builder.Default
        boolean printDirectionGroupUnion = false;
        @Builder.Default
        boolean printInterfaceTypeUnion = false;

        @Builder.Default
        boolean groupByDirection = true;
        @Builder.Default
        boolean groupByInterfaceType = true;
        @Builder.Default
        Set<Interface.Type> groupedByComponent = Set.of(jms, ws, kafka);
        @Builder.Default
        Function<Component, Package> packager = PlantUmlTextFactory::extractPackage;

        public static UnionStyle newUnionStyle(UnionBorder unionBorder) {
            return UnionStyle.builder().unionBorder(unionBorder).build();
        }

        public static UnionStyle newUnionStyle(UnionBorder unionBorder, String style) {
            return UnionStyle.builder().unionBorder(unionBorder).style(style).build();
        }

        public static UnionBorder unionBorder(Type type) {
            if (type != null) switch (type) {
                case jms:
                    return queue;
                case storage:
                    return database;
            }
            return rectangle;
        }

        public static Direction defaultDirectionName(Interface.Direction direction) {
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
            Comparator<Component> components = Sort::compare;
            Comparator<Component> dependencies = (o1, o2) -> Utils.compareNullable(o1.getName(), o2.getName(), String::compareToIgnoreCase);
            Comparator<Interface> interfaces = (o1, o2) -> ignoreCaseComparator(o1, o2, Interface::getName);
            Comparator<InterfaceGroup> interfaceGroups = (o1, o2) -> compareNullable(o1, o2, InterfaceGroup::getKey, (k1, k2) -> {
                int direction = k1.direction.compareTo(k2.direction);
                if (direction != 0) return direction;
                int type = k1.type.compareTo(k2.type);
                if (type != 0) return type;
                return compare(k1.component, k2.component);
            });
            Comparator<Package> packages = (o1, o2) -> ignoreCaseComparator(o1, o2, Package::getName);

            public static int compare(Component o1, Component o2) {
                var nameCompared = Utils.compareNullable(o1.getName(), o2.getName(), String::compareToIgnoreCase);
                var pathCompared = Utils.compareNullable(o1.getPath(), o2.getPath(), String::compareToIgnoreCase);
                return nameCompared == 0 ? pathCompared : nameCompared;
            }
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
            Predicate<Component> componentsConcatenationCondition = hasNoInterfaces();

            public static Predicate<Component> hasNoInterfaces() {
                return c -> ofNullable(c.getInterfaces()).map(Collection::isEmpty).orElse(true);
            }
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
            int rows = 10;
        }
    }

    @Data
    @Builder(toBuilder = true)
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class InterfaceGroup {
        Key key;
        Map<Interface, List<Component>> interfaces;
        List<InterfaceGroup> groups;

        @Data
        @Builder(toBuilder = true)
        @FieldDefaults(makeFinal = true, level = PRIVATE)
        public static class Key {
            Direction direction;
            Type type;
            Component component;
            String untyped;

            @Override
            public String toString() {
                var builder = new StringBuilder();
                if (direction != null) {
                    builder.append("direction=").append(direction);
                }
                if (type != null) {
                    if (builder.length() > 0) {
                        builder.append(", ");
                    }
                    builder.append("type=").append(type);
                }
                if (component != null) {
                    if (builder.length() > 0) {
                        builder.append(", ");
                    }
                    builder.append("component=").append(component);
                }
                return builder.toString();

            }
        }
    }
}
