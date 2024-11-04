package io.github.m4gshm.components.visualizer.autoconfigure;
import lombok.var;

import io.github.m4gshm.components.visualizer.SchemaFactory;
import io.github.m4gshm.components.visualizer.PlantUmlTextFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static io.github.m4gshm.components.visualizer.PlantUmlTextFactory.*;
import static io.github.m4gshm.components.visualizer.Utils.getApplicationName;

@Configuration(proxyBeanMethods = false)
public class PlantUmlTextFactoryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SchemaFactory.class)
    PlantUmlTextFactory plantUmlConnectionsVisualizer(Environment environment, ObjectProvider<Options> options) {
        return new PlantUmlTextFactory(getApplicationName(environment), options.getIfAvailable());
    }
}
