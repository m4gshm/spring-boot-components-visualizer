package io.github.m4gshm.connections.bytecode;

import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.Type;

import java.util.Arrays;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class MethodInfo {
    Class<?> objectType;
    String name;
    String signature;
    int referenceKind;

    public static MethodInfo newMethodInfo(ConstantMethodHandle constant, ConstantPool cp) {
        var constantCP = cp.getConstant(constant.getReferenceIndex(), ConstantCP.class);
        if (constantCP instanceof ConstantMethodref || constantCP instanceof ConstantInterfaceMethodref) {
            var constantNameAndType = cp.getConstant(constantCP.getNameAndTypeIndex(), ConstantNameAndType.class);
            var methodName = constantNameAndType.getName(cp);
            var methodSignature = constantNameAndType.getSignature(cp);
            var targetClass = EvalBytecodeUtils.getClassByName(constantCP.getClass(cp));
            var referenceKind = constant.getReferenceKind();
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
