package io.github.m4gshm.components.visualizer;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.apache.bcel.classfile.JavaClass;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;

import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.getClassByName;
import static java.util.Objects.requireNonNull;

@UtilityClass
public class LambdaUtils {
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
        var tmpdir = System.getProperty("java.io.tmpdir") + "/" + "dump";
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


    public class DumperClassLoader extends ClassLoader {
        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {

            return super.loadClass(name);
        }
    }
}
