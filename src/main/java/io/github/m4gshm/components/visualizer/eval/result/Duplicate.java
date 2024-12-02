package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

@Getter
@EqualsAndHashCode
public class Duplicate extends Result implements ContextAware {

    private final Result onDuplicate;
    private final Eval eval;

    public Duplicate(InstructionHandle firstInstruction, InstructionHandle lastInstruction, Result onDuplicate, Eval eval) {
        super(firstInstruction, lastInstruction);
        this.onDuplicate = onDuplicate;
        this.eval = eval;
    }

    @Override
    public Object getValue() {
        return onDuplicate.getValue();
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
