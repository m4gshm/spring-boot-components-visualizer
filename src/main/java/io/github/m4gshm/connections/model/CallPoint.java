package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class CallPoint {
    String ownerClass;
    String methodName;
    Type[] argumentTypes;
    InstructionHandle instruction;
    Method method;
//    ConstantPoolGen constantPoolGen;
    JavaClass javaClass;
    @Builder.Default
    List<CallPoint> callPoints = List.of();
}
