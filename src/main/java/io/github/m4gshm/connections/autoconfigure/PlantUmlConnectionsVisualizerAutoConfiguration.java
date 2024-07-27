package io.github.m4gshm.connections.autoconfigure;

import io.github.m4gshm.connections.ConnectionsVisualizer;
import io.github.m4gshm.connections.PlantUmlConnectionsVisualizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class PlantUmlConnectionsVisualizerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ConnectionsVisualizer.class)
    PlantUmlConnectionsVisualizer plantUmlConnectionsVisualizer(Environment environment) {
        return new PlantUmlConnectionsVisualizer(ConnectionsVisualizer.getApplicationName(environment), true);
    }
}
