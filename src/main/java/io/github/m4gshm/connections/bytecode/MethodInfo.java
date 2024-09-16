package io.github.m4gshm.connections.bytecode;

import lombok.Data;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.Type;

import java.util.Arrays;

@Data
public class MethodInfo {
    public final Class<?> objectType;
    public final String name;
    public final String signature;

    public static MethodInfo newMethodInfo(ConstantMethodHandle constant, ConstantPool cp) {
        var constantCP = cp.getConstant(constant.getReferenceIndex(), ConstantCP.class);
        if (constantCP instanceof ConstantMethodref || constantCP instanceof ConstantInterfaceMethodref) {
            var constantNameAndType = cp.getConstant(constantCP.getNameAndTypeIndex(), ConstantNameAndType.class);
            var methodName = constantNameAndType.getName(cp);
            var methodSignature = constantNameAndType.getSignature(cp);
            var targetClass = EvalBytecodeUtils.getClassByName(constantCP.getClass(cp));
            return newMethodInfo(targetClass, methodName, methodSignature);
        } else {
            return null;
        }
    }

    public static MethodInfo newMethodInfo(Class<?> objectClass, String methodName, String signature) {
        return new MethodInfo(objectClass, methodName, signature);
    }

    public static MethodInfo newMethodInfo(EvalBytecode.Result.MethodArgument methodArgument) {
        return MethodInfo.newMethodInfo(methodArgument.getComponent().getType(),
                methodArgument.getMethod().getName(), methodArgument.getMethod().getSignature());
    }

    @Override
    public String toString() {
        return objectType.getName() + "." + name + "(" + Arrays.stream(Type.getArgumentTypes(signature))
                .map(Type::getClassName).reduce((l, r) -> l + "," + r).orElse("") + ")";
    }
}
