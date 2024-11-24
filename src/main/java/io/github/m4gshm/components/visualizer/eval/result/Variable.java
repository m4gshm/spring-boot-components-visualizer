package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.UnresolvedVariableException;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Variable extends Result implements ContextAware, TypeAware {
    VarType varType;
    Eval eval;
    int index;
    String name;
    Type type;
    Result prev;

    public Variable(InstructionHandle firstInstruction, InstructionHandle lastInstruction, VarType varType,
                    Eval eval, int index, String name, Type type, Result prev) {
        super(firstInstruction, lastInstruction);
        this.varType = varType;
        this.eval = eval;
        this.index = index;
        this.name = name;
        this.type = type;
        this.prev = prev;
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

    @RequiredArgsConstructor
    public enum VarType {
        MethodArg("methodArg"),
        LocalVar("localVar");

        private final String code;
    }
}
