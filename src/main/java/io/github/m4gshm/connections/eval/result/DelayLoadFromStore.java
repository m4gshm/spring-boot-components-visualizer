package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.Eval;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class DelayLoadFromStore extends Delay {
    List<Result> storeInstructions;

    public DelayLoadFromStore(InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                              Eval evalContext, String description, DelayFunction<DelayLoadFromStore> evaluator,
                              Result prev, List<Result> storeInstructions) {
        super(firstInstruction, lastInstruction, evalContext, description, evaluator, prev, storeInstructions, null);
        this.storeInstructions = storeInstructions;
    }

    @Override
    public Delay withEval(Eval eval) {
        Object evaluator1 = evaluator;
        return new DelayLoadFromStore(firstInstruction, lastInstruction, eval, description,
                (DelayFunction<DelayLoadFromStore>) evaluator1, prev, storeInstructions);
    }
}
