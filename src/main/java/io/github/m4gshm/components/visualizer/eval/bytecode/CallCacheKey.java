package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval.ParameterValue;
import io.github.m4gshm.components.visualizer.eval.result.Delay;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.Instruction;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class CallCacheKey {
    Delay call;
    List<List<ParameterValue>> parametersVariants;
    Instruction lastInstruction;
//    Resolver resolver;
}
