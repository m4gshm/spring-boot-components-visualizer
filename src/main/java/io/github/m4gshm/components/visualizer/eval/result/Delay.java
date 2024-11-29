package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalException;
import io.github.m4gshm.components.visualizer.eval.result.Result.RelationsAware;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.List;

import static lombok.AccessLevel.PROTECTED;

@Getter
@FieldDefaults(level = PROTECTED)
public class Delay extends Result implements ContextAware, RelationsAware, TypeAware {
    final Eval eval;
    final String description;
    final DelayFunction<Delay> evaluator;
    final List<Result> relations;
    final Type type;
    Result result;

    public Delay(InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                 Eval eval, String description, DelayFunction<? extends Delay> evaluator,
                 List<Result> relations, Type type, Result result) {
        super(firstInstruction, lastInstruction);
        this.eval = eval;
        this.description = description;
        this.evaluator = (DelayFunction<Delay>) evaluator;
        this.relations = relations;
        this.type = type;
        this.result = result;
    }

    @Override
    public Object getValue() {
        //todo must throw Exception
        var delayed = getDelayed(null);
        if (delayed == this) {
            throw new EvalException("looped delay 2");
        }
        return delayed.getValue();
    }

    public Result getDelayed(Resolver resolver) {
        var result = this.result;
        if (!isResolved()) {
            result = evaluator.call(this, resolver);
            if (result == this) {
                throw new EvalException("looped delay 1");
            }
            this.result = result;
        }
        return result;
    }

    public Delay withEval(Eval eval) {
        return new Delay(firstInstruction, lastInstruction, eval, description, evaluator, relations, type, null);
    }

    @Override
    public String toString() {
        var txt = description == null || description.isBlank() ? "" : description + ",";
        return "delay(" + txt + "resolved:" + isResolved() + ")";
    }

    @Override
    public boolean isResolved() {
        return result != null && result.isResolved();
    }

    @FunctionalInterface
    public interface DelayFunction<T extends Delay> {
        Result call(T delay, Resolver resolver);
    }
}
