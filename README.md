# Spring Boot components visualizer (under construction)

A tool to visualize external and internal connections of a Sprint Boot
application.

By default, generates a PlantUML schema with Spring components,
relations and interfaces like mvc controllers, websocket services, jms
and so on.

Inspired by Spring REST Docs.

Requires Java 11 or higher.

## Schema example

[puml](./test/service1/src/schema/connections.puml),
[svg](./test/service1/src/schema/connections.svg)

<figure>
<img src="./test/service1/src/schema/connections.svg"
alt="connections" />
</figure>

## Supported interfaces

- Beans with dependency relations

- Sprint Mvc controllers

- Spring Data repositories

- WebSocketHandler

- WebSocketClient

- @JmsListener

- JmsTemplate based clients

- @FeignClient

- Standalone Feign client

- RestTemplate based clients

## Planned to support

- @Scheduled

- TaskScheduler

- @KafkaListener

- KafkaTemplate

- GRPC

- Minio

- Spring Webflux (controllers, routers)

## How to use

### Gradle (Kotlin syntax)

Copy the code below to your `build.gradle.kts`

``` kotlin
repositories {
    mavenCentral()
}

dependencies {
    testImplementation("github.m4gshm:spring-connections-visualizer:0.0.1-beta1")
    testImplementation("org.apache.commons:commons-lang3:3.14.0")
}

tasks.test {
    useJUnitPlatform()
    environment("CONNECTIONS_VISUALIZE_PLANTUML_OUT", "$projectDir/src/schema/connections.puml")
}
```

Next, adds test like below

``` java
package service1;

import com.plantuml.api.cheerpj.v1.Svg;
import io.github.m4gshm.connections.ComponentsExtractor;
import io.github.m4gshm.connections.PlantUmlTextFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

@SpringBootTest(classes = {YourSprintBootApplication.class})
@EnableAutoConfiguration
public class SchemaGeneratorTest {

    @Autowired
    ComponentsExtractor extractor;
    @Autowired
    PlantUmlTextFactory schemaFactory;

    @Test
    public void generatePlantUml() {
        var content = schemaFactory.create(extractor.getComponents());
        var envName = "CONNECTIONS_VISUALIZE_PLANTUML_OUT";
        var outPlantUmlFileName = requireNonNull(System.getenv(envName), envName);
        writeTextFile(outPlantUmlFileName, content);
        writeSwgFile(outPlantUmlFileName, content);
    }

    private void writeSwgFile(String fileName, String content) {
        var svg = Svg.convert(null, content);
        var extensionDelim = fileName.lastIndexOf(".");
        var svgOutFile = (extensionDelim != -1 ? fileName.substring(0, extensionDelim) : fileName) + ".svg";
        try (var writer = new FileOutputStream(svgOutFile)) {
            writer.write(svg.toString().getBytes(UTF_8));
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeTextFile(String fileName, String content) {
        var file = new File(fileName);
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
    }

}
```

Runs gradle build `gradle test`
