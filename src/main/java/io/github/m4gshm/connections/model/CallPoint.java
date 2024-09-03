package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
//    ConstantPoolGen constantPoolGen;
    JavaClass javaClass;
    @Builder.Default
    List<CallPoint> callPoints = List.of();
    @Builder.Default
    Map<Integer, InstructionHandle> jumpsTo = Map.of();
}
