package io.github.m4gshm.components.visualizer.autoconfigure;

import io.github.m4gshm.components.visualizer.ComponentsExtractor;
import io.github.m4gshm.components.visualizer.extractor.Options;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ComponentsExtractorAutoConfiguration {
    @Bean
    public ComponentsExtractor componentsExtractor(ConfigurableApplicationContext context,
                                                   ObjectProvider<Options> componentsExtractorOptions) {
        return new ComponentsExtractor(context, componentsExtractorOptions.getIfAvailable());
    }
}
