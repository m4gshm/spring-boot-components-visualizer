package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.EvalBytecode;
import io.github.m4gshm.connections.eval.bytecode.EvalBytecodeException;
import io.github.m4gshm.connections.eval.result.Result.PrevAware;
import io.github.m4gshm.connections.eval.result.Result.RelationsAware;
import io.github.m4gshm.connections.model.Component;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;

import static lombok.AccessLevel.PROTECTED;

@Getter
@FieldDefaults(level = PROTECTED)
public class Delay extends Result implements ContextAware, PrevAware, RelationsAware {
    final EvalBytecode eval;
    final String description;
    final DelayFunction<Delay> evaluator;
    final Result prev;
    final Component component;
    final Method method;
    final Class<?> componentType;
    Result result;
    boolean evaluated;
    boolean resolved;

    public Delay(InstructionHandle firstInstruction, InstructionHandle lastInstruction, EvalBytecode eval,
                 String description, DelayFunction<? extends Delay> evaluator, Result prev, Result result,
                 boolean evaluated, boolean resolved) {
        super(firstInstruction, lastInstruction);
        this.eval = eval;
        this.description = description;
        this.evaluator = (DelayFunction<Delay>) evaluator;
        this.prev = prev;
        this.result = result;
        this.evaluated = evaluated;
        this.resolved = resolved;
        component = eval.getComponent();
        method = eval.getMethod();
        componentType = eval.getComponent().getType();
    }

    @Override
    public Object getValue() {
        var delayed = getDelayed(true, null);
        if (delayed == this) {
            throw new EvalBytecodeException("looped delay 2");
        }
        return delayed.getValue();
    }

    public InstructionHandle getLastInstruction() {
        if (evaluated) {
            return lastInstruction;
        }
        var delayed = getDelayed(false, null);
        return delayed.getLastInstruction();
    }

    public Result getDelayed(boolean resolve, Resolver resolver) {
        var result = this.result;
        var evaluate = !resolve;
        if (resolve && !resolved) {
            result = evaluator.call(this, true, resolver);
            if (result == this) {
                throw new EvalBytecodeException("looped delay 1");
            }
            this.result = result;
            this.resolved = true;
            this.evaluated = true;
        } else if (evaluate && !evaluated) {
            result = evaluator.call(this, false, resolver);
            this.result = result;
            this.evaluated = true;
        }
        return result;
    }

    public Delay evaluated(InstructionHandle lastInstruction) {
        return new Delay(firstInstruction, lastInstruction, eval, description, evaluator, prev, null, true, false);
    }

    public Delay withEval(EvalBytecode eval) {
        return new Delay(firstInstruction, lastInstruction, eval, description, evaluator, prev, null, true, false);
    }

    @Override
    public String toString() {
        var txt = description == null || description.isBlank() ? "" : description + ",";
        return "delay(" + txt + "evaluated:" + evaluated + ", resolved:" + resolved + ")";
    }

    @Override
    public Method getMethod() {
        return method;
    }

    @Override
    public Component getComponent() {
        return component;
    }

    @Override
    public Class<?> getComponentType() {
        return componentType;
    }

    @Override
    public List<Result> getRelations() {
        return List.of();
    }

    @FunctionalInterface
    public interface DelayFunction<T extends Delay> {
        Result call(T delay, Boolean needResolve, Resolver resolver);
    }
}
