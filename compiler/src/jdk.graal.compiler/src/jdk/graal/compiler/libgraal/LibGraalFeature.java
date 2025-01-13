/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.libgraal;

import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import jdk.graal.compiler.core.common.spi.ForeignCallSignature;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.hotspot.EncodedSnippets;
import jdk.graal.compiler.hotspot.HotSpotForeignCallLinkage;
import jdk.graal.compiler.hotspot.HotSpotReplacementsImpl;
import jdk.graal.compiler.libgraal.truffle.LibGraalTruffleHostEnvironmentLookup;
import jdk.graal.compiler.options.OptionsParser;
import jdk.graal.compiler.serviceprovider.GraalServices;
import jdk.graal.compiler.truffle.host.TruffleHostEnvironment;
import jdk.graal.compiler.util.ObjectCopier;
import jdk.internal.module.Modules;
import jdk.vm.ci.hotspot.HotSpotJVMCIBackendFactory;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.services.JVMCIServiceLocator;
import org.graalvm.collections.EconomicMap;
import org.graalvm.jniutils.NativeBridgeSupport;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.FieldValueTransformer;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import jdk.graal.compiler.core.common.Fields;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Edges;
import jdk.graal.compiler.options.OptionDescriptor;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.core.common.FeatureComponent;
import jdk.graal.nativeimage.LibGraalLoader;
import jdk.vm.ci.hotspot.HotSpotModifiers;
import org.graalvm.nativeimage.hosted.RuntimeSystemProperties;

/**
 * This feature builds the libgraal shared library (e.g., libjvmcicompiler.so on linux).
 */
@Platforms(Platform.HOSTED_ONLY.class)
public final class LibGraalFeature implements Feature {

    /**
     * Prefix to be used when {@linkplain RuntimeSystemProperties#register registering} properties
     * describing the image configuration for libgraal. This is analogous to the configuration info
     * displayed by {@code -XshowSettings}.
     *
     * For example:
     *
     * <pre>
     * RuntimeSystemProperties.register(NATIVE_IMAGE_SETTING_KEY_PREFIX + "gc", "serial");
     * </pre>
     */
    public static final String NATIVE_IMAGE_SETTING_KEY_PREFIX = "org.graalvm.nativeimage.setting.";

    private static LibGraalFeature singleton;

    public LibGraalFeature() {
        synchronized (LibGraalFeature.class) {
            GraalError.guarantee(singleton == null, "only a single %s instance should be created", LibGraalFeature.class.getName());
            singleton = this;
        }
    }

    /**
     * @return the singleton {@link LibGraalFeature} instance if called within the context of the
     *         class loader used to load the code being compiled into the libgraal image, otherwise
     *         null
     */
    public static LibGraalFeature singleton() {
        // Cannot use ImageSingletons here as it is not initialized early enough.
        return singleton;
    }

