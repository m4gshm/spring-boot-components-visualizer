package io.github.m4gshm.components.visualizer.extractor;

import io.github.m4gshm.components.visualizer.ComponentsExtractor;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.lang.reflect.Method;

import static lombok.AccessLevel.PRIVATE;

@Getter
@Setter
@RequiredArgsConstructor
@Builder
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ScheduledMethod {
    Method method;
    String expression;
    TriggerType triggerType;

    @Override
    public String toString() {
        return triggerType + "(" + expression + ")";
    }

    public enum TriggerType {
        fixedDelay, fixedRate, cron
    }
}
