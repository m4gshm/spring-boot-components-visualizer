package io.github.m4gshm.components.visualizer.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.Collections;
import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class CallPoint {
    Class<?> ownerClass;
    String ownerClassName;
    String methodName;
    Type[] argumentTypes;
    InstructionHandle instruction;
    Method method;
    JavaClass javaClass;
    @Builder.Default
    List<CallPoint> callPoints = Collections.emptyList();
    boolean invokeDynamic;
    int referenceKind;
}
