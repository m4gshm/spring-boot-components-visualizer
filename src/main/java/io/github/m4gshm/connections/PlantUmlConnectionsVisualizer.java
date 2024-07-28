package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.Package;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Lists.reverse;
import static com.google.common.collect.Streams.concat;
import static io.github.m4gshm.connections.Components.HttpInterface.getHttpInterfaceName;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.*;

@RequiredArgsConstructor
public class PlantUmlConnectionsVisualizer implements ConnectionsVisualizer<String> {

    private final String applicationName;
    private final boolean simple;

    private static String pumlAlias(String name) {
        return name.replace("-", "").replace(".", "").replace("$", "").replace("@", "");
    }

    @Override
    public String visualize(Components components) {
        var out = new StringBuilder();

        out.append("@startuml\n");

//        out.append(format("component \"%s\" as %s\n", applicationName, pumlAlias(applicationName)));

        if (simple) {
            visualizeSimple(components.getBeanDependencies(), out, true);
        } else {
            visualizeStructured(components, out);
        }

        out.append("@enduml\n");
        return out.toString();
    }

    private void visualizeSimple(Map<Component, List<String>> beanDependencies, StringBuilder out, boolean groupByPackages) {
        if (groupByPackages) {
            var packages = distinctPackages("", beanDependencies.keySet().stream().map(bean -> {
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
                printPackage(out, "", pack);
            }

        } else {
            beanDependencies.keySet().forEach(bean -> printBean(out, "", bean));
        }

        beanDependencies.forEach((bean, dependencies) -> {
            for (String dependency : dependencies) {
                out.append(format("%s ..> %s\n", pumlAlias(bean.getName()), pumlAlias(dependency)));
            }
        });
    }

    private static void printPackage(StringBuilder out, String prefix, Package pack) {
        var packageName = pack.getName();
        var wrapByPack = !packageName.isEmpty();
        if (wrapByPack) {
            var path = pack.getPath();
            var packId = path;//getElementId(path, packageName);
            out.append(format(prefix + "package \"%s\" as %s {\n", packageName, packId));
        }
        var beans = pack.getComponents();
        if (beans != null) {
            beans.forEach(bean -> printBean(out, prefix + "  ", bean));
        }
        var packages = pack.getPackages();
        if (packages != null) {
            packages.forEach(subPack -> printPackage(out, prefix + (wrapByPack ? "  " : ""), subPack));
        }
        if (wrapByPack) {
            out.append(prefix + "}\n");
        }
    }

    private static String getElementId(String path, String packageName) {
        return (path != null && !path.isEmpty() ? path + "." : "") + packageName;
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

    private static void printBean(StringBuilder out, String prefix, Component component) {
        var beanId = component.getName();//getElementId(bean.getPath(), bean.getName());
        out.append(prefix + format("[%s] as %s\n", component.getName(), beanId));
    }

    private void visualizeStructured(Components components, StringBuilder out) {
        var httpInterfaces = components.getHttpInterfaces();
        if (!httpInterfaces.isEmpty()) {
            out.append("cloud \"REST API\" {\n");
            httpInterfaces.forEach((beanName, httpInterface) -> {
                var name = getHttpInterfaceName(beanName, httpInterface);
                out.append(format("\tinterface \"%s\" as %s\n", name, pumlAlias(name)));
            });
            out.append("}\n");

            httpInterfaces.forEach((beanName, httpInterface) -> {
                var name = getHttpInterfaceName(beanName, httpInterface);
                out.append(format("%s )..> %s\n", pumlAlias(name), pumlAlias(applicationName)));
            });
        }

        var jmsListeners = components.getJmsListeners();
        if (!jmsListeners.isEmpty()) {
            out.append("queue \"Input queues\" {\n");
            for (var jmsQueue : jmsListeners.values()) {
                out.append(format("\tqueue \"%s\" as %s\n", jmsQueue.getDestination(), pumlAlias(jmsQueue.getName())));
            }
            out.append("}\n");

            for (var jmsQueue : jmsListeners.values()) {
                out.append(format("%s ..> %s: jms\n", pumlAlias(jmsQueue.getName()), pumlAlias(applicationName)));
            }
        }

        var httpClients = components.getHttpClients();
        if (!(httpClients.isEmpty())) {
            out.append("cloud \"H2H Services\" {\n");
            for (var target : httpClients.values()) {
                out.append(format("\tcomponent \"%s\" as %s\n",
                        target.getName(),
                        pumlAlias(target.getName()))
                );
            }

            out.append("}\n");

            for (var target : httpClients.values()) {
                out.append(format("%s ..> %s: http\n", pumlAlias(applicationName), pumlAlias(target.getName())));
            }
        }

        //database postgres
        //
        //package "File storages" {
        //    component  "minio"
        //}
        //
    }

}
