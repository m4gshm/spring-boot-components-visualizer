package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Delay;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.Instruction;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class CallCacheKey {
    Delay call;
    List<List<Eval.ParameterValue>> parametersVariants;
    Instruction lastInstruction;
}
