package io.github.m4gshm.connections;

import io.github.m4gshm.connections.ReflectionUtils.CallResult;
import lombok.experimental.UtilityClass;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ALOAD;
import org.apache.bcel.generic.ASTORE;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.GETFIELD;
import org.apache.bcel.generic.INVOKESTATIC;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.InvokeInstruction;
import org.apache.bcel.generic.LDC;
import org.apache.bcel.generic.ReturnInstruction;
import org.apache.bcel.generic.Type;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.ConnectionsExtractorUtils.getFieldValue;
import static io.github.m4gshm.connections.ReflectionUtils.CallResult.success;
import static io.github.m4gshm.connections.ReflectionUtils.callStaticMethod;

@UtilityClass
public class BcelUtils {
    static CallResult getDoHandshakeUri(
            Object object, InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen, Method method
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
                    value = eval(object, prev.getPrev(), constantPoolGen);
                } else {
                    value = eval(object, prev, constantPoolGen);
                }
            } else {
                value = eval(object, prev, constantPoolGen);
                var result = value.getResult();
                if (result instanceof URI) {
                    var uri = (URI) result;
                    value = CallResult.success(uri.toString(), /*todo ???*/null);
                } else {
                    //log
                    value = CallResult.success(result.toString(), /*todo ???*/null);
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

    public static CallResult eval(
            Object object, InstructionHandle instructionHandle, ConstantPoolGen constantPoolGen
    ) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            return success(ldc.getValue(constantPoolGen), instructionHandle);
        } else if (instruction instanceof ALOAD) {
            var aload = (ALOAD) instruction;
            var aloadIndex = aload.getIndex();
            if (aloadIndex == 0) {
                //this ???
                return CallResult.success(object, instructionHandle);
            } else {
                var prev = instructionHandle.getPrev();
                while (prev != null) {
                    if (prev.getInstruction() instanceof ASTORE) {
                        var astore = (ASTORE) prev.getInstruction();
                        if (astore.getIndex() == aloadIndex) {
                            var storedInLocal = eval(object, prev, constantPoolGen);
                            return storedInLocal;
                        }
                    }
                    prev = prev.getPrev();
                }
            }
        } else if (instruction instanceof ASTORE) {
            return eval(object, instructionHandle.getPrev(), constantPoolGen);
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;

            var eval = eval(object, instructionHandle.getPrev(), constantPoolGen);
            var fieldName = getField.getFieldName(constantPoolGen);
            var result = eval.getResult();
            var fieldValue = ReflectionUtils.getFieldValue(result, fieldName, instructionHandle);
            return fieldValue;
        } else if (instruction instanceof INVOKESTATIC) {
            return getStaticMethodResult(object, instructionHandle, (INVOKESTATIC) instruction, constantPoolGen);
        }
        throw new UnsupportedOperationException("eval: " + instruction.toString(constantPoolGen.getConstantPool()));
    }

    public static CallResult getStaticMethodResult(
            Object object, InstructionHandle instructionHandle, INVOKESTATIC instruction, ConstantPoolGen constantPoolGen
    ) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        var className = instruction.getClassName(constantPoolGen);
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = getArgumentTypes(instruction.getArgumentTypes(constantPoolGen));
        var arguments = getArguments(object, instructionHandle, instruction, constantPoolGen);
        var callResult = callStaticMethod(Class.forName(className), methodName, argumentTypes, arguments, instructionHandle);
        return callResult;
    }

    private static Class[] getArgumentTypes(Type[] argumentTypes) throws ClassNotFoundException {
        var args = new Class[argumentTypes.length];
        for (int i = 0; i < argumentTypes.length; i++) {
            var argumentType = argumentTypes[i];
            args[i] = Class.forName(argumentType.getClassName());
        }
        return args;
    }


    private static Object[] getArguments(
            Object object, InstructionHandle instructionHandle,
            INVOKESTATIC instruction, ConstantPoolGen constantPoolGen
    ) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        var args = new Object[argumentTypes.length];
        var last = instructionHandle.getPrev();
        for (int i = argumentTypes.length; i > 0; i--) {
            var eval = eval(object, last, constantPoolGen);
            last = eval.getLastInstruction();
            args[i - 1] = eval.getResult();
        }
        return args;
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

    public static List<?> getURICreateArgument(
            Object object, INVOKESTATIC instruction, Instruction[] instructions,
            int instructionIndex, ConstantPoolGen constantPoolGen
    ) {
        var c = instruction.consumeStack(constantPoolGen);
        int p = instruction.produceStack(constantPoolGen);
        var argumentTypes1 = instruction.getArgumentTypes(constantPoolGen);
        int current = instructionIndex;
        var values = new ArrayList<Object>(argumentTypes1.length);
        //todo check argument type is String
        for (var argI = argumentTypes1.length; argI > 0; argI--) {
            current -= argI;
            var subInstruction = instructions[current];
            if (subInstruction instanceof LDC) {
                var value = ((LDC) subInstruction).getValue(constantPoolGen);
                values.add(value);
            } else if (subInstruction instanceof GETFIELD) {
                var getFieldInstruction = (GETFIELD) subInstruction;
                var fieldName = getFieldInstruction.getFieldName(constantPoolGen);
                var fieldValue = getFieldValue(object, fieldName);
                values.add(fieldValue);
            } else {
                throw new UnsupportedOperationException(subInstruction.toString());
                //log
            }
        }

        return values;
    }

}
