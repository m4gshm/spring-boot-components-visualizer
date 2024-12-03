package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalException;
import io.github.m4gshm.components.visualizer.eval.result.Result.RelationsAware;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.List;
import java.util.Objects;

import static lombok.AccessLevel.PROTECTED;
import static org.springframework.util.Assert.state;

@Getter
@FieldDefaults(level = PROTECTED)
public class Delay extends Result implements ContextAware, RelationsAware, TypeAware {
    final Eval eval;
    final String description;
    final DelayFunction<Delay> evaluator;
    final List<Result> relations;
    final Type type;

    public Delay(List<InstructionHandle> firstInstruction, List<InstructionHandle> lastInstruction,
                 Eval eval, String description, DelayFunction<? extends Delay> evaluator,
                 List<Result> relations, Type type, Result result) {
        super(firstInstruction, lastInstruction);
        this.eval = eval;
        this.description = description;
        this.evaluator = (DelayFunction<Delay>) evaluator;
        this.relations = relations;
        this.type = type;
    }

    @Override
    public Object getValue() {
        //todo must throw Exception
        var delayed = getDelayed(getEval(), null);
        if (delayed == this) {
            throw new EvalException("looped delay 2");
        }
        return delayed.getValue();
    }

    public Result getDelayed(Eval eval, Resolver resolver) {
        state(this.getEval().equals(eval));
        var result = evaluator.call(this, eval, resolver);
        if (result == this) {
            throw new EvalException("looped delay 1");
        }
        return result;
    }

    @Override
    public String toString() {
        var txt = description == null || description.isBlank() ? "" : description + ",";
        return "delay(" + txt + "resolved:" + isResolved() + ")";
    }

    @Override
    public boolean isResolved() {
        return false;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) return false;
        if (!super.equals(object)) return false;
        Delay delay = (Delay) object;
        return Objects.equals(eval, delay.eval)
                && Objects.equals(relations, delay.relations)
                && Objects.equals(type, delay.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), eval, relations, type);
    }

    @FunctionalInterface
    public interface DelayFunction<T extends Delay> {
        Result call(T delay, Eval eval, Resolver resolver);
    }
}
