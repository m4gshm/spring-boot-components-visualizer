package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.EvalBytecode;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.ofNullable;
import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class DelayInvoke extends Delay {
    Result object;
    List<Result> arguments;

    public DelayInvoke(EvalBytecode evalContext, String description, DelayFunction evaluator,
                       InstructionHandle firstInstruction, Result prev,
                       InstructionHandle lastInstruction, Result object,
                       List<Result> arguments) {
        super(firstInstruction, lastInstruction, evalContext, description, evaluator, prev, null, true, false);
        this.object = object;
        this.arguments = arguments;
    }

    @Override
    public List<Result> getRelations() {
        var relations = Stream.of(ofNullable(object), arguments.stream(), super.getRelations().stream()).flatMap(s -> s).collect(toList());
        return relations;
    }
}
