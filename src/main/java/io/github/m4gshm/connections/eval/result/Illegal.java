package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.IllegalInvokeException;
import io.github.m4gshm.connections.model.Component;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.Set;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Illegal extends Result implements Result.PrevAware {
    Set<Status> status;
    Object element;
    Result prev;

    public Illegal(InstructionHandle firstInstruction, InstructionHandle lastInstruction, Set<Status> status,
                   Object element, Result prev) {
        super(firstInstruction, lastInstruction);
        this.status = status;
        this.element = element;
        this.prev = prev;
    }

    @Override
    public Object getValue() {
        throw new IllegalInvokeException(prev, firstInstruction);
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

    public enum Status {
        notAccessible, notFound, stub, illegalArgument, illegalTarget;
    }
}
