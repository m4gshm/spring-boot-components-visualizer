package io.github.m4gshm.components.visualizer.extractor;

import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.model.Direction;
import io.github.m4gshm.components.visualizer.model.MethodId;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Getter
@Setter
@RequiredArgsConstructor
@Builder
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class JmsService {
    String name;
    String destination;
    Direction direction;
    MethodId methodSource;
    Result evalSource;

    @Getter
    @Setter
    @RequiredArgsConstructor
    @Builder
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class Destination {
        String destination;
        Direction direction;

        @Override
        public String toString() {
            return destination + ":" + direction;
        }
    }
}
