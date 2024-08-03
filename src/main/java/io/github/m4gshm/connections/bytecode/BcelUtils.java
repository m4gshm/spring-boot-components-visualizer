package io.github.m4gshm.connections.bytecode;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.ConstantInvokeDynamic;
import org.apache.bcel.classfile.ConstantMethodHandle;
import org.apache.bcel.classfile.ConstantMethodType;
import org.apache.bcel.classfile.ConstantMethodref;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ALOAD;
import org.apache.bcel.generic.ASTORE;
import org.apache.bcel.generic.CHECKCAST;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.GETFIELD;
import org.apache.bcel.generic.INVOKEDYNAMIC;
import org.apache.bcel.generic.INVOKEINTERFACE;
import org.apache.bcel.generic.INVOKESTATIC;
import org.apache.bcel.generic.INVOKEVIRTUAL;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.InvokeInstruction;
import org.apache.bcel.generic.LDC;
import org.apache.bcel.generic.ReturnInstruction;
import org.apache.bcel.generic.Type;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ReflectionUtils.getDeclaredField;
import static io.github.m4gshm.connections.ReflectionUtils.getDeclaredMethod;
import static io.github.m4gshm.connections.bytecode.BcelUtils.CallResult.Status.notAccessible;
import static io.github.m4gshm.connections.bytecode.BcelUtils.CallResult.Status.notFound;
import static io.github.m4gshm.connections.bytecode.BcelUtils.CallResult.notAccessible;
import static io.github.m4gshm.connections.bytecode.BcelUtils.CallResult.notFound;
import static io.github.m4gshm.connections.bytecode.BcelUtils.CallResult.success;
import static io.github.m4gshm.connections.bytecode.UnsupportedEvalException.newUnsupportedEvalException;
import static java.util.stream.Collectors.toList;
import static org.apache.bcel.Const.CONSTANT_InvokeDynamic;
import static org.apache.bcel.Const.CONSTANT_NameAndType;
import static org.apache.bcel.Const.REF_invokeSpecial;
import static org.aspectj.apache.bcel.Constants.CONSTANT_MethodHandle;
import static org.aspectj.apache.bcel.Constants.CONSTANT_Methodref;

@Slf4j
@UtilityClass
public class BcelUtils {

