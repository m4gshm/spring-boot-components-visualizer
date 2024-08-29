package service1;

import io.github.m4gshm.connections.ComponentsExtractor;
import io.github.m4gshm.connections.PlantUmlTextFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;

import static java.util.Objects.requireNonNull;
import static service1.SchemaGeneratorTest.*;

@SpringBootTest(classes = {YourSprintBootApplication.class})
@EnableAutoConfiguration
public class SchemaGeneratorTest2 {

    @Autowired
    ComponentsExtractor extractor;
    @Autowired
    PlantUmlTextFactory schemaFactory;

    @Test
    public void generatePlantUml() {
        var schema = schemaFactory.create(extractor.getComponents(), schemaFactory.getOptions().toBuilder()
                .concatenateInterfaces(PlantUmlTextFactory.Options.ConcatenateInterfacesOptions.builder()
                        .moreThan(1)
                        .build())
                .concatenateComponents(PlantUmlTextFactory.Options.ConcatenateComponentsOptions.builder()
                        .moreThan(1)
                        .build())
                .build());
        var envName = "PLANTUML_OUT";
        var plantUmlOutFile = new File(requireNonNull(System.getenv(envName), envName), "components-compress.puml");
        writeTextFile(plantUmlOutFile, schema);
        writeSwgFile(getSvgFile(plantUmlOutFile), schema);
    }
}
