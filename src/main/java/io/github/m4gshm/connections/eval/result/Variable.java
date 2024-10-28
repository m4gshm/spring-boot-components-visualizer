package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.Eval;
import io.github.m4gshm.connections.eval.bytecode.UnresolvedVariableException;
import io.github.m4gshm.connections.model.Component;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Variable extends Result implements ContextAware {
    VarType varType;
    Eval evalContext;
    int index;
    String name;
    Type type;
    Result prev;

    public Variable(InstructionHandle firstInstruction, InstructionHandle lastInstruction, VarType varType,
                    Eval evalContext, int index, String name, Type type, Result prev) {
        super(firstInstruction, lastInstruction);
        this.varType = varType;
        this.evalContext = evalContext;
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
        var className = evalContext.getComponent().getType().getName();
        return varType.code + "(" + className + "." + methodName + "(" + getIndex() + " " + getName() + "))";
    }

    @Override
    public Method getMethod() {
        return evalContext.getMethod();
    }

    @Override
    public Component getComponent() {
        return evalContext.getComponent();
    }

    @Override
    public Class<?> getComponentType() {
        return evalContext.getComponent().getType();
    }

    @RequiredArgsConstructor
    public enum VarType {
        MethodArg("methodArg"),
        LocalVar("localVar");

        private final String code;
    }
}
