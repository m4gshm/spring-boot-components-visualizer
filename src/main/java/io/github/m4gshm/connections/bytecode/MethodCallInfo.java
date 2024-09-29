package io.github.m4gshm.connections.bytecode;

import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.lang.reflect.Method;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class MethodCallInfo {
    Method method;
    Object object;
    Object[] arguments;
}
