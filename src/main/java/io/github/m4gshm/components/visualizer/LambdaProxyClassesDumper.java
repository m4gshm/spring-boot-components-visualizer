package io.github.m4gshm.components.visualizer;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.UUID;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.getClassByName;
import static java.util.Objects.requireNonNull;
import static org.apache.bcel.classfile.Utility.packageToPath;

@Slf4j
@UtilityClass
public class LambdaProxyClassesDumper {

    public static final String dumpsDir = initialize();

    public static boolean isDumperInitialized() {
        return dumpsDir != null;
    }

    private static String initialize() {
        try {
            var dumpsDir = getDumpsDir();
            injectProxyDumperIntoInnerClassLambdaMetafactory(newProxyClassesDumper(dumpsDir));
            log.info("Lambda proxy dumper is initialized");
            new File(dumpsDir).deleteOnExit();
            return dumpsDir;
        } catch (IOException e) {
            log.error("Lambda proxy dumper initialization error", e);
            return null;
        }
    }

    public JavaClass loadClass(final String className) throws ClassNotFoundException {
        if (!isDumperInitialized()) {
            throw new IllegalStateException("Lambda proxy dumper uninitialized");
        }
        var classFile = packageToPath(className);
        var file = new File(dumpsDir, classFile + ".class");
        if (!file.exists()) {
            throw new ClassNotFoundException(className + " not found.");
        }
        try (var is = new FileInputStream(file)) {
            var parser = new ClassParser(is, className);
            return parser.parse();
        } catch (IOException e) {
            throw new ClassNotFoundException(className + " not found: " + e, e);
        }
    }

    /**
     * @param outputDir is directory where lambda bytecode dumps are stored by java.lang.invoke.InnerClassLambdaMetafactory class
     * @return java.lang.invoke.ProxyClassesDumper
     */
    @SneakyThrows
    public static Object newProxyClassesDumper(String outputDir) {
        var proxyClassesDumperClass = getClassByName("java.lang.invoke.ProxyClassesDumper");
        var getInstanceM = proxyClassesDumperClass.getMethod("getInstance", String.class);
        getInstanceM.setAccessible(true);

        return requireNonNull(getInstanceM.invoke(proxyClassesDumperClass, outputDir));
    }

    public static String getDumpsDir() throws IOException {
        var dumpSubdir = "dump";
        var tmpdir = System.getProperty("java.io.tmpdir") + "/" + dumpSubdir + "/" + UUID.randomUUID();
        var file = new File(tmpdir);
        var mkdirs = file.mkdirs();
        if (!mkdirs) {
            throw new IOException("Failed to create tmp directory: " + tmpdir);
        }
        file.deleteOnExit();
        return tmpdir;
    }

    @SneakyThrows
    public static void injectProxyDumperIntoInnerClassLambdaMetafactory(Object proxyClassesDumper) {
        var innerClassLambdaMetafactoryC = getClassByName("java.lang.invoke.InnerClassLambdaMetafactory");
        var dumperF = innerClassLambdaMetafactoryC.getDeclaredField("dumper");
        dumperF.setAccessible(true);

        var unsafeC = getClassByName("sun.misc.Unsafe");
        var unsafeF = unsafeC.getDeclaredField("theUnsafe");
        unsafeF.setAccessible(true);

        var unsafe = unsafeF.get(unsafeC);

        var staticFieldBaseM = unsafe.getClass().getMethod("staticFieldBase", Field.class);
        var staticFieldOffsetM = unsafe.getClass().getMethod("staticFieldOffset", Field.class);
        var putObjectM = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);

        var dumperStaticFieldBase = staticFieldBaseM.invoke(unsafe, dumperF);
        var dumperStaticFieldOffset = staticFieldOffsetM.invoke(unsafe, dumperF);

        putObjectM.invoke(unsafe, dumperStaticFieldBase, dumperStaticFieldOffset, proxyClassesDumper);
    }

}
