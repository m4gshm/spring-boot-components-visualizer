package io.github.m4gshm.components.visualizer.client;

import io.github.m4gshm.components.visualizer.ComponentsExtractor.ScheduledMethod;
import io.github.m4gshm.components.visualizer.ComponentsExtractor.ScheduledMethod.TriggerType;
import io.github.m4gshm.components.visualizer.eval.bytecode.CallCacheKey;
import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalContextFactory;
import io.github.m4gshm.components.visualizer.eval.result.DelayInvoke;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.model.Component;
import io.github.m4gshm.components.visualizer.model.MethodId;
import lombok.experimental.UtilityClass;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.client.Utils.getBootstrapMethods;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeDynamicUtils.getInvokeDynamicUsedMethodInfo;
import static io.github.m4gshm.components.visualizer.model.MethodId.newMethodId;
import static java.util.Arrays.stream;
import static java.util.Map.entry;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.of;
import static org.apache.bcel.generic.Type.getArgumentTypes;
import static org.apache.bcel.generic.Type.getType;

@UtilityClass
public class SchedulingConfigurerUtils {
    public static List<ScheduledMethod> getScheduledByConfigurerMethods(Component component, Class<?> componentType,
                                                                        Function<TimeUnit, String> timeUnitStringifier,
                                                                        EvalContextFactory evalContextFactory,
                                                                        Map<CallCacheKey, Result> callCache,
                                                                        Resolver resolver) {
        if (SchedulingConfigurer.class.isAssignableFrom(componentType)) {
            var configureTasksMethodClassPair = getClassAndMethodSource(componentType, "configureTasks",
                    getType(ScheduledTaskRegistrar.class));
            if (configureTasksMethodClassPair != null) {
                var method = configureTasksMethodClassPair.getValue();
                var source = configureTasksMethodClassPair.getKey();
                var constantPoolGen = new ConstantPoolGen(method.getConstantPool());
                return instructionHandleStream(method.getCode()).flatMap(instructionHandle -> {
                    var instruction = instructionHandle.getInstruction();
                    if (instruction instanceof INVOKEVIRTUAL) {
                        var invokevirtual = (INVOKEVIRTUAL) instruction;
                        var methodName = invokevirtual.getMethodName(constantPoolGen);
                        var fixedRate = "addFixedRateTask".equals(methodName);
                        var fixeDelay = "addFixedDelayTask".equals(methodName);
                        var cron = "addCronTask".equals(methodName);
                        var argumentTypes = invokevirtual.getArgumentTypes(constantPoolGen);
                        var triggerType = fixedRate ? TriggerType.fixedRate : fixeDelay
                                ? TriggerType.fixedDelay : cron
                                ? TriggerType.cron : null;
                        int argAmount = argumentTypes.length;
                        if (triggerType != null) {
//                            if (argAmount == 2 && getType(Runnable.class).equals(argumentTypes[0])) {
                            return extractRunnableScheduled(triggerType, instructionHandle, component, componentType,
                                    source, method, evalContextFactory, callCache, resolver, timeUnitStringifier).stream();
//                            } else if (argAmount == 1) {
//                                return extractRunnableScheduled(triggerType, instructionHandle, component, componentType,
//                                        source, method, evalContextFactory, callCache, resolver, timeUnitStringifier).stream();
//                            }
                        }
                    }
                    return of();
                }).collect(toList());
            }
        }
        return List.of();
    }

    public static Predicate<Entry<JavaClass, Method>> byArgs(Type... argTypes) {
        return pair -> Arrays.equals(pair.getValue().getArgumentTypes(), argTypes);
    }

    public static Predicate<Entry<JavaClass, Method>> byName(String methodName) {
        return pair -> pair.getValue().getName().equals(methodName);
    }

    public static Entry<JavaClass, Method> getClassAndMethodSource(Class<?> type, String methodName, Type... argTypes) {
        return getClassAndMethodSourceStream(type, byName(methodName).and(byArgs(argTypes))).findFirst().orElse(null);
    }

