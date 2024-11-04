package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.var;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.Type;

import java.util.Arrays;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.getClassByName;
import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class MethodInfo {
    Class<?> objectType;
    String name;
    String signature;
    int referenceKind;

    public static MethodInfo newMethodInfo(ConstantMethodHandle constant, ConstantPool cp) {
        ConstantCP constantCP = cp.getConstant(constant.getReferenceIndex(), ConstantCP.class);
        if (constantCP instanceof ConstantMethodref || constantCP instanceof ConstantInterfaceMethodref) {
            ConstantNameAndType constantNameAndType = cp.getConstant(constantCP.getNameAndTypeIndex(), ConstantNameAndType.class);
            String methodName = constantNameAndType.getName(cp);
            String methodSignature = constantNameAndType.getSignature(cp);
            Class<?> targetClass = getClassByName(constantCP.getClass(cp));
            int referenceKind = constant.getReferenceKind();
            return newMethodInfo(targetClass, methodName, methodSignature, referenceKind);
        } else {
            return null;
        }
    }

    public static MethodInfo newMethodInfo(Class<?> objectClass, String methodName, String signature, int referenceKind) {
        return new MethodInfo(objectClass, methodName, signature, referenceKind);
    }

    @Override
    public String toString() {
        return objectType.getName() + "." + name + "(" + Arrays.stream(Type.getArgumentTypes(signature))
                .map(Type::getClassName).reduce((l, r) -> l + "," + r).orElse("") + ")";
    }
}
