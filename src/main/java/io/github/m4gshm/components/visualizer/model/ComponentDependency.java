package io.github.m4gshm.components.visualizer.model;
import lombok.var;

public interface ComponentDependency {
    String getName();

    boolean isManaged();
}
