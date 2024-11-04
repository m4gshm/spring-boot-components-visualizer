package io.github.m4gshm.components.visualizer.extractor;

import io.github.m4gshm.components.visualizer.model.HttpMethod;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Getter
@Setter
@RequiredArgsConstructor
@Builder
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class FeignClient {
    Class<?> type;
    String name;
    String url;
    List<HttpMethod> httpMethods;
}
