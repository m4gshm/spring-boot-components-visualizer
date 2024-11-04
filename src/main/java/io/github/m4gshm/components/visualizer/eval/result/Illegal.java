package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.IllegalInvokeException;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.Set;

import static lombok.AccessLevel.PRIVATE;

@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Illegal extends Result {
    private final Set<Status> status;
    private final Object target;
    private final Result prev;

    public Illegal(InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                   Set<Status> status, Object target, Result prev) {
        super(firstInstruction, lastInstruction);
        this.status = status;
        this.target = target;
        this.prev = prev;
    }

    @Override
    public Object getValue() {
        throw new IllegalInvokeException(prev, firstInstruction, target);
    }

    @Override
    public boolean isResolved() {
        return false;
    }

    @Override
    public Method getMethod() {
        return prev.getMethod();
    }

    @Override
    public Component getComponent() {
        return prev.getComponent();
    }

    public Set<Status> getStatus() {
        return this.status;
    }

    public Object getTarget() {
        return this.target;
    }

    public Result getPrev() {
        return this.prev;
    }

    public enum Status {
        notAccessible, notFound, stub, illegalArgument, illegalTarget;
    }
}
