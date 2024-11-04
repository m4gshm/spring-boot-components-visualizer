package io.github.m4gshm.components.visualizer.extractor;

import io.github.m4gshm.components.visualizer.ComponentsExtractor;
import io.github.m4gshm.components.visualizer.eval.bytecode.StringifyResolver;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.util.Set;

import static io.github.m4gshm.components.visualizer.eval.bytecode.StringifyResolver.Level.varOnly;
import static lombok.AccessLevel.PRIVATE;

@Getter
@Setter
@RequiredArgsConstructor
@Builder(toBuilder = true)
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Options {
    public static final Options DEFAULT = Options.builder().build();
    public boolean includeUnusedOutInterfaces;
    BeanFilter exclude;
    boolean failFast;
    @Builder.Default
    boolean ignoreNotFoundDependencies = true;
    boolean cropRootPackagePath;
    @Builder.Default
    StringifyResolver.Level stringifyLevel = varOnly;

    @Getter
    @Setter
    @RequiredArgsConstructor
    @Builder
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class BeanFilter {
        Set<String> packageName;
        Set<String> beanName;
        Set<Class<?>> type;
    }
}