    public static CallResult getDoHandshakeUri(
            Object object, InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen, Method method,
            BootstrapMethods bootstrapMethods
    ) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        Code code = method.getCode();
        CallResult value;
        InvokeInstruction instruction = (InvokeInstruction) instructionHandle.getInstruction();
        Type[] argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        if (argumentTypes.length == 3) {
            var argumentType = argumentTypes[2];
            var uriFound = URI.class.getName().equals(argumentType.getClassName());
            //trying to found uri push to stack

            var prev = instructionHandle.getPrev();
            var prevInstruction = prev.getInstruction();
            String stringInstr = prevInstruction.toString(constantPoolGen.getConstantPool());
            if (prevInstruction instanceof INVOKESTATIC) {
                var prevInvoke = (INVOKESTATIC) prevInstruction;
                if (isUriCreate(prevInvoke, constantPoolGen)) {
                    value = eval(object, prev.getPrev(), constantPoolGen, bootstrapMethods);
                } else {
                    value = eval(object, prev, constantPoolGen, bootstrapMethods);
                }
            } else {
                value = eval(object, prev, constantPoolGen, bootstrapMethods);
                var result = value.getResult();
                if (result instanceof URI) {
                    var uri = (URI) result;
                    value = success(uri.toString(), /*todo ???*/null, null);
                } else {
                    //log
                    value = success(result.toString(), /*todo ???*/null, null);
                }
            }
        } else {
            //log
            throw new UnsupportedOperationException("getDoHandshakeUri argumentTypes.length mismatch, " + argumentTypes.length);
        }
        return value;
    }

    public static InstructionHandle goToReturn(InstructionList instructionList) {
        InstructionHandle end = instructionList.getEnd();
        while (!(end.getInstruction() instanceof ReturnInstruction)) {
            end = end.getPrev();
        }
        return end;
    }

    public static Method getMethod(JavaClass javaClass, String methodName) {
        return Stream.of(javaClass.getMethods()).filter(method -> method.getName().equals(methodName)).findFirst()
                .orElseThrow(() -> new RuntimeException("method not found, '" + methodName + "'"));
    }

    public static CallResult<Object> eval(
            Object object, InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods
    ) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            return success(ldc.getValue(constantPoolGen), instructionHandle, instructionHandle);
        } else if (instruction instanceof ALOAD) {
            var aload = (ALOAD) instruction;
            var aloadIndex = aload.getIndex();
            if (aloadIndex == 0) {
                //this ???
                return success(object, instructionHandle, instructionHandle);
            } else {
                var prev = instructionHandle.getPrev();
                while (prev != null) {
                    if (prev.getInstruction() instanceof ASTORE) {
                        var astore = (ASTORE) prev.getInstruction();
                        if (astore.getIndex() == aloadIndex) {
                            var storedInLocal = eval(object, prev, constantPoolGen, bootstrapMethods);
                            return success(storedInLocal.getResult(), instructionHandle, instructionHandle);
                        }
                    }
                    prev = prev.getPrev();
                }
            }
        } else if (instruction instanceof ASTORE) {
            return eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;

            var evalFieldOwnedObject = eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
            var fieldName = getField.getFieldName(constantPoolGen);
            var filedOwnedObject = evalFieldOwnedObject.getResult();
            return getFieldValue(filedOwnedObject, fieldName, instructionHandle, evalFieldOwnedObject.getLastInstruction());
        } else if (instruction instanceof CHECKCAST) {
            return eval(object, instructionHandle.getPrev(), constantPoolGen, bootstrapMethods);
        } else if (instruction instanceof InvokeInstruction) {
            return getMethodResult(object, instructionHandle, (InvokeInstruction) instruction, constantPoolGen, bootstrapMethods);
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }


    public static CallResult<Object> getMethodResult(
            Object object, InstructionHandle instructionHandle, InvokeInstruction instruction, ConstantPoolGen constantPoolGen,
            BootstrapMethods bootstrapMethods) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = getArgumentTypes(instruction.getArgumentTypes(constantPoolGen));
        var evalArgumentsResult = evalArguments(object, instructionHandle, argumentTypes.length, constantPoolGen, bootstrapMethods);
        var arguments = evalArgumentsResult.getResult();
        if (instruction instanceof INVOKEVIRTUAL) {
            var next = evalArgumentsResult.getInstructionHandle().getPrev();
            var objectCallResult = eval(object, next, constantPoolGen, bootstrapMethods);
            var obj = objectCallResult.getResult();
            return callMethod(obj, obj.getClass(), methodName, argumentTypes, arguments,
                    instructionHandle, objectCallResult.getLastInstruction(), constantPoolGen);
        } else if (instruction instanceof INVOKEINTERFACE) {
            var next = evalArgumentsResult.getInstructionHandle().getPrev();
            var objectCallResult = eval(object, next, constantPoolGen, bootstrapMethods);
            var obj = objectCallResult.getResult();
            return callMethod(obj, getClassByName(instruction.getClassName(constantPoolGen)), methodName, argumentTypes, arguments,
                    instructionHandle, objectCallResult.getLastInstruction(), constantPoolGen);
        } else if (instruction instanceof INVOKEDYNAMIC) {
            var cp = constantPoolGen.getConstantPool();
            var methodInfo = getBootstrapMethod(object, instruction, bootstrapMethods, cp);

//            var methodName1 = methodInfo.getName();
//            var argumentTypes1 = methodInfo.getArgumentTypes();


//            java.lang.invoke.LambdaMetafactory.metafactory(
//                    MethodHandles.lookup(),
//                    "get",
//                    MethodType.genericMethodType(0),
//
//            )

            //1: invokeStatic java.lang.invoke.LambdaMetafactory.metafactory (
            // Ljava.lang.invoke.MethodHandles$Lookup;
            // Ljava.lang.String;
            // Ljava.lang.invoke.MethodType;
            // Ljava.lang.invoke.MethodType;
            // Ljava.lang.invoke.MethodHandle;
            // Ljava.lang.invoke.MethodType;
            // )Ljava.lang.invoke.CallSite;
            //     Method Arguments:
            //       0: ()Ljava/lang/Object;
            //       1: invokeSpecial service1.service.external.ws.Service2StreamClientImpl.lambda$subscribe$0 ()Ljava/net/URI;
            //       2: ()Ljava/net/URI;

//            MethodHandles.lookup()
//            Function<String, Boolean> f = (Function<String, Boolean>) LambdaMetafactory.metafactory(
//                            lookup,
//                            "apply",
//                            MethodType.methodType(Function.class),
//                            methodType.generic(),
//                            handle,
//                            methodType)
//                    .getTarget()
//                    .invokeExact();


//            var evalArgumentsResult1 = evalArguments(object, instructionHandle, argumentTypes1.length,
//                    constantPoolGen, bootstrapMethods);
////            var next = evalArgumentsResult.getNextOnEval();
////            var objectCallResult = eval(object, next, constantPoolGen, bootstrapMethods);
////            var obj = objectCallResult.getResult();
//            var obj = evalArgumentsResult.result[0];
//            InstructionHandle lastInstruction = evalArgumentsResult1.getInstructionHandle();
//            return callMethod(obj, obj.getClass(), methodName1, argumentTypes1, evalArgumentsResult1.getResult(),
//                    instructionHandle, lastInstruction, constantPoolGen);

            return success(methodInfo, instructionHandle, instructionHandle);
        } else if (instruction instanceof INVOKESTATIC) {
            return callMethod(null, Class.forName(instruction.getClassName(constantPoolGen)), methodName, argumentTypes, arguments,
                    instructionHandle, instructionHandle, constantPoolGen);
        }
        throw new UnsupportedOperationException("invoke: " + instruction.toString(constantPoolGen.getConstantPool()));
    }

    private static Object getBootstrapMethod(Object object, InvokeInstruction instruction, BootstrapMethods bootstrapMethods, ConstantPool cp) {
        var constantInvokeDynamic = cp.getConstant(instruction.getIndex(), CONSTANT_InvokeDynamic, ConstantInvokeDynamic.class);

        int nameAndTypeIndex = constantInvokeDynamic.getNameAndTypeIndex();
        var constantNameAndType1 = cp.getConstant(nameAndTypeIndex, ConstantNameAndType.class);
        String name1 = constantNameAndType1.getName(cp);
        String signature1 = constantNameAndType1.getSignature(cp);

        MethodType methodType1 = MethodType.fromMethodDescriptorString(signature1, null);

        int bootstrapMethodAttrIndex = constantInvokeDynamic.getBootstrapMethodAttrIndex();
        var bootstrapMethod = bootstrapMethods.getBootstrapMethods()[bootstrapMethodAttrIndex];
        int bootstrapMethodRef = bootstrapMethod.getBootstrapMethodRef();
        var constant1 = cp.getConstant(bootstrapMethodRef, CONSTANT_MethodHandle, ConstantMethodHandle.class);
        int referenceKind1 = constant1.getReferenceKind();
        var constant2 = cp.getConstant(constant1.getReferenceIndex(), CONSTANT_Methodref, ConstantMethodref.class);

        var className = constant2.getClass(cp);

        var nameAndType = cp.getConstant(constant2.getNameAndTypeIndex(), CONSTANT_NameAndType, ConstantNameAndType.class);
        var name = nameAndType.getName(cp);
        var signature = nameAndType.getSignature(cp);
        var argumentTypes = getArgumentTypes(Type.getArgumentTypes(signature));

        MethodType methodType = MethodType.fromMethodDescriptorString(signature, null);

        MethodHandles.Lookup lookup = MethodHandles.lookup();

        //todo check referenceKind1
        MethodHandle lookupStatic;
        try {
            lookupStatic = lookup.findStatic(getClassByName(className), name, methodType);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new BcelException(e);
        }

        List<Object> arguments = IntStream.of(bootstrapMethod.getBootstrapArguments()).mapToObj(cp::getConstant).map(c -> {
                    if (c instanceof ConstantMethodType) {
                        var constantMethodType = (ConstantMethodType) c;
                        int descriptorIndex = constantMethodType.getDescriptorIndex();
                        var constantUtf8 = cp.getConstantUtf8(descriptorIndex).getBytes();
                        return MethodType.fromMethodDescriptorString(constantUtf8, null);
                    } else if (c instanceof ConstantMethodHandle) {
                        var constant = (ConstantMethodHandle) c;

                        int referenceIndex = constant.getReferenceIndex();
                        int referenceKind = constant.getReferenceKind();
                        var invokeSpecial = referenceKind == REF_invokeSpecial;
                        //      static final byte
                        //            REF_NONE                    = 0,  // null value
                        //            REF_getField                = 1,
                        //            REF_getStatic               = 2,
                        //            REF_putField                = 3,
                        //            REF_putStatic               = 4,
                        //            REF_invokeVirtual           = 5,
                        //            REF_invokeStatic            = 6,
                        //            REF_invokeSpecial           = 7,
                        //            REF_newInvokeSpecial        = 8,
                        //            REF_invokeInterface         = 9,
                        //            REF_LIMIT                  = 10;
//

                        var constantMethodref = cp.getConstant(referenceIndex, ConstantMethodref.class);

                        var type = constantMethodref.getClass(cp);
                        var constantNameAndType = cp.getConstant(constantMethodref.getNameAndTypeIndex(), ConstantNameAndType.class);
                        var methodName = constantNameAndType.getName(cp);
                        var methodSignature = constantNameAndType.getSignature(cp);

                        Class<?> targetClass = getClassByName(type);
                        final MethodHandles.Lookup privateLookup = getPrivateLookup(targetClass, lookup);
                        if (invokeSpecial) {
                            MethodHandle special;
                            try {
                                special = privateLookup.findSpecial(targetClass, methodName,
                                        MethodType.fromMethodDescriptorString(methodSignature, null), targetClass);
                            } catch (NoSuchMethodException | IllegalAccessException e) {
                                throw new BcelException(e);
                            }
                            return special;
                        } else {
                            throw new UnsupportedOperationException("getBootstrapMethod, method handle referenceKind " + referenceKind);
                        }
                    } else {
                        throw new UnsupportedOperationException("getBootstrapMethod, eval arg type of " + c);
                    }
                }
        ).collect(toList());

        MethodHandles.Lookup privateLookup = getPrivateLookup(methodType1.parameterArray()[0], lookup);

        CallSite metafactory;
        try {
            metafactory = LambdaMetafactory.metafactory(privateLookup, name1, methodType1, (MethodType) arguments.get(0), (MethodHandle) arguments.get(1), (MethodType) arguments.get(2));
        } catch (LambdaConversionException e) {
            throw new RuntimeException(e);
        }

        MethodHandle methodHandle = metafactory.dynamicInvoker();
        Object invoke;
        try {
            invoke = methodHandle.invoke(object);
        } catch (Throwable e) {
            throw new BcelException(e);
        }

        return invoke;

    }

    private static MethodHandles.Lookup getPrivateLookup(Class<?> targetClass, MethodHandles.Lookup lookup) {
        final MethodHandles.Lookup privateLookup;
        try {
            privateLookup = MethodHandles.privateLookupIn(targetClass, lookup);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return privateLookup;
    }

    private static Class[] getArgumentTypes(Type[] argumentTypes) {
        var args = new Class[argumentTypes.length];
        for (int i = 0; i < argumentTypes.length; i++) {
            var argumentType = argumentTypes[i];
            String className = argumentType.getClassName();
            args[i] = getClassByName(className);
        }
        return args;
    }

    private static Class<?> getClassByName(String className) {
        Class<?> forName;
        try {
            forName = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new BcelException(e);
        }
        return forName;
    }

    private static EvalArgumentsResult evalArguments(
            Object object, InstructionHandle instructionHandle,
            int argumentsCount, ConstantPoolGen constantPoolGen,
            BootstrapMethods bootstrapMethods
    ) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        var args = new Object[argumentsCount];
        var current = instructionHandle;
        for (int i = argumentsCount; i > 0; i--) {
            current = current.getPrev();
            var eval = eval(object, current, constantPoolGen, bootstrapMethods);
            current = eval.getCallInstruction();
            args[i - 1] = eval.getResult();
        }
        return new EvalArgumentsResult(args, current);
    }

    public static boolean isUriCreate(Instruction instruction, ConstantPoolGen constantPoolGen) {
        return instruction instanceof INVOKESTATIC && isUriCreate((INVOKESTATIC) instruction, constantPoolGen);
    }

    public static boolean isUriCreate(INVOKESTATIC instruction, ConstantPoolGen constantPoolGen) {
        return isUriCreate(instruction.getClassName(constantPoolGen), instruction.getMethodName(constantPoolGen));
    }

    public static boolean isUriCreate(String className, String methodName) {
        return URI.class.getName().equals(className) && methodName.equals("create");
    }

    public static CallResult<Object> getFieldValue(Object object, String name,
                                                   InstructionHandle getFieldInstruction, InstructionHandle lastInstruction) throws IllegalAccessException {
        var field = getDeclaredField(name, object.getClass());
        return field == null ? notFound(name, getFieldInstruction) : field.trySetAccessible() ? success(field.get(object),
                getFieldInstruction, lastInstruction) : notAccessible(field, getFieldInstruction);
    }

    public static CallResult<Object> callMethod(Object object, Class<?> type, String methodName,
                                                Class[] argTypes, Object[] args,
                                                InstructionHandle invokeInstruction,
                                                InstructionHandle lastInstruction,
                                                ConstantPoolGen constantPoolGen
    ) throws IllegalAccessException, InvocationTargetException {
        var msg = "callMethod";
        var declaredMethod = getDeclaredMethod(methodName, type, argTypes);
        if (declaredMethod == null) {
            log.info("{}, method not found '{}.{}', instruction {}",
                    msg, type.getName(), methodName, toString(invokeInstruction, constantPoolGen));
            return notFound(methodName, invokeInstruction);
        } else if (declaredMethod.trySetAccessible()) {
            var result = declaredMethod.invoke(object, args);
            if (log.isDebugEnabled()) {
                log.debug("{}, success, method '{}.{}', result: {}, instruction {}",
                        msg, type.getName(), methodName, result, toString(invokeInstruction, constantPoolGen));
            }
            return success(result, invokeInstruction, lastInstruction);
        } else
            log.warn("{}, method is not accessible, method '{}.{}', instruction {}",
                    msg, type.getName(), methodName, toString(invokeInstruction, constantPoolGen));
        return notAccessible(declaredMethod, invokeInstruction);
    }

    private static String toString(InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen) {
        return instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
    }

    @Data
    private static class EvalArgumentsResult {
        private final Object[] result;
        private final InstructionHandle instructionHandle;
    }

    @Data
    @Builder
    public static class CallResult<T> {
        private final T result;
        private final Set<Status> status;
        private final Object source;
        private final InstructionHandle callInstruction;
        private final InstructionHandle lastInstruction;

        public static <T> CallResult<T> success(T value, InstructionHandle callInstruction, InstructionHandle lastInstruction) {
            return CallResult.<T>builder().result(value).callInstruction(callInstruction).lastInstruction(lastInstruction).build();
        }

        public static <T> CallResult<T> notAccessible(Object source, InstructionHandle callInstruction) {
            return CallResult.<T>builder().status(Set.of(notAccessible)).source(source)
                    .callInstruction(callInstruction).build();
        }

        public static <T> CallResult<T> notFound(Object source, InstructionHandle callInstruction) {
            return CallResult.<T>builder().status(Set.of(notFound)).source(source)
                    .callInstruction(callInstruction).build();
        }

        public T getResult() {
            if (status != null && !status.isEmpty()) {
                throw new CallResultException(status, source, callInstruction);
            }
            return result;
        }

        public enum Status {
            notAccessible, notFound;
        }

        @Getter
        public static class CallResultException extends RuntimeException {
            public CallResultException(Collection<Status> status, Object source, InstructionHandle instruction) {
                super(status + ", source=" + source + ", instruction=" + instruction);
            }
        }
    }

}