    @SafeVarargs
    public static Map<JavaClass, List<Method>> getClassAndMethodsSource(Class<?> type, Predicate<Entry<JavaClass, Method>>... filters) {
        var filter = of(filters).reduce(Predicate::and).orElse(o -> true);
        return getClassAndMethodSourceStream(type, filter).collect(groupingBy(Entry::getKey, mapping(Entry::getValue, toList())));
    }

    public static Stream<Entry<JavaClass, Method>> getClassAndMethodSourceStream(
            Class<?> type, Predicate<Entry<JavaClass, Method>> filter
    ) {
        return getClassAndMethodSourceStream(type).filter(filter);
    }

    public static Stream<Entry<JavaClass, Method>> getClassAndMethodSourceStream(Class<?> type) {
        return getClassSources(type).stream().flatMap(aClass -> stream(aClass.getMethods()).map(m -> entry(aClass, m)));
    }

    private static List<ScheduledMethod> extractRunnableScheduled(
            TriggerType triggerType, InstructionHandle runnableInstruction, Component component, Class<?> componentType,
            JavaClass source, Method componentMethod, EvalContextFactory evalContextFactory,
            Map<CallCacheKey, Result> callCache, Resolver resolver,
            Function<TimeUnit, String> timeUnitStringifier) {
        var evalContext = evalContextFactory.getEvalContext(component, source, componentMethod);
        var evaluated = evalContext.eval(runnableInstruction, null, callCache);
        if (evaluated instanceof DelayInvoke) {
            var delayInvoke = (DelayInvoke) evaluated;
            var arguments = delayInvoke.getArguments();
            var argAmount = arguments.size();
            if (argAmount == 1) {
                var taskExpr = arguments.get(0);
                var instruction = taskExpr.getFirstInstruction().getInstruction();
                var constantPoolGen = evalContext.getConstantPoolGen();
                if (instruction instanceof INVOKESPECIAL) {
                    var delayInvokeTaskExpr = (DelayInvoke) taskExpr;
                    var argumentsExpr = delayInvokeTaskExpr.getArguments();
                    var invokespecial = (INVOKESPECIAL) instruction;
                    var name = invokespecial.getName(constantPoolGen);
                    if ("<init>".equals(name)) {
                        var className = invokespecial.getClassName(constantPoolGen);
                        var aClass = findClassByName(className);
                        if (aClass != null && (IntervalTask.class.isAssignableFrom(aClass) || CronTask.class.isAssignableFrom(aClass))) {
                        var argumentTypes = invokespecial.getArgumentTypes(constantPoolGen);
                            var result = getResult(resolver, argumentTypes, argumentsExpr, evalContext);
                            var runnableExpr = result.getKey();
                            var triggerExpr = result.getValue();
                            return runnableExpr != null && triggerExpr != null
                                    ? getScheduledMethods(triggerType, timeUnitStringifier, triggerExpr, component,
                                    componentType, source, (Runnable) runnableExpr.getValue(), runnableExpr,
                                    evalContext, resolver)
                                    : List.of();
                        }
                    }
//                } else if (instruction instanceof InvokeInstruction) {

//                } else {
//                    throw new UnsupportedOperationException("TODO");
                }
            } else if (argAmount == 2) {
                var runnableExpr = arguments.get(0);
                var runnableResolved = evalContext.resolve(runnableExpr, resolver);
                var runnable = runnableResolved.getValue();
                if (runnable instanceof Runnable) {
                    var triggerExpr = arguments.get(1);
                    return getScheduledMethods(triggerType, timeUnitStringifier, triggerExpr, component,
                            componentType, source, (Runnable) runnable, runnableExpr, evalContext, resolver);
                }
            }
        }
        throw new UnsupportedOperationException("TODO");
    }

    private static Entry<Result, Result> getResult(Resolver resolver, Type[] argumentTypes, List<Result> argumentsExpr, Eval evalContext) {
        Result runnableExpr = null;
        Result triggerExpr = null;
        for (int i = 0; i < argumentTypes.length; i++) {
            var argumentType = argumentTypes[i];
            var argClass = findClassByName(argumentType.getClassName());
            if (argClass != null && Runnable.class.isAssignableFrom(argClass)) {
                runnableExpr = evalContext.resolve(argumentsExpr.get(i), resolver);
            } else {
                triggerExpr = argumentsExpr.get(i);
            }
            if (runnableExpr != null && triggerExpr != null) {
                return Map.entry(runnableExpr, triggerExpr);
            }
        }
        return null;
    }


