package io.github.m4gshm.connections.eval.result;

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
public class Constant extends Result implements ContextAware, Result.RelationsAware {
    Object value;
    List<Result> relations;
    Component component;
    Method method;


    public Constant(InstructionHandle firstInstruction, InstructionHandle lastInstruction, Object value,
                    List<Result> relations, Component component, Method method) {
        super(firstInstruction, lastInstruction);
        this.value = value;
        this.method = method;
        this.component = component;
        this.relations = relations;
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
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }
        return Objects.equals(value, ((Constant) o).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), value);
    }
}
