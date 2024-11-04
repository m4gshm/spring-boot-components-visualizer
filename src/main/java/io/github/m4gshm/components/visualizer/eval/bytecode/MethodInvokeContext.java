package io.github.m4gshm.components.visualizer.eval.bytecode;
import lombok.var;

import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.lang.reflect.Method;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class MethodInvokeContext {
    Method method;
    Object object;
    Object[] arguments;
}