    private static List<ScheduledMethod> getScheduledMethods(TriggerType triggerType,
                                                             Function<TimeUnit, String> timeUnitStringifier,
                                                             Result triggerExpr, Component component,
                                                             Class<?> componentType, JavaClass source,
                                                             Runnable runnable, Result runnableExpr,
                                                             Eval evalContext, Resolver resolver) {
        var triggerValues = resolveValues(triggerExpr, evalContext, resolver);
        var triggerExpressions = resolveTriggerExpression(triggerType, triggerValues, triggerExpr,
                resolver, timeUnitStringifier);

        final Stream<MethodId> methodIdStream;
        if (component.getBean().equals(runnable)) {
            methodIdStream = of(newMethodId("run"));
        } else {
            var touched = new HashMap<Class<?>, Set<MethodId>>();
            var runnableClass = runnable.getClass();
            var scheduledMethodIds = isLambda(runnableClass)
                    ? getScheduledMethodIdFromRunnableLambda(componentType, runnableExpr,
                    getBootstrapMethods(source), evalContext.getConstantPoolGen(), touched)
                    : getScheduledMethodIdsFromMethod(componentType, getClassAndMethodSource(runnableClass, "run"),
                    touched);
            methodIdStream = scheduledMethodIds.stream();
        }
        return methodIdStream.flatMap(methodId -> getScheduledMethodStream(triggerType, methodId,
                triggerExpressions)).collect(toList());
    }

    private static boolean isLambda(Class<?> aClass) {
        return aClass.isSynthetic() && aClass.getSimpleName().contains("$$Lambda");
    }

