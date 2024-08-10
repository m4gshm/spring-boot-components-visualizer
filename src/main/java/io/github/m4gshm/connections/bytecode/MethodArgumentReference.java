package io.github.m4gshm.connections.bytecode;

import lombok.Data;
import org.apache.bcel.classfile.LocalVariable;

@Data
public class MethodArgumentReference implements CharSequence {
    private final LocalVariable localVariable;

    public String getName() {
        return localVariable.getName();
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public int length() {
        return getName().length();
    }

    @Override
    public char charAt(int index) {
        return getName().charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return getName().subSequence(start, end);
    }
}
