package io.github.m4gshm.connections.bytecode;

import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.Instruction;

public class EvalException extends RuntimeException {
    public EvalException(String message) {
        super(message);
    }

    public static EvalException newUnsupportedEvalException(Instruction instruction, ConstantPoolGen constantPoolGen) {
        return newInvalidEvalException("unsupported instruction: ", instruction, constantPoolGen);
    }

    public static EvalException newInvalidEvalException(String message, Instruction instruction, ConstantPoolGen constantPoolGen) {
        return new EvalException(message + ": " + instruction.toString(constantPoolGen.getConstantPool()));
    }
}
