package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;

@Getter
@EqualsAndHashCode(callSuper = true)
public class Duplicate extends Result implements ContextAware {
    private final Result onDuplicate;

    public Duplicate(List<InstructionHandle> firstInstruction, List<InstructionHandle> lastInstruction, Result onDuplicate) {
        super(firstInstruction, lastInstruction);
        this.onDuplicate = onDuplicate;
    }

    @Override
    public Object getValue() {
        return onDuplicate.getValue();
    }

    @Override
    public List<Object> getValue(Resolver resolver, Eval eval) {
        return onDuplicate.getValue(resolver, eval);
    }

    @Override
    public List<InstructionHandle> getLastInstructions() {
        return onDuplicate.getLastInstructions();
    }

    @Override
    public Eval getEval() {
        return onDuplicate.getEval();
    }

    @Override
    public boolean isResolved() {
        return onDuplicate.isResolved();
    }

    @Override
    public String toString() {
        return "duplicate(" + onDuplicate + ")";
    }
}
