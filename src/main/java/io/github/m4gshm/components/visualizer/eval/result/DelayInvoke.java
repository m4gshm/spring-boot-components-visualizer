package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.StreamUtils.ofNullable;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PROTECTED;

@FieldDefaults(makeFinal = true, level = PROTECTED)
public class DelayInvoke extends Delay {
    protected final Result object;
    protected final List<Result> arguments;

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

    public Result getObject() {
        return this.object;
    }

    public List<Result> getArguments() {
        return this.arguments;
    }
}
