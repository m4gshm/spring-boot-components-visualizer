package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.Type;

import java.util.Arrays;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.getClassByName;
import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class MethodInfo {
    String className;
    String name;
    String signature;
    int referenceKind;

    public static MethodInfo newMethodInfo(ConstantMethodHandle constant, ConstantPool constantPool) {
        var constantCP = constantPool.getConstant(constant.getReferenceIndex(), ConstantCP.class);
        if (constantCP instanceof ConstantMethodref || constantCP instanceof ConstantInterfaceMethodref) {
            var constantNameAndType = constantPool.getConstant(constantCP.getNameAndTypeIndex(), ConstantNameAndType.class);
            var methodName = constantNameAndType.getName(constantPool);
            var methodSignature = constantNameAndType.getSignature(constantPool);
            var className = constantCP.getClass(constantPool);
            var referenceKind = constant.getReferenceKind();
            return newMethodInfo(className, methodName, methodSignature, referenceKind);
        } else {
            return null;
        }
    }

    public static MethodInfo newMethodInfo(String className, String methodName, String signature, int referenceKind) {
        return new MethodInfo(className, methodName, signature, referenceKind);
    }

    @Override
    public String toString() {
        return getClassName() + "." + name + "(" + Arrays.stream(Type.getArgumentTypes(signature))
                .map(Type::getClassName).reduce((l, r) -> l + "," + r).orElse("") + ")";
    }
}
