/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.hub.registry;

import static com.oracle.svm.core.MissingRegistrationUtils.throwMissingRegistrationErrors;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.MapCursor;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.dynamicaccess.AccessCondition;
import org.graalvm.nativeimage.hosted.FieldValueTransformer;
import org.graalvm.nativeimage.impl.ClassLoadingSupport;

import com.oracle.svm.configure.ClassNameSupport;
import com.oracle.svm.core.configure.ConditionalRuntimeValue;
import com.oracle.svm.core.configure.RuntimeDynamicAccessMetadata;
import com.oracle.svm.core.hub.ClassForNameSupport;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.PredefinedClassesSupport;
import com.oracle.svm.core.hub.RuntimeClassLoading;
import com.oracle.svm.core.hub.RuntimeClassLoading.ClassDefinitionInfo;
import com.oracle.svm.core.jdk.Target_java_lang_ClassLoader;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.reflect.MissingReflectionRegistrationUtils;
import com.oracle.svm.espresso.classfile.JavaVersion;
import com.oracle.svm.espresso.classfile.ParsingContext;
import com.oracle.svm.espresso.classfile.descriptors.ByteSequence;
import com.oracle.svm.espresso.classfile.descriptors.ModifiedUTF8;
import com.oracle.svm.espresso.classfile.descriptors.Name;
import com.oracle.svm.espresso.classfile.descriptors.Symbol;
import com.oracle.svm.espresso.classfile.descriptors.Type;
import com.oracle.svm.espresso.classfile.descriptors.TypeSymbols;
import com.oracle.svm.espresso.classfile.perf.TimerCollection;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.PartiallyLayerAware;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.Duplicable;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.util.JVMCIReflectionUtil;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.internal.misc.PreviewFeatures;

/**
 * Class registries are used when native image respects the class loader hierarchy. There is one
 * {@linkplain AbstractClassRegistry registry} per class loader. Each registry maps class names to
 * classes. This allows multiple class loaders to load classes with the same name without conflicts.
 * <p>
 * Class registries are attached to class loaders through an
 * {@linkplain Target_java_lang_ClassLoader#classRegistry injected field}, which binds their
 * lifetime to that of the class loader.
 * <p>
 * Classes that require dynamic lookup via reflection or other mechanisms at runtime are
 * pre-registered in their respective declaring class loader's registry during build time. At
 * runtime class registries can grow in 2 ways:
 * <ul>
 * <li>When a class lookup is answered through delegation rather than directly. This means the next
 * lookup can use that cached answer directly as required by JVMS sect. 5.3.1 & 5.3.2.</li>
 * <li>When a class is defined (i.e., by runtime class loading when it's enabled)</li>
 * </ul>
 */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, layeredInstallationKind = Duplicable.class, other = PartiallyLayerAware.class)
public final class ClassRegistries implements ParsingContext {
    public final TimerCollection timers = TimerCollection.create(false);

    @Platforms(Platform.HOSTED_ONLY.class)//
    private final ConcurrentHashMap<ClassLoader, AbstractClassRegistry> buildTimeRegistries;

    private final AbstractClassRegistry bootRegistry;
    private final EconomicMap<String, String> bootPackageToModule;

    /**
     * Holds all class names known to the image build. The value linked to each name is a
     * conditional value specifying when the name can be queried at run-time, and holding a
     * Throwable object if querying the class with this name should throw a specific error at
     * run-time, excluding ClassNotFoundException, or null otherwise.
     */
    private final EconomicMap<String, ConditionalRuntimeValue<Throwable>> knownClassNames;

    @Platforms(Platform.HOSTED_ONLY.class)
    public ClassRegistries() {
        if (RuntimeClassLoading.isSupported()) {
            bootRegistry = new BootClassRegistry();
        } else {
            bootRegistry = new AOTClassRegistry(null);
        }
        buildTimeRegistries = new ConcurrentHashMap<>();
        bootPackageToModule = computeBootPackageToModuleMap();
        knownClassNames = EconomicMap.create();
    }

    private static EconomicMap<String, String> computeBootPackageToModuleMap() {
        EconomicMap<String, String> bootPackageToModule = EconomicMap.create();
        JVMCIReflectionUtil.bootLoaderPackages().forEach(p -> bootPackageToModule.put(p.getName(), p.module().getName()));
        return bootPackageToModule;
    }

    @Fold
    public static ClassRegistries singleton() {
        return ImageSingletons.lookup(ClassRegistries.class);
    }

    static String getBootModuleForPackage(String pkg) {
        return singleton().bootPackageToModule.get(pkg);
    }

