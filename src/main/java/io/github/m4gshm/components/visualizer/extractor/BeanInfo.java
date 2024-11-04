package io.github.m4gshm.components.visualizer.extractor;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true)
public class BeanInfo {
    String name;
    Class<?> type;
    Object bean;
}
