package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval.CallCache;
import io.github.m4gshm.components.visualizer.eval.bytecode.Eval.EvalArguments;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.eval.result.Variable;
import io.github.m4gshm.components.visualizer.model.CallPoint;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.Type;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.CallPointsHelper.CallPointsProvider;
import static io.github.m4gshm.components.visualizer.Utils.warnDuplicated;
import static io.github.m4gshm.components.visualizer.eval.bytecode.Eval.resolveArgumentVariants;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.findClassByName;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.stringForLog;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeBranch.newTree;
import static java.util.Map.entry;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Stream.concat;
import static lombok.AccessLevel.PROTECTED;
import static org.springframework.util.Assert.state;

@Slf4j
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = PROTECTED)
public class EvalContextFactoryImpl implements EvalContextFactory {

    CallCache callCache;
    DependentProvider dependentProvider;
    CallPointsProvider callPointsProvider;
    Resolver resolver;

    public static List<List<Result>> computeArgumentVariants(Component component, Method method,
                                                             EvalContextFactory evalContextFactory,
                                                             DependentProvider dependentProvider,
                                                             CallPointsProvider callPointsProvider) {
        var methodCallPoints = getCallPoints(component, method.getName(), method.getArgumentTypes(),
                dependentProvider, callPointsProvider);
        var methodArgumentVariants = getEvalCallPointVariants(component, method, methodCallPoints, evalContextFactory);
        var variants = methodArgumentVariants.values().stream().parallel()
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .flatMap(e -> e.getValue().stream()).map(EvalArguments::getArguments)
                .distinct()
                .collect(toList());
        return variants;
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
        return getCallPoints(declaringClass, methodName, argumentTypes, dependentOnThisComponent, callPointsProvider);
    }

    public static Map<Component, Map<CallPoint, List<CallPoint>>> getCallPoints(
            Class<?> declaringClass, String methodName, Type[] argumentTypes,
            List<Component> dependentOnThisComponent, CallPointsProvider callPointsProvider
    ) {
        return dependentOnThisComponent.stream().parallel().map(dependentComponent -> {
            var dependentComponentType = dependentComponent.getType();
            var callPoints = callPointsProvider.apply(dependentComponentType);
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
        return dependentMethod.getOwnerClass() != null
                ? dependentMethod.getOwnerClass().getName()
                : dependentMethod.getOwnerClassName();
    }

    static Map<Component, Map<CallPoint, List<EvalArguments>>> getEvalCallPointVariants(
            Component component, Method method, Map<Component, Map<CallPoint, List<CallPoint>>> callPoints,
            EvalContextFactory evalContextFactory
    ) {
        return callPoints.entrySet().stream().map(e -> {
            var dependentComponent = e.getKey();
            var callPointListMap = e.getValue();
            var variants = callPointListMap.entrySet().stream().map(ee -> {
                return getCallPointListEntry(component, method, evalContextFactory, ee.getKey(), dependentComponent, ee.getValue());
            }).filter(Objects::nonNull).collect(toMap(Map.Entry::getKey, Map.Entry::getValue,
                    warnDuplicated(), LinkedHashMap::new));
            return entry(dependentComponent, variants);
        }).collect(toMap(Map.Entry::getKey, Map.Entry::getValue, warnDuplicated(), LinkedHashMap::new));
    }

    private static Map.Entry<CallPoint, List<EvalArguments>> getCallPointListEntry(
            Component component, Method method, EvalContextFactory evalContextFactory, CallPoint callPoint, Component dependentComponent,
            List<CallPoint> matchedCallPoints
    ) {
        var eval = evalContextFactory.getEvalContext(dependentComponent, callPoint.getJavaClass(), callPoint.getMethod());
        var argumentVariants = evalCallPointArgumentVariants(eval, callPoint, matchedCallPoints);
        return argumentVariants;
    }

    static Map.Entry<CallPoint, List<EvalArguments>> evalCallPointArgumentVariants(
            Eval eval, CallPoint dependentMethod, List<CallPoint> matchedCallPoints
    ) {
        var argVariants = matchedCallPoints.stream().map(callPoint -> {
            try {
                return Eval.evalArguments(eval, callPoint);
            } catch (EvalException e) {
                var result = (e instanceof UnresolvedResultException) ? ((UnresolvedResultException) e).getResult() : null;
                if (result instanceof Variable) {
                    var variable = (Variable) result;
                    state(eval.equals(variable.getEval()));
                    var variableMethod = eval.getMethod();
                    log.info("{} is aborted, cannot evaluate variable {}, in method {} {} of {}", "evalCallPointArgumentVariants",
                            variable.getName(), variableMethod.getName(),
                            variableMethod.getSignature(), eval.getComponent().getType()
                    );
                } else if (result != null) {
                    log.info("{} is aborted, cannot evaluate result '{}'", "evalCallPointArgumentVariants", result);
                } else {
                    log.info("{} is aborted by error", "evalCallPointArgumentVariants", e);
                }
                return List.<EvalArguments>of();
            }
        }).flatMap(Collection::stream).filter(Objects::nonNull).collect(toList());
        return !argVariants.isEmpty() ? entry(dependentMethod, argVariants) : null;
    }

    static List<CallPoint> getMatchedCallPoints(CallPoint dependentMethod, String methodName,
                                                Type[] argumentTypes, Class<?> declaringClass) {
        var callPoints = dependentMethod.getCallPoints();
        return callPoints.stream().filter(calledMethodInsideDependent -> {
            var match = isMatch(methodName, argumentTypes, declaringClass, calledMethodInsideDependent);
            var cycled = isMatch(dependentMethod.getMethodName(), dependentMethod.getArgumentTypes(),
                    dependentMethod.getOwnerClass(), calledMethodInsideDependent);
            //exclude cycling
            return match && !cycled;
        }).collect(toList());
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
        return ownerClass == null ? findClassByName(ownerClassName) : ownerClass;
    }

    @Override
    public Eval getEvalContext(Component component, JavaClass javaClass, Method method, BootstrapMethods bootstrapMethods) {
        var emptyEval = newEmptyEvalContext(component, javaClass, method, bootstrapMethods);
        return withArgumentsVariants(component, method, emptyEval);
    }

    protected Eval withArgumentsVariants(Component component, Method method, Eval emptyEval) {
        var argumentVariants = component != null ? computeArgumentVariants(component, method, this,
                dependentProvider, callPointsProvider) : List.<List<Result>>of();
        var resolveArgumentVariants = resolveArgumentVariants(component, method, argumentVariants, method.isStatic(), resolver);
        return emptyEval.withArgumentVariants(resolveArgumentVariants);
    }

    protected Eval newEmptyEvalContext(Component component, JavaClass javaClass, Method method, BootstrapMethods bootstrapMethods) {
        var tree = newTree(component.getType(), method);
        return new Eval(component, javaClass, method, bootstrapMethods, callCache, Set.of(), null, tree);
    }

    public interface DependentProvider extends Function<Component, List<Component>> {

    }
}
