package io.github.m4gshm.connections.eval.bytecode;

import lombok.experimental.StandardException;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.generic.Instruction;

@StandardException
public class EvalBytecodeException extends RuntimeException {

    public static EvalBytecodeException newUnsupportedEvalException(Instruction instruction, ConstantPool constantPool) {
        return newInvalidEvalException("unsupported instruction", instruction, constantPool);
    }

    public static EvalBytecodeException newInvalidEvalException(String message, Instruction instruction,
                                                                ConstantPool constantPool) {
        return new EvalBytecodeException(message + ": " + instruction.toString(constantPool));
    }
}
