package io.github.m4gshm.components.visualizer.model;

import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.Type;

import java.lang.reflect.Method;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class MethodId {
    private static final Type[] NO_TYPES = new Type[0];

    String name;
    Type[] argumentTypes;

    public static MethodId newMethodId(String name, Type[] argumentTypes) {
        return new MethodId(name, argumentTypes);
    }

    public static MethodId newMethodId(String name) {
        return new MethodId(name, NO_TYPES);
    }

    public static MethodId newMethodId(Method method) {
        return newMethodId(method.getName(), Type.getTypes(method.getParameterTypes()));
    }

    public static MethodId newMethodId(org.apache.bcel.classfile.Method method) {
        return newMethodId(method.getName(), method.getArgumentTypes());
    }
}
