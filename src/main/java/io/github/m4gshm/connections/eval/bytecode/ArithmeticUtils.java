package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Result;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.ArithmeticInstruction;

import java.util.function.DoubleBinaryOperator;
import java.util.function.LongBinaryOperator;

import static org.apache.bcel.Const.*;

@UtilityClass
public class ArithmeticUtils {
    public static Object computeArithmetic(ArithmeticInstruction instruction, Result first, Result second) {
        var opcode = instruction.getOpcode();
        switch (opcode) {
            case DADD:
                return invokeDouble(first, second, (a, b) -> a + b);
            case DDIV:
                return invokeDouble(first, second, (a, b) -> a / b);
            case DMUL:
                return invokeDouble(first, second, (a, b) -> a * b);
            case DNEG:
                return -(double) first.getValue();
            case DREM:
                return invokeDouble(first, second, (a, b) -> a % b);
            case DSUB:
                return invokeDouble(first, second, (a, b) -> a - b);
            case FADD:
                return (float) invokeDouble(first, second, (a, b) -> a + b);
            case FDIV:
                return (float) invokeDouble(first, second, (a, b) -> a / b);
            case FMUL:
                return (float) invokeDouble(first, second, (a, b) -> a * b);
            case FNEG:
                return -(float) first.getValue();
            case FREM:
                return (float) invokeDouble(first, second, (a, b) -> a % b);
            case FSUB:
                return (float) invokeDouble(first, second, (a, b) -> a - b);
            case IADD:
                return (int) invokeLong(first, second, (a, b) -> a + b);
            case IAND:
                return (int) invokeLong(first, second, (a, b) -> a & b);
            case IDIV:
                return (int) invokeLong(first, second, (a, b) -> a / b);
            case IMUL:
                return (int) invokeLong(first, second, (a, b) -> a * b);
            case INEG:
                return -(int) first.getValue();
            case IOR:
                return (int) invokeLong(first, second, (a, b) -> a | b);
            case IREM:
                return (int) invokeLong(first, second, (a, b) -> a % b);
            case ISHL:
                return (int) shiftLeft(first, second);
            case ISHR:
                return (int) shiftRight(first, second);
            case ISUB:
                return (int) invokeLong(first, second, (a, b) -> a - b);
            case IUSHR:
                return (int) unsignedShiftRight(first, second);
            case IXOR:
                return (int) invokeLong(first, second, (a, b) -> a ^ b);
            case LADD:
                return invokeLong(first, second, (a, b) -> a + b);
            case LAND:
                return invokeLong(first, second, (a, b) -> a & b);
            case LDIV:
                return invokeLong(first, second, (a, b) -> a / b);
            case LMUL:
                return invokeLong(first, second, (a, b) -> a * b);
            case LNEG:
                return -(long) first.getValue();
            case LOR:
                return invokeLong(first, second, (a, b) -> a | b);
            case LREM:
                return invokeLong(first, second, (a, b) -> a % b);
            case LSHL:
                return shiftLeft(first, second);
            case LSHR:
                return shiftRight(first, second);
            case LSUB:
                return invokeLong(first, second, (a, b) -> a - b);
            case LUSHR:
                return unsignedShiftRight(first, second);
            case LXOR:
                return invokeLong(first, second, (a, b) -> a ^ b);
            default:
                throw new IllegalStateException("unsupported arithmetic op " + opcode);
        }
    }

    private static double invokeDouble(Result first, Result second, DoubleBinaryOperator add) {
        var value1 = (double) second.getValue();
        var value2 = (double) first.getValue();
        return add.applyAsDouble(value1, value2);
    }

    private static double invokeLong(Result first, Result second, LongBinaryOperator add) {
        var value1 = (long) second.getValue();
        var value2 = (long) first.getValue();
        return add.applyAsLong(value1, value2);
    }

    private static long unsignedShiftRight(Result first, Result second) {
        var value1 = (long) second.getValue();
        var s = s(first);
        return value1 >>> s;
    }

    private static long shiftRight(Result first, Result second) {
        var value1 = (long) second.getValue();
        var s = s(first);
        return value1 >> s;
    }

    private static long shiftLeft(Result first, Result second) {
        var value1 = (long) second.getValue();
        var s = s(first);
        return value1 << s;
    }

    private static long s(Result result) {
        var value2 = (long) result.getValue();
        var s = value2 & 0X1f;
        return s;
    }
}
