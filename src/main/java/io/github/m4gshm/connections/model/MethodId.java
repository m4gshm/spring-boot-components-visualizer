package io.github.m4gshm.connections.model;

import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.Type;

import java.lang.reflect.Method;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class MethodId {
    Class<?> declaringClass;
    String name;
    Type[] argumentTypes;

    public static MethodId newMethodId(Class<?> declaringClass, String name, Type[] argumentTypes) {
        return new MethodId(declaringClass, name, argumentTypes);
    }

    public static MethodId newMethodId(Method method) {
        return newMethodId(method.getDeclaringClass(), method.getName(), Type.getTypes(method.getParameterTypes()));
    }

    public static MethodId newMethodId(Class<?> declaringClass, String name, String signature) {
        return new MethodId(declaringClass, name, Type.getArgumentTypes(signature));
    }

    public static MethodId newMethodId(Class<?> declaringClass, org.apache.bcel.classfile.Method method) {
        return newMethodId(declaringClass, method.getName(), method.getArgumentTypes());
    }
}
