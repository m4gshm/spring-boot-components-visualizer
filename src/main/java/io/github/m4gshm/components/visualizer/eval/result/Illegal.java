package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.IllegalInvokeException;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.Set;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Illegal extends Result {
    Set<Status> status;
    Object target;
    Result prev;
    Eval eval;

    public Illegal(InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                   Set<Status> status, Object target, Result prev, Eval eval) {
        super(firstInstruction, lastInstruction);
        this.status = status;
        this.target = target;
        this.prev = prev;
        this.eval = eval;
    }

    @Override
    public Object getValue() {
        throw new IllegalInvokeException(prev, firstInstruction, target);
    }

    @Override
    public boolean isResolved() {
        return false;
    }

    public enum Status {
        notAccessible, notFound, stub, illegalArgument, illegalTarget;
    }
}
