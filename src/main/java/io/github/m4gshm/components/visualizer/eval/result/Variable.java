package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.UnresolvedVariableException;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.experimental.FieldDefaults;
import lombok.var;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import static lombok.AccessLevel.PRIVATE;

@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Variable extends Result implements ContextAware {
    private final VarType varType;
    private final Eval evalContext;
    private final int index;
    private final String name;
    private final Type type;
    private final Result prev;

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

    public VarType getVarType() {
        return this.varType;
    }

    public Eval getEvalContext() {
        return this.evalContext;
    }

    public int getIndex() {
        return this.index;
    }

    public String getName() {
        return this.name;
    }

    public Type getType() {
        return this.type;
    }

    public Result getPrev() {
        return this.prev;
    }

    public enum VarType {
        MethodArg("methodArg"),
        LocalVar("localVar");

        private final String code;

        private VarType(String code) {
            this.code = code;
        }
    }
}
