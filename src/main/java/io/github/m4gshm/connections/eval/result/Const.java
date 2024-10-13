package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.EvalBytecode;
import io.github.m4gshm.connections.model.Component;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;
import java.util.Objects;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Const extends Result implements ContextAware, Result.RelationsAware {
    Object value;
    EvalBytecode evalContext;
    List<Result> relations;


    public Const(InstructionHandle firstInstruction, InstructionHandle lastInstruction, Object value, EvalBytecode evalContext, List<Result> relations) {
        super(firstInstruction, lastInstruction);
        this.value = value;
        this.evalContext = evalContext;
        this.relations = relations;
    }

    @Override
    public String toString() {
        return "const(" + value + ")";
    }

    @Override
    public Method getMethod() {
        return getEvalContext().getMethod();
    }

    @Override
    public Component getComponent() {
        return getEvalContext().getComponent();
    }

    @Override
    public boolean isResolved() {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }
        return Objects.equals(value, ((Const) o).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), value);
    }
}
