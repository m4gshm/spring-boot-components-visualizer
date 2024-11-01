package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.ofNullable;
import static lombok.AccessLevel.PROTECTED;

@Getter
@FieldDefaults(makeFinal = true, level = PROTECTED)
public class DelayInvoke extends Delay {
    Result object;
    List<Result> arguments;

    public DelayInvoke(InstructionHandle firstInstruction, InstructionHandle lastInstruction, Eval evalContext,
                       String description, DelayFunction<DelayInvoke> evaluator, Result prev,
                       Result object, List<Result> arguments) {
        super(firstInstruction, lastInstruction, evalContext, description, evaluator, prev,
                Stream.of(ofNullable(object), arguments.stream()).flatMap(s -> s).collect(toList()), null);
        this.object = object;
        this.arguments = arguments;
    }

    @Override
    public DelayInvoke withEval(Eval eval) {
        return new DelayInvoke(firstInstruction, lastInstruction, eval, description,
                (DelayFunction<DelayInvoke>) (Object) evaluator, prev, object, arguments);
    }
}
