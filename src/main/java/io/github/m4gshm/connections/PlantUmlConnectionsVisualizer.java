package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.Interface;
import io.github.m4gshm.connections.model.Package;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.reverse;
import static com.google.common.collect.Streams.concat;
import static io.github.m4gshm.connections.PlantUmlConnectionsVisualizer.PackageOutType.cloud;
import static io.github.m4gshm.connections.PlantUmlConnectionsVisualizer.PackageOutType.rectangle;
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
        var onRemove = List.of("*", "$").stream().map(v -> "\\" + v).reduce((l, r) -> l + "|" + r).orElse("");
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

    private static void printPackage(StringBuilder out, String prefix, Package pack) {
        printPackage(out, prefix, pack, PackageOutType.pack);
    }

    private static void printPackage(StringBuilder out, String prefix, Package pack, PackageOutType packageType) {
        var packageName = pack.getName();
        var wrapByPack = !packageName.isEmpty();

        Runnable printInternal = () -> {
            var beans = pack.getComponents();
            if (beans != null) {
                beans.forEach(bean -> printComponent(out, prefix + "  ", bean));
            }
            var packages = pack.getPackages();
            if (packages != null) {
                packages.forEach(subPack -> printPackage(out, prefix + (wrapByPack ? "  " : ""), subPack));
            }
        };

        if (wrapByPack) {
            printPackage(out, prefix, packageName, pack.getPath(), packageType, printInternal);
        } else {
            printInternal.run();
        }
    }

    private static void printPackage(StringBuilder out, String prefix, String name, String id,
                                     PackageOutType packageType, Runnable internal) {
        out.append(format(prefix + "%s \"%s\" as %s {\n", packageType.code, name, id));
        internal.run();
        out.append(prefix).append("}\n");
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

                    var distinctPackages = distinctPackages(getElementId(parentPath, l.getName()), concat(
                            ofNullable(l.getPackages()).orElse(emptyList()).stream(),
                            ofNullable(r.getPackages()).orElse(emptyList()).stream())
                    );
                    var pack = l.toBuilder().packages(copyOf(distinctPackages.values())).build();
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
        var prefix = "";

        var packages = distinctPackages(prefix, components.stream().map(bean -> {
            var beanPath = bean.getPath();

            var reversePathBuilders = reverse(asList(beanPath.split("\\."))).stream()
                    .map(packageName -> Package.builder().name(packageName))
                    .collect(toList());

            reversePathBuilders.stream().findFirst().ifPresent(packageBuilder -> packageBuilder.components(singletonList(bean)));

            return reversePathBuilders.stream().reduce((l, r) -> {
                r.packages(singletonList(l.build()));
                return r;
            }).map(Package.PackageBuilder::build).orElse(
                    Package.builder().name(beanPath).components(singletonList(bean)).build()
            );

        })).values();

        for (var pack : packages) {
            printPackage(out, prefix, pack);
        }

        components.forEach(component -> {
            component.getDependencies().stream().map(Component::getName).forEach(dependency -> {
                out.append(format("%s ..> %s\n", getComponentId(component), pumlAlias(dependency)));
            });
        });

        var groupedInterfaces = components.stream().flatMap(component -> Stream.ofNullable(component.getInterfaces())
                        .flatMap(Collection::stream).map(anInterface -> entry(anInterface, component)))
                .collect(groupingBy(entry -> entry.getKey().getDirection(), groupingBy(entry -> entry.getKey().getType())));

        groupedInterfaces.forEach((direction, byType) -> {
            var directionName = direction.name();
            printPackage(out, prefix, directionName, directionName, rectangle, () -> {
                byType.forEach((type, interfaceComponentLink) -> {
                    var typeName = type.name();
                    printPackage(out, prefix + INDENT, typeName, getElementId(directionName, typeName), cloud, () -> {
                        interfaceComponentLink.forEach(entry -> {
                            var anInterface = entry.getKey();
                            var component = entry.getValue();
                            var interfaceId = getInterfaceId(component, anInterface);
                            out.append(prefix + INDENT.repeat(2));
                            out.append(format("interface \"%s\" as %s\n", anInterface.getName(), interfaceId));
                            out.append(prefix + INDENT.repeat(2));
                            out.append(format("%s )..> %s\n", interfaceId, getComponentId(component)));
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
        cloud("cloud");

        private final String code;
    }

//    private void visualizeStructured(Components components, StringBuilder out) {
//        var httpInterfaces = components.getHttpInterfaces();
//        if (!httpInterfaces.isEmpty()) {
//            out.append("cloud \"REST API\" {\n");
//            httpInterfaces.forEach((beanName, httpInterface) -> {
//                var name = getHttpInterfaceName(beanName, httpInterface);
//                out.append(format("\tinterface \"%s\" as %s\n", name, pumlAlias(name)));
//            });
//            out.append("}\n");
//
//            httpInterfaces.forEach((beanName, httpInterface) -> {
//                var name = getHttpInterfaceName(beanName, httpInterface);
//                out.append(format("%s )..> %s\n", pumlAlias(name), pumlAlias(applicationName)));
//            });
//        }
//
//        var jmsListeners = components.getJmsListeners();
//        if (!jmsListeners.isEmpty()) {
//            out.append("queue \"Input queues\" {\n");
//            for (var jmsQueue : jmsListeners.values()) {
//                out.append(format("\tqueue \"%s\" as %s\n", jmsQueue.getDestination(), pumlAlias(jmsQueue.getName())));
//            }
//            out.append("}\n");
//
//            for (var jmsQueue : jmsListeners.values()) {
//                out.append(format("%s ..> %s: jms\n", pumlAlias(jmsQueue.getName()), pumlAlias(applicationName)));
//            }
//        }
//
//        var httpClients = components.getHttpClients();
//        if (!(httpClients.isEmpty())) {
//            out.append("cloud \"H2H Services\" {\n");
//            for (var target : httpClients.values()) {
//                out.append(format("\tcomponent \"%s\" as %s\n",
//                        target.getName(),
//                        pumlAlias(target.getName()))
//                );
//            }
//
//            out.append("}\n");
//
//            for (var target : httpClients.values()) {
//                out.append(format("%s ..> %s: http\n", pumlAlias(applicationName), pumlAlias(target.getName())));
//            }
//        }
//
//        //database postgres
//        //
//        //package "File storages" {
//        //    component  "minio"
//        //}
//        //
//    }

}
