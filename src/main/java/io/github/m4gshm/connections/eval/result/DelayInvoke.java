package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.EvalBytecode;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.ofNullable;
import static lombok.AccessLevel.PRIVATE;
import static lombok.AccessLevel.PROTECTED;

@Getter
@FieldDefaults(makeFinal = true, level = PROTECTED)
public class DelayInvoke extends Delay {
    Result object;
    List<Result> arguments;

    public DelayInvoke(InstructionHandle firstInstruction, InstructionHandle lastInstruction, EvalBytecode evalContext,
                       String description, DelayFunction<DelayInvoke> evaluator, Result prev,
                       Result object, List<Result> arguments) {
        super(firstInstruction, lastInstruction, evalContext, description, evaluator, prev, null, true, false);
        this.object = object;
        this.arguments = arguments;
    }

    @Override
    public List<Result> getRelations() {
        var relations = Stream.of(ofNullable(object), arguments.stream(), super.getRelations().stream()).flatMap(s -> s).collect(toList());
        return relations;
    }

    @Override
    public DelayInvoke withEval(EvalBytecode eval) {
        Object evaluator1 = evaluator;
        return new DelayInvoke(firstInstruction, lastInstruction, eval, description,
                (DelayFunction<DelayInvoke>) evaluator1, prev, object, arguments);
    }
}
