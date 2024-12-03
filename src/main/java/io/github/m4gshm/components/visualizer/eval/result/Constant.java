package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.result.Result.RelationsAware;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.List;
import java.util.Objects;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Constant extends Result implements ContextAware, RelationsAware, TypeAware {
    Object value;
    List<Result> relations;
    Eval eval;
    Object resolvedBy;
    Type type;

    public Constant(List<InstructionHandle> firstInstruction, List<InstructionHandle> lastInstruction, Object value,
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

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        if (!super.equals(object)) return false;
        Constant constant = (Constant) object;
        return Objects.equals(value, constant.value)
                && Objects.equals(relations, constant.relations)
                && Objects.equals(eval, constant.eval)
                && Objects.equals(resolvedBy, constant.resolvedBy)
                && Objects.equals(type, constant.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), value, relations, eval, resolvedBy, type);
    }
}