    public static String[] getSystemPackageNames() {
        String[] result = new String[singleton().bootPackageToModule.size()];
        MapCursor<String, String> cursor = singleton().bootPackageToModule.getEntries();
        int i = 0;
        while (cursor.advance()) {
            result[i++] = cursor.getKey();
        }
        assert i == result.length;
        return result;
    }

    public static Class<?> findBootstrapClass(String name) {
        try {
            return singleton().resolve(name, null);
        } catch (ClassNotFoundException e) {
            throw VMError.shouldNotReachHere("The boot class loader shouldn't throw ClassNotFoundException", e);
        }
    }

    public static Class<?> findLoadedClass(String name, ClassLoader loader) {
        ByteSequence typeBytes = ByteSequence.createTypeFromName(name);
        Symbol<Type> type = SymbolsSupport.getTypes().lookupValidType(typeBytes);
        Class<?> result = null;
        if (type != null) {
            result = singleton().getRegistry(loader).findLoadedClass(type);
        }
        if (result == null) {
            result = PredefinedClassesSupport.getLoadedForNameOrNull(name, loader);
        }
        try {
            singleton().checkResult(DynamicHub.fromClass(result), name, false);
        } catch (ClassNotFoundException e) {
            throw VMError.shouldNotReachHere("checkResult should not throw ClassNotFoundException");
        }
        return result;
    }

    public static ParsingContext getParsingContext() {
        assert RuntimeClassLoading.isSupported();
        return singleton();
    }

    public static Class<?> forName(String name, ClassLoader loader) throws ClassNotFoundException {
        return singleton().resolveOrThrowException(name, loader);
    }

    private Class<?> resolveOrThrowException(String name, ClassLoader loader) throws ClassNotFoundException {
        Class<?> clazz = resolve(name, loader);
        if (clazz == null) {
            throw new ClassNotFoundException(name);
        }
        return clazz;
    }

    /**
     * This resolves the given class name. Expects dot-names.
     * <p>
     * It may or may not throw a {@link ClassNotFoundException} if the class is not found: the boot
     * class loader returns null while user-defined class loaders throw
     * {@link ClassNotFoundException}. This approach avoids unnecessary exceptions during class
     * loader delegation.
     */
    private Class<?> resolve(String name, ClassLoader loader) throws ClassNotFoundException {
        int arrayDimensions = 0;
        while (arrayDimensions < name.length() && name.charAt(arrayDimensions) == '[') {
            arrayDimensions++;
        }
        if (arrayDimensions == name.length() || arrayDimensions > 255) {
            if (loader == null) {
                return null;
            }
            throw new ClassNotFoundException(name);
        }
        Class<?> elementalResult;
        if (arrayDimensions > 0) {
            /*
             * We know that the array name was registered for reflection. The elemental type might
             * not be, so we have to ignore registration during its lookup.
             */
            ClassLoadingSupport classLoadingSupport = ClassLoadingSupport.singleton();
            classLoadingSupport.startIgnoreReflectionConfigurationScope();
            try {
                elementalResult = resolveElementalType(name, arrayDimensions, loader);
            } finally {
                classLoadingSupport.endIgnoreReflectionConfigurationScope();
            }
        } else {
            elementalResult = resolveInstanceType(name, loader);
        }
        Class<?> result = elementalResult;
        if (arrayDimensions > 0 && result != null) {
            result = getArrayClass(elementalResult, arrayDimensions);
        }
        if (result == null) {
            result = PredefinedClassesSupport.getLoadedForNameOrNull(name, loader);
        }
        checkResult(SubstrateUtil.cast(result, DynamicHub.class), name, loader != null);
        return result;
    }

    private void checkResult(DynamicHub result, String name, boolean throwOnClassNotFound) throws ClassNotFoundException {
        if (result == null && shouldFollowReflectionConfiguration()) {
            Throwable savedException = getSavedException(name);
            if (savedException != null) {
                if (savedException instanceof Error error) {
                    if (!RuntimeClassLoading.isSupported()) {
                        throw error;
                    } else {
                        return;
                    }
                } else if (savedException instanceof ClassNotFoundException cnfe) {
                    if (throwOnClassNotFound) {
                        throw cnfe;
                    }
                    return;
                }
                throw VMError.shouldNotReachHere("Unexpected exception type", savedException);
            }
        }
        boolean missingRegistration = shouldFollowReflectionConfiguration() && ClassNameSupport.isValidReflectionName(name) && shouldThrowMissingRegistrationError(result);
        if (throwMissingRegistrationErrors() && missingRegistration) {
            MissingReflectionRegistrationUtils.reportClassAccess(name);
        }
        if (throwOnClassNotFound && (result == null || result.isPrimitive())) {
            throw new ClassNotFoundException(name);
        }
    }

