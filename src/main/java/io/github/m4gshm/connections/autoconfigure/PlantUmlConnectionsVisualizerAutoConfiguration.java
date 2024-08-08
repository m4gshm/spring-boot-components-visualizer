package io.github.m4gshm.connections.autoconfigure;

import io.github.m4gshm.connections.Visualizer;
import io.github.m4gshm.connections.PlantUmlVisualizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static io.github.m4gshm.connections.Visualizer.getApplicationName;

@Configuration
public class PlantUmlConnectionsVisualizerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Visualizer.class)
    PlantUmlVisualizer plantUmlConnectionsVisualizer(Environment environment) {
        return new PlantUmlVisualizer(getApplicationName(environment));
    }
}
