package io.github.m4gshm.connections.eval.bytecode;

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
