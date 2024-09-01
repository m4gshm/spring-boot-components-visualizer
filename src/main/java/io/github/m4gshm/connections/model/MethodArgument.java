package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.LocalVariable;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder(toBuilder = true)
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class MethodArgument {
    LocalVariable localVariable;
    List<Object> variants;

    @Override
    public String toString() {
        return "arg(" + localVariable.getIndex() + "):" + localVariable.getName();
    }
}
