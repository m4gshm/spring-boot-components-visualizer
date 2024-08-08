package io.github.m4gshm.connections.autoconfigure;

import io.github.m4gshm.connections.ComponentsExtractor;
import io.github.m4gshm.connections.Visualizer;
import io.github.m4gshm.connections.OnApplicationReadyEventConnectionsVisualizeGenerator;
import io.github.m4gshm.connections.OnApplicationReadyEventConnectionsVisualizeGenerator.Storage;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@AutoConfigureAfter({ConnectionsExtractorAutoConfiguration.class, PlantUmlConnectionsVisualizerAutoConfiguration.class})
public class OnApplicationReadyEventConnectionsVisualizeGeneratorAutoConfiguration {

    @Bean
    @ConditionalOnBean({Visualizer.class, Storage.class})
    public <T> OnApplicationReadyEventConnectionsVisualizeGenerator<T> onApplicationReadyEventConnectionsVisualizeGenerator(
            ComponentsExtractor extractor, Visualizer<T> visualizer, Storage<T> storage
    ) {
        return new OnApplicationReadyEventConnectionsVisualizeGenerator<>(extractor, visualizer, storage);
    }
}
