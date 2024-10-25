package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.model.Component;
import lombok.Getter;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

@Getter
public class Duplicate extends Result implements ContextAware, Result.PrevAware {

    private final Result onDuplicate;
    private final Result prev;

    public Duplicate(InstructionHandle firstInstruction, InstructionHandle lastInstruction, Result onDuplicate, Result prev) {
        super(firstInstruction, lastInstruction);
        this.onDuplicate = onDuplicate;
        this.prev = prev;
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
    public Method getMethod() {
        return onDuplicate.getMethod();
    }

    @Override
    public Component getComponent() {
        return onDuplicate.getComponent();
    }

    @Override
    public String toString() {
        return "duplicate(" + onDuplicate + ")";
    }
}
