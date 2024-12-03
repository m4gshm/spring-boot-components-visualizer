package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.ofNullable;
import static lombok.AccessLevel.PROTECTED;

@Getter
@FieldDefaults(makeFinal = true, level = PROTECTED)
public class DelayInvoke extends Delay {
    Result object;
    String className;
    String methodName;
    List<Result> arguments;

    public DelayInvoke(List<InstructionHandle> firstInstruction, List<InstructionHandle> lastInstruction, Eval evalContext,
                       String description, DelayFunction<DelayInvoke> evaluator,
                       Type type, Result object, String className, String methodName, List<Result> arguments) {
        super(firstInstruction, lastInstruction, evalContext, description, evaluator,
                Stream.of(ofNullable(object), arguments.stream()).flatMap(s -> s).collect(toList()), type, null);
        this.object = object;
        this.className = className;
        this.methodName = methodName;
        this.arguments = arguments;
    }

}
