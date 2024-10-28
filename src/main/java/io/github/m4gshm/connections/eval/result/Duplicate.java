package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.model.Component;
import lombok.Getter;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

@Getter
public class Duplicate extends Result implements ContextAware {

    private final Result onDuplicate;

    public Duplicate(InstructionHandle firstInstruction, InstructionHandle lastInstruction, Result onDuplicate) {
        super(firstInstruction, lastInstruction);
        this.onDuplicate = onDuplicate;
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
