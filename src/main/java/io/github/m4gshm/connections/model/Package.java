package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Package {
    private String name;
    private String path;
    private List<Component> components;
    private List<Package> packages;
}