package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.model.Component;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
@EqualsAndHashCode(callSuper = true)
public class Constant extends Result implements ContextAware, Result.RelationsAware {
    Object value;
    Component component;
    Method method;
    List<Result> relations;
    Object resolvedBy;

    public Constant(InstructionHandle firstInstruction, InstructionHandle lastInstruction, Object value,
                    List<Result> relations, Component component, Method method, Object resolvedBy) {
        super(firstInstruction, lastInstruction);
        this.value = value;
        this.method = method;
        this.component = component;
        this.relations = relations;
        this.resolvedBy = resolvedBy;
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