    /**
     * Looks up a class in the libgraal class loader.
     *
     * @throws Error if the lookup fails
     */
    public static Class<?> lookupClass(String className) {
        try {
            return Class.forName(className, false, LibGraalFeature.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new GraalError(ex);
        }
    }

    /**
     * Looks up a field via reflection and makes it accessible for reading.
     *
     * @throws Error if the operation fails
     */
    public static Field lookupField(Class<?> declaringClass, String fieldName) {
        try {
            Field result = declaringClass.getDeclaredField(fieldName);
            Modules.addOpensToAllUnnamed(declaringClass.getModule(), declaringClass.getPackageName());
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new GraalError(ex);
        }
    }

    public static final class IsEnabled implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            Class<LibGraalFeature> clazz = LibGraalFeature.class;
            return ImageSingletons.contains(clazz);
        }
    }

    final LibGraalLoader libgraalLoader = (LibGraalLoader) getClass().getClassLoader();

    /**
     * Set of {@link FeatureComponent}s created during analysis.
     */
    private final Set<FeatureComponent> libGraalFeatureComponents = ConcurrentHashMap.newKeySet();

    public void addFeatureComponent(FeatureComponent fc) {
        libGraalFeatureComponents.add(fc);
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(NativeBridgeSupport.class, new LibGraalNativeBridgeSupport());

        // The qualified exports from java.base to jdk.internal.vm.ci
        // and jdk.graal.compiler need to be expressed as exports to
        // ALL-UNNAMED so that access is also possible when these classes
        // are loaded via the libgraal loader.
        Module javaBase = ModuleLayer.boot().findModule("java.base").orElseThrow();
        Set<ModuleDescriptor.Exports> exports = javaBase.getDescriptor().exports();
        for (ModuleDescriptor.Exports e : exports) {
            if (e.targets().contains("jdk.internal.vm.ci") || e.targets().contains("jdk.graal.compiler")) {
                Modules.addExportsToAllUnnamed(javaBase, e.source());
            }
        }
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        optionCollector = new OptionCollector();
        access.registerObjectReachabilityHandler(optionCollector::accept, OptionKey.class);
        GetJNIConfig.register((ClassLoader) libgraalLoader);
    }

    private OptionCollector optionCollector;

    /**
     * Collects all instances of the LibGraalLoader loaded {@link OptionKey} class reached by the
     * static analysis.
     */
    private class OptionCollector implements Consumer<OptionKey<?>> {
        private final Set<OptionKey<?>> options = Collections.newSetFromMap(new ConcurrentHashMap<>());

        /**
         * Libgraal compiler options info.
         */
        private final OptionsParser.LibGraalOptionsInfo compilerOptionsInfo;

        private boolean sealed;

        OptionCollector() {
            compilerOptionsInfo = OptionsParser.setLibgraalOptions(OptionsParser.LibGraalOptionsInfo.create());
        }

        @Override
        public void accept(OptionKey<?> option) {
            if (sealed) {
                GraalError.guarantee(options.contains(option), "All options must have been discovered during static analysis: %s", option);
            } else {
                options.add(option);
            }
        }

        void afterAnalysis(AfterAnalysisAccess access) {
            sealed = true;
            Map<String, String> modules = libgraalLoader.getModuleMap();
            for (OptionKey<?> option : options) {
                OptionDescriptor descriptor = option.getDescriptor();
                if (descriptor.isServiceLoaded()) {
                    GraalError.guarantee(access.isReachable(option.getClass()), "%s", option.getClass());
                    GraalError.guarantee(access.isReachable(descriptor.getClass()), "%s", descriptor.getClass());

                    String name = option.getName();
                    compilerOptionsInfo.descriptors().put(name, descriptor);

                    String module = modules.get(descriptor.getDeclaringClass().getName());
                    if (module.contains("enterprise")) {
                        compilerOptionsInfo.enterpriseOptions().add(name);
                    }
                }
            }
        }
    }

    private BeforeCompilationAccess beforeCompilationAccess;

    /**
     * Transformer for {@code Fields.offsets} and {@code Edges.iterationMask} which need to be
     * recomputed to use SVM field offsets instead of HotSpot field offsets.
     */
    class FieldOffsetsTransformer implements FieldValueTransformer {
        /**
         * Map from {@link Fields} objects to a (newOffsets, newIterationMask) tuple represented as
         * a {@link java.util.Map.Entry} value.
         */
        private final Map<Object, Map.Entry<long[], Long>> replacements = new IdentityHashMap<>();

        final Field fieldsOffsetsField;
        final Field edgesIterationMaskField;

        FieldOffsetsTransformer() {
            fieldsOffsetsField = lookupField(Fields.class, "offsets");
            edgesIterationMaskField = lookupField(Edges.class, "iterationMask");
        }

        void register(BeforeAnalysisAccess access) {
            access.registerFieldValueTransformer(fieldsOffsetsField, this);
            access.registerFieldValueTransformer(edgesIterationMaskField, this);
        }

        @Override
        public boolean isAvailable() {
            return beforeCompilationAccess != null;
        }

        @Override
        public Object transform(Object receiver, Object originalValue) {
            Map.Entry<long[], Long> repl = getReplacement(receiver);
            if (originalValue instanceof long[]) {
                return repl.getKey();
            }
            return repl.getValue();
        }

        private Map.Entry<long[], Long> getReplacement(Object receiver) {
            synchronized (replacements) {
                return replacements.computeIfAbsent(receiver, this::computeReplacement);
            }
        }

        private Map.Entry<long[], Long> computeReplacement(Object receiver) {
            Fields fields = (Fields) receiver;
            return fields.recomputeOffsetsAndIterationMask(beforeCompilationAccess);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {

        new FieldOffsetsTransformer().register(access);

        /* Contains static fields that depend on HotSpotJVMCIRuntime */
        RuntimeClassInitialization.initializeAtRunTime(HotSpotModifiers.class);
        RuntimeClassInitialization.initializeAtRunTime(lookupClass("jdk.vm.ci.hotspot.HotSpotCompiledCodeStream"));
        RuntimeClassInitialization.initializeAtRunTime(lookupClass("jdk.vm.ci.hotspot.HotSpotCompiledCodeStream$Tag"));

        /* Needed for runtime calls to BoxingSnippets.Templates.getCacheClass(JavaKind) */
        RuntimeReflection.registerAllDeclaredClasses(Character.class);
        RuntimeReflection.register(lookupField(lookupClass("java.lang.Character$CharacterCache"), "cache"));
        RuntimeReflection.registerAllDeclaredClasses(Byte.class);
        RuntimeReflection.register(lookupField(lookupClass("java.lang.Byte$ByteCache"), "cache"));
        RuntimeReflection.registerAllDeclaredClasses(Short.class);
        RuntimeReflection.register(lookupField(lookupClass("java.lang.Short$ShortCache"), "cache"));
        RuntimeReflection.registerAllDeclaredClasses(Integer.class);
        RuntimeReflection.register(lookupField(lookupClass("java.lang.Integer$IntegerCache"), "cache"));
        RuntimeReflection.registerAllDeclaredClasses(Long.class);
        RuntimeReflection.register(lookupField(lookupClass("java.lang.Long$LongCache"), "cache"));

        doLegacyJVMCIInitialization();

        Path libGraalJavaHome = libgraalLoader.getJavaHome();
        GetCompilerConfig.Result configResult = GetCompilerConfig.from(libGraalJavaHome);
        for (var e : configResult.opens().entrySet()) {
            Module module = ModuleLayer.boot().findModule(e.getKey()).orElseThrow();
            for (String source : e.getValue()) {
                Modules.addOpensToAllUnnamed(module, source);
            }
        }

        EconomicMap<String, Object> libgraalObjects = (EconomicMap<String, Object>) ObjectCopier.decode(configResult.encodedConfig(), (ClassLoader) libgraalLoader);
        EncodedSnippets encodedSnippets = (EncodedSnippets) libgraalObjects.get("encodedSnippets");

        // Mark all the Node classes as allocated so they are available during graph decoding.
        for (NodeClass<?> nodeClass : encodedSnippets.getSnippetNodeClasses()) {
            access.registerAsInHeap(nodeClass.getClazz());
        }
        HotSpotReplacementsImpl.setEncodedSnippets(encodedSnippets);

        List<ForeignCallSignature> foreignCallSignatures = (List<ForeignCallSignature>) libgraalObjects.get("foreignCallSignatures");
        HotSpotForeignCallLinkage.Stubs.initStubs(foreignCallSignatures);

        TruffleHostEnvironment.overrideLookup(new LibGraalTruffleHostEnvironmentLookup());
    }

    /**
     * Initialization of JVMCI code that needs to be done for JDK versions that do not include
     * JDK-8346781.
     */
    private void doLegacyJVMCIInitialization() {
        if (!BeforeJDK8346781.VALUE) {
            return;
        }
        try {
            String rawArch = GraalServices.getSavedProperty("os.arch");
            String arch = switch (rawArch) {
                case "x86_64", "amd64" -> "AMD64";
                case "aarch64" -> "aarch64";
                case "riscv64" -> "riscv64";
                default -> throw new GraalError("Unknown or unsupported arch: %s", rawArch);
            };

            ClassLoader cl = (ClassLoader) libgraalLoader;
            Field cachedHotSpotJVMCIBackendFactoriesField = ObjectCopier.getField(HotSpotJVMCIRuntime.class, "cachedHotSpotJVMCIBackendFactories");
            GraalError.guarantee(cachedHotSpotJVMCIBackendFactoriesField.get(null) == null, "Expect cachedHotSpotJVMCIBackendFactories to be null");
            ServiceLoader<HotSpotJVMCIBackendFactory> load = ServiceLoader.load(HotSpotJVMCIBackendFactory.class, cl);
            List<HotSpotJVMCIBackendFactory> backendFactories = load.stream()//
                            .map(ServiceLoader.Provider::get)//
                            .filter(s -> s.getArchitecture().equals(arch))//
                            .toList();
            cachedHotSpotJVMCIBackendFactoriesField.set(null, backendFactories);
            GraalError.guarantee(backendFactories.size() == 1, "%s", backendFactories);

            var jvmciServiceLocatorCachedLocatorsField = ObjectCopier.getField(JVMCIServiceLocator.class, "cachedLocators");
            GraalError.guarantee(jvmciServiceLocatorCachedLocatorsField.get(null) == null, "Expect cachedLocators to be null");
            Iterable<JVMCIServiceLocator> serviceLocators = ServiceLoader.load(JVMCIServiceLocator.class, cl);
            List<JVMCIServiceLocator> cachedLocators = new ArrayList<>();
            serviceLocators.forEach(cachedLocators::add);
            jvmciServiceLocatorCachedLocatorsField.set(null, cachedLocators);
        } catch (Throwable e) {
            throw new GraalError(e);
        }
    }

    @Override
    public void duringAnalysis(DuringAnalysisAccess access) {
        for (var c : libGraalFeatureComponents) {
            c.duringAnalysis(this, access);
        }
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        optionCollector.afterAnalysis(access);
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        beforeCompilationAccess = access;
    }
}
