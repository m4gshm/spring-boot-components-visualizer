package io.github.m4gshm.connections.bytecode;

import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.Instruction;

public class EvalBytecodeException extends RuntimeException {
    public EvalBytecodeException(String message) {
        super(message);
    }

    public EvalBytecodeException(Throwable e) {
        super(e);
    }

    public static EvalBytecodeException newUnsupportedEvalException(Instruction instruction, ConstantPoolGen constantPoolGen) {
        return newInvalidEvalException("unsupported instruction", instruction, constantPoolGen);
    }

    public static EvalBytecodeException newInvalidEvalException(String message, Instruction instruction, ConstantPoolGen constantPoolGen) {
        return new EvalBytecodeException(message + ": " + instruction.toString(constantPoolGen.getConstantPool()));
    }
}
