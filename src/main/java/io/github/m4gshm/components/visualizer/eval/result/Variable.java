package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.UnresolvedVariableException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.Objects;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Variable extends Result implements ContextAware, TypeAware {
    VarType varType;
    Eval eval;
    int index;
    String name;
    Type type;

    public Variable(InstructionHandle firstInstruction, InstructionHandle lastInstruction, VarType varType,
                    Eval eval, int index, String name, Type type) {
        super(getInstructions(firstInstruction), getInstructions(lastInstruction));
        this.varType = varType;
        this.eval = eval;
        this.index = index;
        this.name = name;
        this.type = type;
    }

    @Override
    public Object getValue() {
        throw new UnresolvedVariableException("unresolved variable", this);
    }

    @Override
    public boolean isResolved() {
        return false;
    }

    @Override
    public String toString() {
        var methodName = getMethod().getName();
        var className = getComponentType();
        return varType.code + "(" + className + "." + methodName + "(" + getIndex() + " " + getName() + "))";
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        if (!super.equals(object)) return false;
        var variable = (Variable) object;
        return index == variable.index && varType == variable.varType
                && Objects.equals(eval, variable.eval)
                && Objects.equals(name, variable.name)
                && Objects.equals(type, variable.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), varType, eval, index, name, type);
    }

    @RequiredArgsConstructor
    public enum VarType {
        MethodArg("methodArg"),
        LocalVar("localVar");

        private final String code;
    }
}
