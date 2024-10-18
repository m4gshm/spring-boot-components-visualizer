package io.github.m4gshm.connections.eval.bytecode;

import lombok.Getter;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.generic.Instruction;

@Getter
public class EvalBytecodeException extends RuntimeException {

    public EvalBytecodeException(String message) {
        super(message);
    }

    public EvalBytecodeException(Throwable e) {
        super(e);
    }

    public static EvalBytecodeException newUnsupportedEvalException(Instruction instruction, ConstantPool constantPool) {
        return newInvalidEvalException("unsupported instruction", instruction, constantPool);
    }

    public static EvalBytecodeException newInvalidEvalException(String message, Instruction instruction, ConstantPool constantPool) {
        return new EvalBytecodeException(message + ": " + instruction.toString(constantPool));
    }
}
