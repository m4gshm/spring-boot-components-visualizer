package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
@EqualsAndHashCode(callSuper = true)
public class Constant extends Result implements ContextAware, Result.RelationsAware, TypeAware {
    Object value;
    List<Result> relations;
    Eval eval;
    Object resolvedBy;
    Type type;

    public Constant(InstructionHandle firstInstruction, InstructionHandle lastInstruction, Object value,
                    List<Result> relations, Eval eval, Object resolvedBy, Type type) {
        super(firstInstruction, lastInstruction);
        this.value = value;
        this.relations = relations;
        this.eval = eval;
        this.resolvedBy = resolvedBy;
        this.type = type;
    }

    @Override
    public String toString() {
        return "const(" + value + ")";
    }

    @Override
    public boolean isResolved() {
        return true;
    }

}
