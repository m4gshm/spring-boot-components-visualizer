package io.github.m4gshm.connections.bytecode;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.LocalVariableTable;
import org.apache.bcel.generic.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import static io.github.m4gshm.connections.bytecode.EvalException.newInvalidEvalException;
import static io.github.m4gshm.connections.bytecode.EvalException.newUnsupportedEvalException;
import static io.github.m4gshm.connections.bytecode.EvalResult.success;
import static io.github.m4gshm.connections.bytecode.EvalUtils.*;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Stream.of;

@Slf4j
@Data
public class Eval {

    private final ConstantPoolGen constantPoolGen;
    private final LocalVariableTable localVariableTable;
    private final BootstrapMethods bootstrapMethods;
    //only for debug
    private final Code code;

    static ArrayList<Object> getInvokeArgs(Object object, Object[] arguments) {
        var invokeArgs = new ArrayList<>(arguments.length);
        invokeArgs.add(object);
        invokeArgs.addAll(asList(arguments));
        return invokeArgs;
    }

    public EvalResult<Object> eval(Object object, InstructionHandle instructionHandle) {
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof LDC) {
            var ldc = (LDC) instruction;
            var value = ldc.getValue(constantPoolGen);
            return success(value, instructionHandle, instructionHandle);
        } else if (instruction instanceof ALOAD) {
            var aload = (ALOAD) instruction;
            var aloadIndex = aload.getIndex();
            var localVariableTable = of(this.localVariableTable.getLocalVariableTable())
                    .collect(groupingBy(LocalVariable::getIndex));
            var localVariables = localVariableTable.getOrDefault(aloadIndex, List.of());
            var position = instructionHandle.getPosition();

            var localVariable = localVariables.stream().filter(variable -> {
                int startPC = variable.getStartPC();
                var endPC = startPC + variable.getLength();
                return startPC <= position && position <= endPC;
            }).findFirst().orElseGet(() -> {
                if (localVariables.isEmpty()) {
                    log.warn("no matched local variables for instruction {} ", instructionHandle);
                    return null;
                }
                return localVariables.get(0);
            });

            var name = localVariable != null ? localVariable.getName() : null;
            if ("this".equals(name)) {
                return success(object, instructionHandle, instructionHandle);
            }

            var prev = instructionHandle.getPrev();
            var aStoreResults = new ArrayList<Object>(localVariables.size());
            for (var variable : localVariables) {
                inner: while (prev != null) {
                    if (prev.getInstruction() instanceof ASTORE) {
                        var astore = (ASTORE) prev.getInstruction();
                        if (astore.getIndex() == aloadIndex) {
                            var storedInLocal = eval(object, prev);
                            var result = storedInLocal.getResult();
                            aStoreResults.add(result);
                            prev = prev.getPrev();
                            break inner;
                        }
                    }
                    prev = prev.getPrev();
                }
            }
            if (!aStoreResults.isEmpty()) {
                return success(aStoreResults, instructionHandle, instructionHandle);
            }
            if (log.isDebugEnabled()) {
                var description = instructionHandle.getInstruction().toString(constantPoolGen.getConstantPool());
                log.debug("not found astore for {}", description);
            }

            if (localVariable == null) {
                throw newInvalidEvalException("null local variable at index " + aloadIndex, instruction, constantPoolGen);
            }

            var signature = localVariable.getSignature();
            var type = Type.getType(signature);
            var classByName = getClassByName(type.getClassName());
            if (classByName != null && CharSequence.class.isAssignableFrom(classByName)) {
                return success("parameter:" + localVariable.getName(), instructionHandle, instructionHandle);
            }
            return success(getDefaultValue(classByName), instructionHandle, instructionHandle);
        } else if (instruction instanceof ASTORE) {
            return eval(object, instructionHandle.getPrev());
        } else if (instruction instanceof GETFIELD) {
            var getField = (GETFIELD) instruction;
            var evalFieldOwnedObject = eval(object, instructionHandle.getPrev());
            var fieldName = getField.getFieldName(constantPoolGen);
            var filedOwnedObject = evalFieldOwnedObject.getResult();
            return getFieldValue(filedOwnedObject, fieldName, instructionHandle, evalFieldOwnedObject.getLastInstruction());
        } else if (instruction instanceof CHECKCAST) {
            return eval(object, instructionHandle.getPrev());
        } else if (instruction instanceof InvokeInstruction) {
            return getMethodResult(object, instructionHandle, (InvokeInstruction) instruction);
        } else if (instruction instanceof ANEWARRAY) {
            var anewarray = (ANEWARRAY) instruction;
            var loadClassType = anewarray.getLoadClassType(constantPoolGen);
            var size = eval(object, instructionHandle.getPrev());
            var result = size.getResult();
            var arrayElementType = getClassByName(loadClassType.getClassName());
            return success(Array.newInstance(arrayElementType, (int) result), instructionHandle, size.getLastInstruction());
        } else if (instruction instanceof ConstantPushInstruction) {
            var cpi = (ConstantPushInstruction) instruction;
            var value = cpi.getValue();
            return success(value, instructionHandle, instructionHandle);
        } else if (instruction instanceof AASTORE) {
            var element = eval(object, instructionHandle.getPrev());
            var index = eval(object, element.getLastInstruction().getPrev());

            var array = eval(object, index.getLastInstruction().getPrev());

            var result = array.getResult();
            if (result instanceof Object[]) {
                ((Object[]) result)[(int) index.getResult()] = element.getResult();
            } else {
                throw newInvalidEvalException("expected array but was " + result.getClass(), instruction, constantPoolGen);
            }

            return success(result, instructionHandle, array.getLastInstruction());
        } else if (instruction instanceof DUP) {
            var eval = eval(object, instructionHandle.getPrev());
            return success(eval.getResult(), instructionHandle, eval.getLastInstruction());
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    private Object getDefaultValue(Class<?> type) {
        log.trace("getDefaultValue type {}", type);
        if (type == null) {
            return null;
        } else if (void.class.isAssignableFrom(type)) {
            return null;
        } else if (boolean.class.isAssignableFrom(type)) {
            return false;
        } else if (byte.class.isAssignableFrom(type)) {
            return (byte) 0;
        } else if (short.class.isAssignableFrom(type)) {
            return (short) 0;
        } else if (int.class.isAssignableFrom(type)) {
            return 0;
        } else if (long.class.isAssignableFrom(type)) {
            return 0L;
        } else if (float.class.isAssignableFrom(type)) {
            return 0F;
        } else if (double.class.isAssignableFrom(type)) {
            return 0D;
        } else if (char.class.isAssignableFrom(type)) {
            return (char) 0;
        } else if (String.class.isAssignableFrom(type)) {
            return "";
        } else {
            return null;
        }
    }

    private EvalResult<Object> getMethodResult(
            Object object, InstructionHandle instructionHandle, InvokeInstruction instruction
    ) {
        if (log.isTraceEnabled()) {
            var instructionText = instruction.toString(constantPoolGen.getConstantPool());
            log.trace("eval {}", instructionText);
        }
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = getArgumentTypes(instruction.getArgumentTypes(constantPoolGen));
        var evalArgumentsResult = evalArguments(object, instructionHandle, argumentTypes.length);
        var arguments = evalArgumentsResult.getResult();
        var lastArgInstruction = evalArgumentsResult.getInstructionHandle();
        if (instruction instanceof INVOKEVIRTUAL) {
            var next = lastArgInstruction.getPrev();
            var objectCallResult = eval(object, next);
            var obj = objectCallResult.getResult();
            var lastInstruction = objectCallResult.getLastInstruction();
            return callMethod(obj, obj.getClass(), methodName, argumentTypes, arguments, instructionHandle, lastInstruction, constantPoolGen);
        } else if (instruction instanceof INVOKEINTERFACE) {
            var next = lastArgInstruction.getPrev();
            var objectCallResult = eval(object, next);
            var obj = objectCallResult.getResult();
            var type = getClassByName(instruction.getClassName(constantPoolGen));
            var lastInstruction = objectCallResult.getLastInstruction();
            return callMethod(obj, type, methodName, argumentTypes, arguments, instructionHandle, lastInstruction, constantPoolGen);
        } else if (instruction instanceof INVOKEDYNAMIC) {
            var result = callBootstrapMethod(object, arguments, (INVOKEDYNAMIC) instruction, constantPoolGen, bootstrapMethods);
            return success(result, instructionHandle, lastArgInstruction);
        } else if (instruction instanceof INVOKESTATIC) {
            var type = getClassByName(instruction.getClassName(constantPoolGen));
            return callMethod(null, type, methodName, argumentTypes, arguments, instructionHandle, instructionHandle, constantPoolGen);
        } else if (instruction instanceof INVOKESPECIAL) {
            var invokeSpec = (INVOKESPECIAL) instruction;
            var lookup = MethodHandles.lookup();
            var type = getClassByName(instruction.getClassName(constantPoolGen));
            var signature = invokeSpec.getSignature(constantPoolGen);
            var methodType = fromMethodDescriptorString(signature, type.getClassLoader());
            if ("<init>".equals(methodName)) {
//                var constructor = getMethodHandle(() -> lookup.findConstructor(type, methodType));
//                Object result = invoke(constructor, asList(arguments));
//                return success(result, instructionHandle, instructionHandle);
                return instantiateObject(instructionHandle, type, argumentTypes, arguments);
            } else {
                var privateLookup = getPrivateLookup(type, lookup);
                var methodHandle = getMethodHandle(() -> privateLookup.findSpecial(type, methodName, methodType, type));
                var result = invoke(methodHandle, getInvokeArgs(object, arguments));
                return success(result, instructionHandle, instructionHandle);
            }
        }
        throw newUnsupportedEvalException(instruction, constantPoolGen);
    }

    EvalArgumentsResult evalArguments(
            Object object, InstructionHandle instructionHandle, int argumentsCount
    ) {
        var args = new Object[argumentsCount];
        var current = instructionHandle;
        for (int i = argumentsCount; i > 0; i--) {
            current = current.getPrev();
            var eval = eval(object, current);
            current = eval.getCallInstruction();
            args[i - 1] = eval.getResult();
        }
        return new EvalArgumentsResult(args, current);
    }

    @Data
    static class EvalArgumentsResult {
        private final Object[] result;
        private final InstructionHandle instructionHandle;
    }
}
