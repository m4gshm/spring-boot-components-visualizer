package io.github.m4gshm.connections.autoconfigure;

import io.github.m4gshm.connections.ConnectionsExtractor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConnectionsExtractorAutoConfiguration {
    @Bean
    public ConnectionsExtractor connectionsExtractor(ConfigurableApplicationContext context) {
        return new ConnectionsExtractor(context);
    }

}
