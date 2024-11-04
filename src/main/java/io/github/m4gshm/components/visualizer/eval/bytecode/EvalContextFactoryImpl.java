package io.github.m4gshm.components.visualizer.eval.bytecode;

import com.google.common.collect.ImmutableList;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.eval.result.Variable;
import io.github.m4gshm.components.visualizer.model.CallPoint;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import lombok.var;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.Type;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.CallPointsHelper.CallPointsProvider;
import static io.github.m4gshm.components.visualizer.MapUtils.entry;
import static io.github.m4gshm.components.visualizer.Utils.classByName;
import static io.github.m4gshm.components.visualizer.Utils.warnDuplicated;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.stringForLog;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Stream.concat;
import static lombok.AccessLevel.PROTECTED;

@Slf4j
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = PROTECTED)
public class EvalContextFactoryImpl implements EvalContextFactory {

    Map<CallCacheKey, Result> callCache;
    DependentProvider dependentProvider;
    CallPointsProvider callPointsProvider;

    public static List<List<Result>> computeArgumentVariants(Component component, Method method,
                                                             Map<CallCacheKey, Result> callCache,
                                                             EvalContextFactory evalContextFactory,
                                                             DependentProvider dependentProvider,
                                                             CallPointsProvider callPointsProvider) {
        var methodCallPoints = getCallPoints(component, method.getName(), method.getArgumentTypes(),
                dependentProvider, callPointsProvider);
        var methodArgumentVariants = getEvalCallPointVariants(methodCallPoints, callCache, evalContextFactory);
        return methodArgumentVariants.values().stream()
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .flatMap(e -> e.getValue().stream()).map(Eval.EvalArguments::getArguments)
                .distinct().collect(toList());
    }

    public static Map<Component, Map<CallPoint, List<CallPoint>>> getCallPoints(
            Component component, String methodName, Type[] argumentTypes,
            DependentProvider dependentProvider, CallPointsProvider callPointsProvider) {
        var declaringClass = component.getType();
        var dependentOnThisComponent = concat(Stream.of(component), dependentProvider.apply(component).stream()).collect(toList());
        if (log.isTraceEnabled()) {
            var depend = dependentOnThisComponent.stream().map(Component::getName).collect(toList());
            log.trace("dependent on component {}: {}", component.getName(), depend);
        }
        var callPoints = getCallPoints(declaringClass, methodName, argumentTypes, dependentOnThisComponent, callPointsProvider);
        return callPoints;
    }

    public static Map<Component, Map<CallPoint, List<CallPoint>>> getCallPoints(
            Class<?> declaringClass, String methodName, Type[] argumentTypes, List<Component> dependentOnThisComponent,
            CallPointsProvider callPointsProvider) {

        return dependentOnThisComponent.stream().map(dependentComponent -> {
            var callPoints = callPointsProvider.apply(dependentComponent);
            var callersWithVariants = callPoints.stream().map(dependentMethod -> {
                var matchedCallPoints = getMatchedCallPoints(dependentMethod, methodName, argumentTypes, declaringClass);
                if (!matchedCallPoints.isEmpty() && log.isDebugEnabled()) {
                    var first = matchedCallPoints.get(0);
                    log.debug("match call point of {}.{}({}) inside {}.{}({}) as first call of {}.{}({})",
                            declaringClass.getName(), methodName, stringForLog(argumentTypes),
                            ownerClassName(dependentMethod), dependentMethod.getMethodName(),
                            stringForLog(dependentMethod.getArgumentTypes()),
                            ownerClassName(first), first.getMethodName(), stringForLog(first.getArgumentTypes()));
                }
                return entry(dependentMethod, matchedCallPoints);
            }).filter(e -> !e.getValue().isEmpty()).collect(toMap(Map.Entry::getKey, Map.Entry::getValue,
                    warnDuplicated(), LinkedHashMap::new));
            return !callersWithVariants.isEmpty() ? entry(dependentComponent, callersWithVariants) : null;
        }).filter(Objects::nonNull).collect(toMap(Map.Entry::getKey, Map.Entry::getValue, warnDuplicated(), LinkedHashMap::new));
    }

    private static String ownerClassName(CallPoint dependentMethod) {
        return dependentMethod.getOwnerClass() != null ? dependentMethod.getOwnerClass().getName() : dependentMethod.getOwnerClassName();
    }

