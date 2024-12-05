package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.ArithmeticInstruction;

import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.LongBinaryOperator;

import static org.apache.bcel.Const.*;

@UtilityClass
public class ArithmeticUtils {
    public static Object computeArithmetic(ArithmeticInstruction instruction, Result first, Result second, Resolver resolver, Eval eval) {
        var opcode = instruction.getOpcode();
        switch (opcode) {
            case DADD:
                return invokeDouble(first, second, resolver, (a, b) -> a + b, eval);
            case DDIV:
                return invokeDouble(first, second, resolver, (a, b) -> a / b, eval);
            case DMUL:
                return invokeDouble(first, second, resolver, (a, b) -> a * b, eval);
            case DNEG:
                return -getDouble(first, resolver, eval);
            case DREM:
                return invokeDouble(first, second, resolver, (a, b) -> a % b, eval);
            case DSUB:
                return invokeDouble(first, second, resolver, (a, b) -> a - b, eval);
            case FADD:
                return (float) invokeDouble(first, second, resolver, (a, b) -> a + b, eval);
            case FDIV:
                return (float) invokeDouble(first, second, resolver, (a, b) -> a / b, eval);
            case FMUL:
                return (float) invokeDouble(first, second, resolver, (a, b) -> a * b, eval);
            case FNEG:
                return -getFloat(first, resolver, eval);
            case FREM:
                return (float) invokeDouble(first, second, resolver, (a, b) -> a % b, eval);
            case FSUB:
                return (float) invokeDouble(first, second, resolver, (a, b) -> a - b, eval);
            case IADD:
                return (int) invokeLong(first, second, resolver, (a, b) -> a + b, eval);
            case IAND:
                return (int) invokeLong(first, second, resolver, (a, b) -> a & b, eval);
            case IDIV:
                return (int) invokeLong(first, second, resolver, (a, b) -> a / b, eval);
            case IMUL:
                return (int) invokeLong(first, second, resolver, (a, b) -> a * b, eval);
            case INEG:
                return -getInt(first, resolver, eval);
            case IOR:
                return (int) invokeLong(first, second, resolver, (a, b) -> a | b, eval);
            case IREM:
                return (int) invokeLong(first, second, resolver, (a, b) -> a % b, eval);
            case ISHL:
                return (int) shiftLeft(first, second, resolver, eval);
            case ISHR:
                return (int) shiftRight(first, second, resolver, eval);
            case ISUB:
                return (int) invokeLong(first, second, resolver, (a, b) -> a - b, eval);
            case IUSHR:
                return (int) unsignedShiftRight(first, second, resolver, eval);
            case IXOR:
                return (int) invokeLong(first, second, resolver, (a, b) -> a ^ b, eval);
            case LADD:
                return invokeLong(first, second, resolver, (a, b) -> a + b, eval);
            case LAND:
                return invokeLong(first, second, resolver, (a, b) -> a & b, eval);
            case LDIV:
                return invokeLong(first, second, resolver, (a, b) -> a / b, eval);
            case LMUL:
                return invokeLong(first, second, resolver, (a, b) -> a * b, eval);
            case LNEG:
                return -getLong(first, resolver, eval);
            case LOR:
                return invokeLong(first, second, resolver, (a, b) -> a | b, eval);
            case LREM:
                return invokeLong(first, second, resolver, (a, b) -> a % b, eval);
            case LSHL:
                return shiftLeft(first, second, resolver, eval);
            case LSHR:
                return shiftRight(first, second, resolver, eval);
            case LSUB:
                return invokeLong(first, second, resolver, (a, b) -> a - b, eval);
            case LUSHR:
                return unsignedShiftRight(first, second, resolver, eval);
            case LXOR:
                return invokeLong(first, second, resolver, (a, b) -> a ^ b, eval);
            default:
                throw new IllegalStateException("unsupported arithmetic op " + opcode);
        }
    }

    private static int getInt(Result result, Resolver resolver, Eval eval) {
        try {
            return (int) getFirst(result.getValue(resolver, eval));
        } catch (ClassCastException | NotInvokedException e) {
            throw new UnresolvedResultException(e, result);
        }
    }

    private static long getLong(Result result, Resolver resolver, Eval eval) {
        try {
            return (long) getFirst(result.getValue(resolver, eval));
        } catch (ClassCastException | NotInvokedException e) {
            throw new UnresolvedResultException(e, result);
        }
    }

    private static float getFloat(Result result, Resolver resolver, Eval eval) {
        try {
            return (float) getFirst(result.getValue(resolver, eval));
        } catch (ClassCastException | NotInvokedException e) {
            throw new UnresolvedResultException(e, result);
        }
    }

    private static double getDouble(Result result, Resolver resolver, Eval eval) {
        try {
            return (double) getFirst(result.getValue(resolver, eval));
        } catch (ClassCastException | NotInvokedException e) {
            throw new UnresolvedResultException(e, result);
        }
    }

    private static Object getFirst(List<Object> value) {
        return value.isEmpty() ? null : value.get(0);
    }

    private static double invokeDouble(Result first, Result second, Resolver resolver, DoubleBinaryOperator add, Eval eval) {
        var value1 = getDouble(second, resolver, eval);
        var value2 = getDouble(first, resolver, eval);
        return add.applyAsDouble(value1, value2);
    }

    private static long invokeLong(Result first, Result second, Resolver resolver, LongBinaryOperator add, Eval eval) {
        var value1 = getLong(second, resolver, eval);
        var value2 = getLong(first, resolver, eval);
        return add.applyAsLong(value1, value2);
    }

    private static long unsignedShiftRight(Result first, Result second, Resolver resolver, Eval eval) {
        var value1 = getLong(second, resolver, eval);
        var s = s(first, resolver, eval);
        return value1 >>> s;
    }

    private static long shiftRight(Result first, Result second, Resolver resolver, Eval eval) {
        var value1 = getLong(second, resolver, eval);
        var s = s(first, resolver, eval);
        return value1 >> s;
    }

    private static long shiftLeft(Result first, Result second, Resolver resolver, Eval eval) {
        var value1 = getLong(second, resolver, eval);
        var s = s(first, resolver, eval);
        return value1 << s;
    }

    private static long s(Result result, Resolver resolver, Eval eval) {
        var value2 = getLong(result, resolver, eval);
        var s = value2 & 0X1f;
        return s;
    }
}
