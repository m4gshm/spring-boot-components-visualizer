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
    public static Object computeArithmetic(ArithmeticInstruction instruction, Result first, Result second, Resolver resolver) {
        var opcode = instruction.getOpcode();
        switch (opcode) {
            case DADD:
                return invokeDouble(first, second, resolver, (a, b) -> a + b);
            case DDIV:
                return invokeDouble(first, second, resolver, (a, b) -> a / b);
            case DMUL:
                return invokeDouble(first, second, resolver, (a, b) -> a * b);
            case DNEG:
                return -getDouble(first, resolver);
            case DREM:
                return invokeDouble(first, second, resolver, (a, b) -> a % b);
            case DSUB:
                return invokeDouble(first, second, resolver, (a, b) -> a - b);
            case FADD:
                return (float) invokeDouble(first, second, resolver, (a, b) -> a + b);
            case FDIV:
                return (float) invokeDouble(first, second, resolver, (a, b) -> a / b);
            case FMUL:
                return (float) invokeDouble(first, second, resolver, (a, b) -> a * b);
            case FNEG:
                return -getFloat(first, resolver);
            case FREM:
                return (float) invokeDouble(first, second, resolver, (a, b) -> a % b);
            case FSUB:
                return (float) invokeDouble(first, second, resolver, (a, b) -> a - b);
            case IADD:
                return (int) invokeLong(first, second, resolver, (a, b) -> a + b);
            case IAND:
                return (int) invokeLong(first, second, resolver, (a, b) -> a & b);
            case IDIV:
                return (int) invokeLong(first, second, resolver, (a, b) -> a / b);
            case IMUL:
                return (int) invokeLong(first, second, resolver, (a, b) -> a * b);
            case INEG:
                return -getInt(first, resolver);
            case IOR:
                return (int) invokeLong(first, second, resolver, (a, b) -> a | b);
            case IREM:
                return (int) invokeLong(first, second, resolver, (a, b) -> a % b);
            case ISHL:
                return (int) shiftLeft(first, second, resolver);
            case ISHR:
                return (int) shiftRight(first, second, resolver);
            case ISUB:
                return (int) invokeLong(first, second, resolver, (a, b) -> a - b);
            case IUSHR:
                return (int) unsignedShiftRight(first, second, resolver);
            case IXOR:
                return (int) invokeLong(first, second, resolver, (a, b) -> a ^ b);
            case LADD:
                return invokeLong(first, second, resolver, (a, b) -> a + b);
            case LAND:
                return invokeLong(first, second, resolver, (a, b) -> a & b);
            case LDIV:
                return invokeLong(first, second, resolver, (a, b) -> a / b);
            case LMUL:
                return invokeLong(first, second, resolver, (a, b) -> a * b);
            case LNEG:
                return -getLong(first, resolver);
            case LOR:
                return invokeLong(first, second, resolver, (a, b) -> a | b);
            case LREM:
                return invokeLong(first, second, resolver, (a, b) -> a % b);
            case LSHL:
                return shiftLeft(first, second, resolver);
            case LSHR:
                return shiftRight(first, second, resolver);
            case LSUB:
                return invokeLong(first, second, resolver, (a, b) -> a - b);
            case LUSHR:
                return unsignedShiftRight(first, second, resolver);
            case LXOR:
                return invokeLong(first, second, resolver, (a, b) -> a ^ b);
            default:
                throw new IllegalStateException("unsupported arithmetic op " + opcode);
        }
    }

    private static int getInt(Result result, Resolver resolver) {
        try {
            return (int) getFirst(result.getValue(resolver));
        } catch (ClassCastException | NotInvokedException e) {
            throw new UnresolvedResultException(e, result);
        }
    }

    private static long getLong(Result result, Resolver resolver) {
        try {
            return (long) getFirst(result.getValue(resolver));
        } catch (ClassCastException | NotInvokedException e) {
            throw new UnresolvedResultException(e, result);
        }
    }

    private static float getFloat(Result result, Resolver resolver) {
        try {
            return (float) getFirst(result.getValue(resolver));
        } catch (ClassCastException | NotInvokedException e) {
            throw new UnresolvedResultException(e, result);
        }
    }

    private static double getDouble(Result result, Resolver resolver) {
        try {
            return (double) getFirst(result.getValue(resolver));
        } catch (ClassCastException | NotInvokedException e) {
            throw new UnresolvedResultException(e, result);
        }
    }

    private static Object getFirst(List<Object> value) {
        return value.isEmpty() ? null : value.get(0);
    }

    private static double invokeDouble(Result first, Result second, Resolver resolver, DoubleBinaryOperator add) {
        var value1 = getDouble(second, resolver);
        var value2 = getDouble(first, resolver);
        return add.applyAsDouble(value1, value2);
    }

    private static long invokeLong(Result first, Result second, Resolver resolver, LongBinaryOperator add) {
        var value1 = getLong(second, resolver);
        var value2 = getLong(first, resolver);
        return add.applyAsLong(value1, value2);
    }

    private static long unsignedShiftRight(Result first, Result second, Resolver resolver) {
        var value1 = getLong(second, resolver);
        var s = s(first, resolver);
        return value1 >>> s;
    }

    private static long shiftRight(Result first, Result second, Resolver resolver) {
        var value1 = getLong(second, resolver);
        var s = s(first, resolver);
        return value1 >> s;
    }

    private static long shiftLeft(Result first, Result second, Resolver resolver) {
        var value1 = getLong(second, resolver);
        var s = s(first, resolver);
        return value1 << s;
    }

    private static long s(Result result, Resolver resolver) {
        var value2 = getLong(result, resolver);
        var s = value2 & 0X1f;
        return s;
    }
}