    static Map<Component, Map<CallPoint, List<Eval.EvalArguments>>> getEvalCallPointVariants(
            Map<Component, Map<CallPoint, List<CallPoint>>> callPoints,
            Map<CallCacheKey, Result> callCache, EvalContextFactory evalContextFactory
    ) {
        return callPoints.entrySet().stream().map(e -> {
            var dependentComponent = e.getKey();
            var callPointListMap = e.getValue();
            var variants = callPointListMap.entrySet().stream().map(ee -> {
                var callPoint = ee.getKey();
                var matchedCallPoints = ee.getValue();
                var javaClass = callPoint.getJavaClass();
                var method = callPoint.getMethod();
                var eval = evalContextFactory.getEvalContext(dependentComponent, javaClass, method);
                var argumentVariants = evalCallPointArgumentVariants(callPoint, matchedCallPoints, eval, callCache);
                return argumentVariants;
            }).filter(Objects::nonNull).collect(toMap(Map.Entry::getKey, Map.Entry::getValue,
                    warnDuplicated(), LinkedHashMap::new));
            return entry(dependentComponent, variants);
        }).collect(toMap(Map.Entry::getKey, Map.Entry::getValue, warnDuplicated(), LinkedHashMap::new));
    }

    static Map.Entry<CallPoint, List<Eval.EvalArguments>> evalCallPointArgumentVariants(
            CallPoint dependentMethod, List<CallPoint> matchedCallPoints,
            Eval eval, Map<CallCacheKey, Result> callCache
    ) {
        var argVariants = matchedCallPoints.stream().map(callPoint -> {
            try {
                return Eval.evalArguments(callPoint, eval, callCache);
            } catch (EvalException e) {
                var result = (e instanceof UnresolvedResultException) ? ((UnresolvedResultException) e).getResult() : null;
                if (result instanceof Variable) {
                    var variable = (Variable) result;
                    var evalContext = variable.getEvalContext();
                    var variableMethod = evalContext.getMethod();
                    log.info("{} is aborted, cannot evaluate variable {}, in method {} {} of {}", "evalCallPointArgumentVariants",
                            variable.getName(), variableMethod.getName(),
                            variableMethod.getSignature(), evalContext.getComponent().getType()
                    );
                } else if (result != null) {
                    log.info("{} is aborted, cannot evaluate result '{}'", "evalCallPointArgumentVariants", result);
                } else {
                    log.info("{} is aborted by error", "evalCallPointArgumentVariants", e);
                }
                return ImmutableList.<Eval.EvalArguments>of();
            }
        }).flatMap(Collection::stream).filter(Objects::nonNull).collect(toList());
        return !argVariants.isEmpty() ? entry(dependentMethod, argVariants) : null;
    }

    static List<CallPoint> getMatchedCallPoints(CallPoint dependentMethod, String methodName,
                                                Type[] argumentTypes, Class<?> declaringClass) {
        var callPoints = dependentMethod.getCallPoints();
        try {
            return callPoints.stream().filter(calledMethodInsideDependent -> {
                try {
                    var match = isMatch(methodName, argumentTypes, declaringClass, calledMethodInsideDependent);
                    var cycled = isMatch(dependentMethod.getMethodName(), dependentMethod.getArgumentTypes(),
                            dependentMethod.getOwnerClass(), calledMethodInsideDependent);
                    //exclude cycling
                    return match && !cycled;
                } catch (Exception e) {
                    throw e;
                }
            }).collect(toList());
        } catch (Exception e) {
            throw e;
        }
    }

    static boolean isMatch(String expectedMethodName, Type[] expectedArguments, Class<?> declaringClass,
                           CallPoint calledMethodInsideDependent) {
        var calledMethod = calledMethodInsideDependent.getMethodName();
        var calledMethodArgumentTypes = calledMethodInsideDependent.getArgumentTypes();
        var calledMethodClass = getCalledMethodClass(calledMethodInsideDependent);

        var methodEquals = expectedMethodName.equals(calledMethod);
        var argumentsEqual = Arrays.equals(expectedArguments, calledMethodArgumentTypes);
        var classEquals = calledMethodClass != null && calledMethodClass.isAssignableFrom(declaringClass);
        return methodEquals && argumentsEqual && classEquals;
    }

    static Class<?> getCalledMethodClass(CallPoint calledMethodInsideDependent) {
        var ownerClass = calledMethodInsideDependent.getOwnerClass();
        var ownerClassName = calledMethodInsideDependent.getOwnerClassName();
        Class<?> calledMethodClass = null;
        try {
            calledMethodClass = ownerClass == null ? classByName(ownerClassName) : ownerClass;
        } catch (ClassNotFoundException e) {
            log.trace("getCalledMethodClass", e);
        }
        return calledMethodClass;
    }

    @Override
    public Eval getEvalContext(Component component, Method method, BootstrapMethods bootstrapMethods) {
        return new Eval(component,
                new ConstantPoolGen(method.getConstantPool()),
                bootstrapMethods, method,
                computeArgumentVariants(component, method, callCache, this, dependentProvider, callPointsProvider));
    }

    public interface DependentProvider extends Function<Component, List<Component>> {

    }
}
