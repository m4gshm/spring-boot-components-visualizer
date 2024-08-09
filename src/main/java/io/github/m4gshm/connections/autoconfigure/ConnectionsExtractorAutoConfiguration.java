package io.github.m4gshm.connections.autoconfigure;

import io.github.m4gshm.connections.ComponentsExtractor;
import io.github.m4gshm.connections.ComponentsExtractor.Options;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConnectionsExtractorAutoConfiguration {
    @Bean
    public ComponentsExtractor connectionsExtractor(ConfigurableApplicationContext context,
                                                    ObjectProvider<Options> componentsExtractorOptions) {
        return new ComponentsExtractor(context, componentsExtractorOptions.getIfAvailable());
    }
}
