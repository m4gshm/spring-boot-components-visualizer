package io.github.m4gshm.connections.autoconfigure;

import io.github.m4gshm.connections.SchemaFactory;
import io.github.m4gshm.connections.PlantUmlTextFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static io.github.m4gshm.connections.PlantUmlTextFactory.*;
import static io.github.m4gshm.connections.Utils.getApplicationName;

@Configuration
public class PlantUmlTextFactoryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SchemaFactory.class)
    PlantUmlTextFactory plantUmlConnectionsVisualizer(Environment environment, ObjectProvider<Options> options) {
        var opt = options.getIfAvailable();
        return new PlantUmlTextFactory(getApplicationName(environment),opt);
    }
}
