package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.Getter;
import lombok.experimental.StandardException;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.generic.Instruction;

@Getter
@StandardException
public class EvalException extends RuntimeException {

    public static EvalException newUnsupportedEvalException(Instruction instruction, ConstantPool constantPool) {
        return newInvalidEvalException("unsupported instruction", instruction, constantPool);
    }

    public static EvalException newInvalidEvalException(String message, Instruction instruction, ConstantPool constantPool) {
        return new EvalException(message + ": " + instruction.toString(constantPool));
    }
}
