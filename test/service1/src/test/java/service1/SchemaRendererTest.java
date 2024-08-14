package service1;

import io.github.m4gshm.connections.ComponentsExtractor;
import io.github.m4gshm.connections.OnApplicationReadyEventSchemaGenerator;
import io.github.m4gshm.connections.PlantUmlTextFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

import static io.github.m4gshm.connections.PlantUmlTextFactory.DEFAULT_OPTIONS;
import static java.util.Objects.requireNonNull;

@SpringBootTest(classes = {
        Service1Application.class,
        SchemaRendererTest.TestConfig.class
})
@EnableAutoConfiguration
public class SchemaRendererTest {

    @Autowired
    OnApplicationReadyEventSchemaGenerator<?> visualizeGenerator;

    @Test
    public void generatePlantUml() {

    }

    @Configuration
    public static class TestConfig {

        @Bean
        ComponentsExtractor.Options componentExtractorOptions() {
            return ComponentsExtractor.Options.builder()
                    .failFast(true)
                    .ignoreNotFoundDependencies(true)
                    .build();
        }

        @Bean
        PlantUmlTextFactory.Options plantUmlVisualizerOptions() {
            return DEFAULT_OPTIONS.toBuilder().collapseComponentsMoreThen(2).build();
        }

        @Bean
        OnApplicationReadyEventSchemaGenerator.Storage<String> storage() {
            return content -> {
                var envName = "CONNECTIONS_VISUALIZE_PLANTUML_OUT";
                var outFileName = requireNonNull(System.getenv(envName), envName);
                var file = new File(outFileName);
                var parentFile = file.getParentFile();
                if (!parentFile.exists()) {
                    parentFile.mkdirs();
                }
                try (var writer = new OutputStreamWriter(new FileOutputStream(file))) {
                    writer.write(content);
                    writer.flush();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        }

    }
}