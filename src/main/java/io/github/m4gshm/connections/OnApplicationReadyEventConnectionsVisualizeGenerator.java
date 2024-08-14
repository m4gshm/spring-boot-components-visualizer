package io.github.m4gshm.connections;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.function.Consumer;


@Slf4j
@RequiredArgsConstructor
public class OnApplicationReadyEventConnectionsVisualizeGenerator<T> implements ApplicationListener<ApplicationReadyEvent> {

    private final ComponentsExtractor extractor;
    private final Visualizer<T> visualizer;
    private final Storage<T> storage;

    @Override
    @SneakyThrows
    public void onApplicationEvent(ApplicationReadyEvent event) {
        storage.accept(visualizer.render(extractor.getComponents()));
    }

    public interface Storage<T> extends Consumer<T> {

    }
}
