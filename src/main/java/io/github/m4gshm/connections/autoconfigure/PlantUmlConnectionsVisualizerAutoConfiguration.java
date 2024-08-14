package io.github.m4gshm.connections.autoconfigure;

import io.github.m4gshm.connections.Visualizer;
import io.github.m4gshm.connections.PlantUmlVisualizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static io.github.m4gshm.connections.PlantUmlVisualizer.*;
import static io.github.m4gshm.connections.Utils.getApplicationName;

@Configuration
public class PlantUmlConnectionsVisualizerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Visualizer.class)
    PlantUmlVisualizer plantUmlConnectionsVisualizer(Environment environment, ObjectProvider<Options> options) {
        var opt = options.getIfAvailable();
        return new PlantUmlVisualizer(getApplicationName(environment),opt);
    }
}
