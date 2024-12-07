package io.github.m4gshm.components.visualizer.eval.bytecode;

import com.google.common.base.Supplier;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.stream.Stream.ofNullable;
import static java.util.stream.StreamSupport.stream;

@UtilityClass
public class InstructionUtils {

    @SneakyThrows
    public static boolean equals(Set<Instruction> thisOps, Set<Instruction> thatOps) {
        return thisOps.equals(thatOps);
    }

    @SneakyThrows
    public static boolean equals(InstructionHandle thisOp, InstructionHandle thatOp) {
        return equals(
                thisOp != null ? thisOp.getInstruction() : null,
                thatOp != null ? thatOp.getInstruction() : null
        );
    }

    @SneakyThrows
    public static boolean equals(Instruction thisInst, Instruction thatInst) {
        if (thisInst == null && thatInst == null) {
            return true;
        } else if (thisInst == null || thatInst == null) {
            return false;
        }

        var thisBytes = new ByteArrayOutputStream();
        var thatBytes = new ByteArrayOutputStream();
        thisInst.dump(new DataOutputStream(thisBytes));
        thatInst.dump(new DataOutputStream(thatBytes));
        var thisBytesByteArray = thisBytes.toByteArray();
        var thatBytesByteArray = thatBytes.toByteArray();
        return Arrays.equals(thisBytesByteArray, thatBytesByteArray);
    }

    public static Stream<InstructionHandle> instructions(Method method) {
        return ofNullable(method.getCode()).map(Code::getCode).flatMap(bc -> instructions(new InstructionList(bc)));
    }

    public static Stream<InstructionHandle> instructions(InstructionList instructionHandles) {
        return stream(instructionHandles.spliterator(), false);
    }


    @UtilityClass
    public static class Filter {

        public static <T extends Instruction> Predicate<InstructionHandle> byType(
                Class<? extends T> opType, BiPredicate<InstructionHandle, ? super T> filter
        ) {
            return handle -> {
                var instruction = handle.getInstruction();
                if (opType.isAssignableFrom(instruction.getClass())) {
                    return filter.test(handle, opType.cast(instruction));
                }
                return false;
            };
        }
    }


    @UtilityClass
    public static class Mapper {

        public static <T extends Instruction, R> Function<InstructionHandle, R> ofClass(
                Class<? extends T> opType, BiFunction<InstructionHandle, ? super T, R> mapper, Supplier<R> unmatched
        ) {
            return handle -> {
                var instruction = handle.getInstruction();
                if (opType.isAssignableFrom(instruction.getClass())) {
                    return mapper.apply(handle, opType.cast(instruction));
                }
                return unmatched.get();
            };
        }
    }
}
