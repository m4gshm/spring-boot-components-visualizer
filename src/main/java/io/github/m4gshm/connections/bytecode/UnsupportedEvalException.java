package io.github.m4gshm.connections.bytecode;

import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.Instruction;

public class UnsupportedEvalException extends RuntimeException {
    public UnsupportedEvalException(String message) {
        super("unsupported instruction: " + message);
    }

    public static UnsupportedEvalException newUnsupportedEvalException(Instruction instruction, ConstantPoolGen constantPoolGen) {
        return new UnsupportedEvalException(instruction.toString(constantPoolGen.getConstantPool()));
    }
}
