package io.github.m4gshm.components.visualizer.model;
import lombok.var;

import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.Type;

import java.lang.reflect.Method;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class MethodId {
    String name;
    Type[] argumentTypes;

    public static MethodId newMethodId(String name, Type[] argumentTypes) {
        return new MethodId(name, argumentTypes);
    }

    public static MethodId newMethodId(Method method) {
        return newMethodId(method.getName(), Type.getTypes(method.getParameterTypes()));
    }

    public static MethodId newMethodId(String name, String signature) {
        return new MethodId(name, Type.getArgumentTypes(signature));
    }

    public static MethodId newMethodId(org.apache.bcel.classfile.Method method) {
        return newMethodId(method.getName(), method.getArgumentTypes());
    }
}
