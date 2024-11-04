package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.model.Component;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Constant extends Result implements ContextAware, Result.RelationsAware {
    private final Object value;
    private final Component component;
    private final Method method;
    private final List<Result> relations;
    private final Object resolvedBy;

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

    public Object getValue() {
        return this.value;
    }

    public Component getComponent() {
        return this.component;
    }

    public Method getMethod() {
        return this.method;
    }

    public List<Result> getRelations() {
        return this.relations;
    }

    public Object getResolvedBy() {
        return this.resolvedBy;
    }

    public boolean equals(final Object o) {
        if (o == this) return true;
        if (!(o instanceof Constant)) return false;
        final Constant other = (Constant) o;
        if (!other.canEqual((Object) this)) return false;
        if (!super.equals(o)) return false;
        final Object this$value = this.getValue();
        final Object other$value = other.getValue();
        if (this$value == null ? other$value != null : !this$value.equals(other$value)) return false;
        final Object this$component = this.getComponent();
        final Object other$component = other.getComponent();
        if (this$component == null ? other$component != null : !this$component.equals(other$component)) return false;
        final Object this$method = this.getMethod();
        final Object other$method = other.getMethod();
        if (this$method == null ? other$method != null : !this$method.equals(other$method)) return false;
        final Object this$relations = this.getRelations();
        final Object other$relations = other.getRelations();
        if (this$relations == null ? other$relations != null : !this$relations.equals(other$relations)) return false;
        final Object this$resolvedBy = this.getResolvedBy();
        final Object other$resolvedBy = other.getResolvedBy();
        if (this$resolvedBy == null ? other$resolvedBy != null : !this$resolvedBy.equals(other$resolvedBy))
            return false;
        return true;
    }

    protected boolean canEqual(final Object other) {
        return other instanceof Constant;
    }

    public int hashCode() {
        final int PRIME = 59;
        int result = super.hashCode();
        final Object $value = this.getValue();
        result = result * PRIME + ($value == null ? 43 : $value.hashCode());
        final Object $component = this.getComponent();
        result = result * PRIME + ($component == null ? 43 : $component.hashCode());
        final Object $method = this.getMethod();
        result = result * PRIME + ($method == null ? 43 : $method.hashCode());
        final Object $relations = this.getRelations();
        result = result * PRIME + ($relations == null ? 43 : $relations.hashCode());
        final Object $resolvedBy = this.getResolvedBy();
        result = result * PRIME + ($resolvedBy == null ? 43 : $resolvedBy.hashCode());
        return result;
    }
}
