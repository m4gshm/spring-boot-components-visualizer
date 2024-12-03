package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class DelayLoadFromStore extends Delay {
    List<Result> storeInstructions;

    public DelayLoadFromStore(List<InstructionHandle> firstInstruction, List<InstructionHandle> lastInstruction,
                              Eval evalContext, String description, DelayFunction<DelayLoadFromStore> evaluator,
                              List<Result> storeInstructions, Type type) {
        super(firstInstruction, lastInstruction, evalContext, description, evaluator, storeInstructions, type, null);
        this.storeInstructions = storeInstructions;
    }

}