    private static List<MethodId> getScheduledMethodIdFromRunnableLambda(
            Class<?> componentType, Result runnableExpr, BootstrapMethods bootstrapMethods,
            ConstantPoolGen constantPoolGen, Map<Class<?>, Set<MethodId>> touched) {
        var instructionHandle = runnableExpr.getFirstInstruction();
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof INVOKEDYNAMIC) {
            var methodIds = getMethodIds(componentType, (INVOKEDYNAMIC) instruction, constantPoolGen,
                    bootstrapMethods, touched);
            return methodIds;
        } else if (instruction instanceof InvokeInstruction) {
            var methodIds = getMethodIds(componentType, instructionHandle, constantPoolGen, touched);
            return methodIds;
        } else {
            return List.of();
        }
    }

    private static List<MethodId> getScheduledMethodIdsFromMethod(
            Class<?> beanType, Entry<JavaClass, Method> runClassMethodPair,
            Map<Class<?>, Set<MethodId>> touched) {
        var source = runClassMethodPair.getKey();
        var runMethod = runClassMethodPair.getValue();

        var bootstrapMethods = getBootstrapMethods(source);
        var constantPoolGen = new ConstantPoolGen(source.getConstantPool());

        return instructionHandleStream(runMethod.getCode()).map(instructionHandle -> {
            var instruction = instructionHandle.getInstruction();
            if (instruction instanceof INVOKEDYNAMIC) {
                var methodIds = getMethodIds(beanType, (INVOKEDYNAMIC) instruction, constantPoolGen, bootstrapMethods, touched);
                return methodIds;
            } else if (instruction instanceof InvokeInstruction) {
                var methodIds = getMethodIds(beanType, instructionHandle, constantPoolGen, touched);
                return methodIds;
            } else {
                return List.<MethodId>of();
            }
        }).flatMap(Collection::stream).collect(toList());
    }

    private static List<MethodId> getMethodIds(Class<?> componentType,
                                               InstructionHandle instructionHandle,
                                               ConstantPoolGen constantPoolGen,
                                               Map<Class<?>, Set<MethodId>> touched) {
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var methodName = instruction.getMethodName(constantPoolGen);
        var type = instruction.getLoadClassType(constantPoolGen);
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        if (getType(componentType).equals(type)) {
            return List.of(newMethodId(methodName, argumentTypes));
        } else {
            var aClass = getClassByName(type.getClassName());
            var methodIds = getMethodIds(componentType, aClass, methodName, argumentTypes, touched);
            return methodIds;
        }
    }

    private static List<MethodId> getMethodIds(Class<?> componentType, INVOKEDYNAMIC invokedynamic,
                                               ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods,
                                               Map<Class<?>, Set<MethodId>> touched) {
        var methodInfo = getInvokeDynamicUsedMethodInfo(invokedynamic, constantPoolGen, bootstrapMethods);
        var objectType = methodInfo.getObjectType();
        var methodName = methodInfo.getName();
        var argumentTypes = getArgumentTypes(methodInfo.getSignature());
        if (objectType.equals(componentType)) {
            return List.of(newMethodId(methodName, argumentTypes));
        } else {
            return getMethodIds(componentType, methodInfo.getObjectType(), methodName, argumentTypes, touched);
        }
    }

    private static List<MethodId> getMethodIds(Class<?> componentType, Class<?> aClass,
                                               String methodName, Type[] argumentTypes,
                                               Map<Class<?>, Set<MethodId>> touched) {
        if (aClass.getName().startsWith("java")) {
            return List.of();
        } else {
            var add = touched.computeIfAbsent(aClass, k -> new HashSet<>()).add(newMethodId(methodName, argumentTypes));
            if (!add) {
                //recursion detected
                return List.of();
            }
            var sourceAndMethod = getClassAndMethodSource(aClass, methodName, argumentTypes);
            return getScheduledMethodIdsFromMethod(componentType, sourceAndMethod, touched);
        }
    }

    private static List<String> resolveTriggerExpression(TriggerType triggerType, List<Object> triggerValues,
                                                         Result triggerExpr, Resolver resolver,
                                                         Function<TimeUnit, String> timeUnitStringifier) {
        switch (triggerType) {
            case fixedDelay:
            case fixedRate:
                var millisecs = triggerValues.stream().map(value -> value instanceof Number ? ((Number) value).longValue() : null)
                        .filter(Objects::nonNull).collect(toList());
                var timeUnits = getTimeUnits(triggerExpr, resolver);
                return millisecs.stream().flatMap(millisec ->
                        getTimeExpressionStream(millisec, timeUnits, timeUnitStringifier)).collect(toList());
            default:
                return triggerValues.stream().map(v -> "" + v).collect(toList());
        }
    }

    private static List<TimeUnit> getTimeUnits(Result triggerExpr, Resolver resolver) {
        var timeUnits = triggerExpr instanceof DelayInvoke
                ? ((DelayInvoke) triggerExpr).getObject().getValue(resolver).stream()
                .map(object -> object instanceof TimeUnit ? (TimeUnit) object : null).filter(Objects::nonNull)
                .collect(toList())
                : List.of(MILLISECONDS);
        return timeUnits;
    }

    private static List<Object> resolveValues(Result result, Eval evalContext, Resolver resolver) {
        var triggerResolved = evalContext.resolve(result, resolver);
        return triggerResolved.getValue(resolver);
    }

    private static Stream<String> getTimeExpressionStream(long millisec, Collection<TimeUnit> timeUnits,
                                                          Function<TimeUnit, String> timeUnitStringifier) {
        return timeUnits.stream().map(timeUnit -> getTimeExpression(millisec, timeUnit, timeUnitStringifier));
    }

    private static String getTimeExpression(long millisec, TimeUnit timeUnit, Function<TimeUnit, String> timeUnitStringifier) {
        return timeUnit.convert(millisec, MILLISECONDS) + timeUnitStringifier.apply(timeUnit);
    }

    private static Stream<ScheduledMethod> getScheduledMethodStream(TriggerType triggerType, MethodId methodId,
                                                                    List<String> triggerExpressions) {
        return triggerExpressions.stream().map(expression -> ScheduledMethod.builder()
                .method(methodId)
                .expression(expression)
                .triggerType(triggerType)
                .build());
    }
}