    private static boolean shouldThrowMissingRegistrationError(DynamicHub result) {
        if (result == null) {
            return true;
        }
        RuntimeDynamicAccessMetadata dynamicAccess = result.getDynamicAccessMetadata();
        return dynamicAccess == null || !dynamicAccess.satisfied();
    }

    private Throwable getSavedException(String name) {
        var cond = knownClassNames.get(name);
        if (cond == null || cond.getDynamicAccessMetadata() == null || !cond.getDynamicAccessMetadata().satisfied()) {
            return null;
        }
        Throwable exception = cond.getValue();
        if (exception == null) {
            exception = new ClassNotFoundException(name);
        }
        return exception;
    }

    private Class<?> resolveElementalType(String fullName, int arrayDimensions, ClassLoader loader) throws ClassNotFoundException {
        if (fullName.length() == arrayDimensions + 1) {
            return switch (fullName.charAt(arrayDimensions)) {
                case 'Z' -> boolean.class;
                case 'B' -> byte.class;
                case 'C' -> char.class;
                case 'S' -> short.class;
                case 'I' -> int.class;
                case 'F' -> float.class;
                case 'J' -> long.class;
                case 'D' -> double.class;
                default -> null; // also 'V'
            };
        }
        assert fullName.length() > arrayDimensions;
        ByteSequence elementalType = ByteSequence.createReplacingDot(fullName, arrayDimensions);
        return resolveInstanceType(loader, elementalType);
    }

    private Class<?> resolveInstanceType(String name, ClassLoader loader) throws ClassNotFoundException {
        ByteSequence elementalType = ByteSequence.createTypeFromName(name);
        return resolveInstanceType(loader, elementalType);
    }

    private Class<?> resolveInstanceType(ClassLoader loader, ByteSequence elementalType) throws ClassNotFoundException {
        Symbol<Type> type = SymbolsSupport.getTypes().getOrCreateValidType(elementalType);
        if (type == null) {
            return null;
        }
        return getRegistry(loader).loadClass(type);
    }

    private static Class<?> getArrayClass(Class<?> elementalResult, int arrayDimensions) {
        assert elementalResult != void.class : "Must be filtered in the caller";
        assert arrayDimensions > 0 && arrayDimensions <= 255 : "Must be filtered in the caller";
        DynamicHub hub = SubstrateUtil.cast(elementalResult, DynamicHub.class);
        int remainingDims = arrayDimensions;
        while (remainingDims > 1) {
            DynamicHub arrayHub = hub.getOrCreateArrayHub();
            if (arrayHub == null) {
                return null;
            }
            remainingDims--;
            hub = arrayHub;
        }
        // Perform the MissingRegistrationError check for the final element
        hub = hub.arrayType();
        return SubstrateUtil.cast(hub, Class.class);
    }

