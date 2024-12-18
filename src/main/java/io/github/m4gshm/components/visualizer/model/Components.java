package io.github.m4gshm.components.visualizer.model;

import lombok.Builder;
import lombok.Data;

import java.util.Collection;

@Data
@Builder
public class Components {
    private final Collection<Component> components;
}
