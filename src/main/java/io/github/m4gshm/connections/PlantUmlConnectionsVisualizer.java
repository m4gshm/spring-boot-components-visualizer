package io.github.m4gshm.connections;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

import static io.github.m4gshm.connections.Components.HttpInterface.getHttpInterfaceName;
import static java.lang.String.format;

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

        out.append(format("component \"%s\" as %s\n", applicationName, pumlAlias(applicationName)));

        if (simple) {
            visualizeSimple(components.getBeanDependencies(), out);
        } else {
            visualizeStructured(components, out);
        }

        out.append("@enduml\n");
        return out.toString();
    }

    private void visualizeSimple(Map<String, List<String>> beanDependencies, StringBuilder out) {
        beanDependencies.forEach((bean, dependencies) -> {
            for (String dependency : dependencies) {
                out.append(format("%s )..> %s\n", pumlAlias(dependency), pumlAlias(bean)));
            }
        });

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
