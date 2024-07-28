package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.Set;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Component {
    String name;
    String path;
    Class<?> type;
    Set<Stereotype> stereotypes;
    Set<Interface> interfaces;

    public static Component newBean(String beanName, String path, Class<?> type) {
        return builder().name(beanName).path(path).type(type).build();
    }

    enum Stereotype {
        MainClass, Configuration, RestController, Controller, HttpRouter, HttpClient, WebsocketClient, JmsListener, KafkaListener, Repository
    }
}
