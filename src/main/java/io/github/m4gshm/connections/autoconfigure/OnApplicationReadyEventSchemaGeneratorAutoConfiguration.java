package io.github.m4gshm.connections.autoconfigure;

import io.github.m4gshm.connections.ComponentsExtractor;
import io.github.m4gshm.connections.SchemaFactory;
import io.github.m4gshm.connections.OnApplicationReadyEventSchemaGenerator;
import io.github.m4gshm.connections.OnApplicationReadyEventSchemaGenerator.Storage;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@AutoConfigureAfter({ConnectionsExtractorAutoConfiguration.class, PlantUmlTextFactoryAutoConfiguration.class})
public class OnApplicationReadyEventSchemaGeneratorAutoConfiguration {

    @Bean
    @ConditionalOnBean({SchemaFactory.class, Storage.class})
    public <T> OnApplicationReadyEventSchemaGenerator<T> onApplicationReadyEventSchemaGenerator(
            ComponentsExtractor extractor, SchemaFactory<T> visualizer, Storage<T> storage
    ) {
        return new OnApplicationReadyEventSchemaGenerator<>(extractor, visualizer, storage);
    }
}