    public static Class<?> defineClass(ClassLoader loader, String name, byte[] b, int off, int len, ClassDefinitionInfo info) {
        // name is a "binary name": `foo.Bar$1`
        assert RuntimeClassLoading.isSupported();
        if (throwMissingRegistrationErrors() && shouldFollowReflectionConfiguration() && !singleton().knownClassNames.containsKey(name)) {
            MissingReflectionRegistrationUtils.reportClassAccess(name);
            // The defineClass path usually can't throw ClassNotFoundException
            throw sneakyThrow(new ClassNotFoundException(name));
        }
        AbstractRuntimeClassRegistry registry = (AbstractRuntimeClassRegistry) singleton().getRegistry(loader);
        if (name != null) {
            ByteSequence typeBytes = ByteSequence.createTypeFromName(name);
            Symbol<Type> type = SymbolsSupport.getTypes().getOrCreateValidType(typeBytes);
            if (type == null) {
                throw new NoClassDefFoundError(name);
            }
            return registry.defineClass(type, b, off, len, info);
        } else {
            return registry.defineClass(null, b, off, len, info);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable ex) throws T {
        throw (T) ex;
    }

    public static String loaderNameAndId(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        return SubstrateUtil.cast(loader, Target_java_lang_ClassLoader.class).nameAndId();
    }

    public AbstractClassRegistry getRegistry(ClassLoader loader) {
        if (loader == null || !ClassForNameSupport.respectClassLoader()) {
            return bootRegistry;
        }
        Target_java_lang_ClassLoader svmLoader = SubstrateUtil.cast(loader, Target_java_lang_ClassLoader.class);
        AbstractClassRegistry registry = svmLoader.classRegistry;
        if (registry == null) {
            synchronized (loader) {
                registry = svmLoader.classRegistry;
                if (registry == null) {
                    if (RuntimeClassLoading.isSupported()) {
                        registry = new UserDefinedClassRegistry(loader);
                    } else {
                        registry = new AOTClassRegistry(loader);
                    }
                    svmLoader.classRegistry = registry;
                }
            }
        }
        return registry;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public static void addAOTClass(ClassLoader loader, Class<?> cls) {
        Class<?> elementType = cls;
        while (elementType.isArray()) {
            elementType = elementType.getComponentType();
        }
        if (elementType == Class.class) {
            /*
             * Workaround for substitution classes in generic signatures. A proper fix will require
             * rewriting generic signatures of methods in substitution classes without an equivalent
             * original method, which is currently limited to some DynamicHub methods
             */
            ClassRegistries.addAOTClass(loader, DynamicHub.class);
        }
        if (!elementType.isPrimitive()) {
            buildTimeSingleton().getBuildTimeRegistry(loader).addAOTType(elementType);
        }
        addKnownClassName(AccessCondition.unconditional(), cls.getName(), null, false);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public static void addKnownClassName(AccessCondition condition, String typeName, Throwable exception, boolean preserved) {
        var knownClassNamesMap = buildTimeSingleton().knownClassNames;
        synchronized (knownClassNamesMap) {
            var cond = knownClassNamesMap.get(typeName);
            if (cond == null) {
                cond = new ConditionalRuntimeValue<>(RuntimeDynamicAccessMetadata.createHosted(condition, preserved), exception);
            } else {
                cond.getDynamicAccessMetadata().addCondition(condition);
                if (!preserved) {
                    cond.getDynamicAccessMetadata().setNotPreserved();
                }
                if (cond.getValueUnconditionally() == null && exception != null) {
                    cond = new ConditionalRuntimeValue<>(cond.getDynamicAccessMetadata(), exception);
                }
            }
            knownClassNamesMap.put(typeName, cond);
        }
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    private AbstractClassRegistry getBuildTimeRegistry(ClassLoader loader) {
        if (loader == null || !respectClassLoader()) {
            return bootRegistry;
        }
        return this.buildTimeRegistries.computeIfAbsent(loader, l -> {
            AbstractClassRegistry newRegistry;
            if (RuntimeClassLoading.isSupported()) {
                newRegistry = new UserDefinedClassRegistry(l);
            } else {
                newRegistry = new AOTClassRegistry(l);
            }
            return newRegistry;
        });
    }

    public static class ClassRegistryComputer implements FieldValueTransformer {
        @Override
        public Object transform(Object receiver, Object originalValue) {
            assert receiver != null;
            return ClassRegistries.singleton().getBuildTimeRegistry((ClassLoader) receiver);
        }
    }

    @Override
    public JavaVersion getJavaVersion() {
        return JavaVersion.HOST_VERSION;
    }

    @Override
    public boolean isStrictJavaCompliance() {
        return false;
    }

    @Override
    public TimerCollection getTimers() {
        return timers;
    }

    @Override
    public boolean isPreviewEnabled() {
        return PreviewFeatures.isEnabled();
    }

    final Logger logger = new Logger() {
        // Checkstyle: Allow raw info or warning printing - begin
        @Override
        public void log(String message) {
            Log.log().string("Warning: ").string(message).newline();
        }

        @Override
        public void log(Supplier<String> messageSupplier) {
            Log.log().string("Warning: ").string(messageSupplier.get()).newline();
        }

        @Override
        public void log(String message, Throwable throwable) {
            Log.log().string("Warning: ").string(message).newline();
            Log.log().exception(throwable);
        }
        // Checkstyle: Allow raw info or warning printing - end
    };

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public Symbol<Name> getOrCreateName(ByteSequence byteSequence) {
        return SymbolsSupport.getNames().getOrCreate(byteSequence);
    }

    @Override
    public Symbol<Type> getOrCreateTypeFromName(ByteSequence byteSequence) {
        return SymbolsSupport.getTypes().getOrCreateValidType(TypeSymbols.nameToType(byteSequence));
    }

    @Override
    public Symbol<? extends ModifiedUTF8> getOrCreateUtf8(ByteSequence byteSequence) {
        // Note: all symbols are strong for now
        return SymbolsSupport.getUtf8().getOrCreateValidUtf8(byteSequence, true);
    }

    private static boolean shouldFollowReflectionConfiguration() {
        return ClassLoadingSupport.singleton().followReflectionConfiguration();
    }
}
