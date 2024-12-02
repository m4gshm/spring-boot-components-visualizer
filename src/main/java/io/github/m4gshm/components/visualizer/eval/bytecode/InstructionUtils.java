package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

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
}
