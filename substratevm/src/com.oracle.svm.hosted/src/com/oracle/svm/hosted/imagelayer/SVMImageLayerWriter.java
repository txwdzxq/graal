/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.imagelayer;

import static com.oracle.svm.hosted.imagelayer.SVMImageLayerSnapshotUtil.CONSTRUCTOR_NAME;
import static com.oracle.svm.hosted.imagelayer.SVMImageLayerSnapshotUtil.DYNAMIC_HUB;
import static com.oracle.svm.hosted.imagelayer.SVMImageLayerSnapshotUtil.ENUM;
import static com.oracle.svm.hosted.imagelayer.SVMImageLayerSnapshotUtil.GENERATED_SERIALIZATION;
import static com.oracle.svm.hosted.imagelayer.SVMImageLayerSnapshotUtil.STRING;
import static com.oracle.svm.hosted.imagelayer.SVMImageLayerSnapshotUtil.UNDEFINED_CONSTANT_ID;
import static com.oracle.svm.hosted.imagelayer.SVMImageLayerSnapshotUtil.UNDEFINED_FIELD_INDEX;
import static com.oracle.svm.hosted.imagelayer.SnapshotWriters.initInts;
import static com.oracle.svm.hosted.imagelayer.SnapshotWriters.initSortedArray;
import static com.oracle.svm.hosted.imagelayer.SnapshotWriters.initStringList;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.graalvm.nativeimage.impl.CEntryPointLiteralCodePointer;
import org.graalvm.word.WordBase;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.api.HostVM;
import com.oracle.graal.pointsto.api.ImageLayerWriter;
import com.oracle.graal.pointsto.flow.AnalysisParsedGraph;
import com.oracle.graal.pointsto.heap.ImageHeap;
import com.oracle.graal.pointsto.heap.ImageHeapConstant;
import com.oracle.graal.pointsto.heap.ImageHeapInstance;
import com.oracle.graal.pointsto.heap.ImageHeapObjectArray;
import com.oracle.graal.pointsto.heap.ImageHeapPrimitiveArray;
import com.oracle.graal.pointsto.heap.ImageHeapRelocatableConstant;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.graal.pointsto.util.AnalysisFuture;
import com.oracle.svm.core.FunctionPointerHolder;
import com.oracle.svm.core.StaticFieldsSupport;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.classinitialization.ClassInitializationInfo;
import com.oracle.svm.core.graal.code.CGlobalDataBasePointer;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.imagelayer.LayeredImageOptions;
import com.oracle.svm.core.meta.MethodOffset;
import com.oracle.svm.core.meta.MethodPointer;
import com.oracle.svm.core.meta.MethodRef;
import com.oracle.svm.core.reflect.serialize.SerializationSupport;
import com.oracle.svm.core.threadlocal.FastThreadLocal;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.SVMHost;
import com.oracle.svm.hosted.ameta.FieldValueInterceptionSupport;
import com.oracle.svm.hosted.annotation.CustomSubstitutionType;
import com.oracle.svm.hosted.c.CGlobalDataFeature;
import com.oracle.svm.hosted.c.InitialLayerCGlobalTracking;
import com.oracle.svm.hosted.classinitialization.ClassInitializationSupport;
import com.oracle.svm.hosted.classinitialization.SimulateClassInitializerSupport;
import com.oracle.svm.hosted.code.CEntryPointCallStubMethod;
import com.oracle.svm.hosted.code.CEntryPointCallStubSupport;
import com.oracle.svm.hosted.code.FactoryMethod;
import com.oracle.svm.hosted.image.NativeImageHeap;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.jni.JNIJavaCallVariantWrapperMethod;
import com.oracle.svm.hosted.lambda.LambdaProxyRenamingSubstitutionProcessor;
import com.oracle.svm.hosted.lambda.LambdaSubstitutionType;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.meta.PatchedWordConstant;
import com.oracle.svm.hosted.methodhandles.MethodHandleInvokerRenamingSubstitutionProcessor;
import com.oracle.svm.hosted.methodhandles.MethodHandleInvokerSubstitutionType;
import com.oracle.svm.hosted.reflect.ReflectionExpandSignatureMethod;
import com.oracle.svm.hosted.reflect.proxy.ProxyRenamingSubstitutionProcessor;
import com.oracle.svm.hosted.reflect.proxy.ProxySubstitutionType;
import com.oracle.svm.hosted.snapshot.c.CEntryPointLiteralReferenceData;
import com.oracle.svm.hosted.snapshot.capnproto.CapnProtoSharedLayerSnapshotFormat;
import com.oracle.svm.hosted.snapshot.constant.ConstantReferenceData;
import com.oracle.svm.hosted.snapshot.constant.PersistedConstantData;
import com.oracle.svm.hosted.snapshot.constant.PersistedConstantData.ObjectValue;
import com.oracle.svm.hosted.snapshot.constant.RelinkingData;
import com.oracle.svm.hosted.snapshot.constant.RelinkingData.EnumConstant;
import com.oracle.svm.hosted.snapshot.constant.RelinkingData.FieldConstant;
import com.oracle.svm.hosted.snapshot.constant.RelinkingData.StringConstant;
import com.oracle.svm.hosted.snapshot.dynamichub.ClassInitializationInfoData;
import com.oracle.svm.hosted.snapshot.dynamichub.DynamicHubInfoData;
import com.oracle.svm.hosted.snapshot.elements.PersistedAnalysisFieldData;
import com.oracle.svm.hosted.snapshot.elements.PersistedAnalysisMethodData;
import com.oracle.svm.hosted.snapshot.elements.PersistedAnalysisMethodData.WrappedMethod;
import com.oracle.svm.hosted.snapshot.elements.PersistedAnalysisTypeData;
import com.oracle.svm.hosted.snapshot.elements.PersistedAnalysisTypeData.WrappedType;
import com.oracle.svm.hosted.snapshot.layer.SharedLayerSnapshotData;
import com.oracle.svm.hosted.snapshot.layer.SharedLayerSnapshotFormat;
import com.oracle.svm.hosted.snapshot.util.SnapshotStringList;
import com.oracle.svm.hosted.snapshot.util.SnapshotStructList;
import com.oracle.svm.hosted.substitute.PolymorphicSignatureWrapperMethod;
import com.oracle.svm.hosted.substitute.SubstitutionMethod;
import com.oracle.svm.sdk.staging.hosted.layeredimage.LayeredCompilationSupport;
import com.oracle.svm.sdk.staging.layeredimage.LayeredCompilationBehavior;
import com.oracle.svm.shared.singletons.ImageSingletonsSupportImpl.SingletonInfo;
import com.oracle.svm.shared.singletons.MultiLayeredImageSingleton;
import com.oracle.svm.shared.util.LogUtils;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.util.AnnotationUtil;

import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.java.LambdaUtils;
import jdk.graal.compiler.nodes.EncodedGraph;
import jdk.graal.compiler.nodes.GraphEncoder;
import jdk.graal.compiler.nodes.NodeClassMap;
import jdk.graal.compiler.util.ObjectCopier;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.MethodHandleAccessProvider.IntrinsicMethod;
import jdk.vm.ci.meta.PrimitiveConstant;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class SVMImageLayerWriter extends ImageLayerWriter {
    private final SVMImageLayerSnapshotUtil imageLayerSnapshotUtil;
    private ImageHeap imageHeap;
    private AnalysisUniverse aUniverse;
    private IdentityHashMap<String, String> internedStringsIdentityMap;

    private final SharedLayerSnapshotFormat sharedLayerSnapshotFormat = new CapnProtoSharedLayerSnapshotFormat();
    private final SharedLayerSnapshotData.Writer snapshotWriter = sharedLayerSnapshotFormat.createWriter();
    private Map<ImageHeapConstant, ConstantParent> constantsMap;
    private final Map<AnalysisMethod, MethodGraphsInfo> methodsMap = new ConcurrentHashMap<>();
    /**
     * This map is only used for validation, to ensure that all method descriptors are unique. A
     * duplicate method descriptor would cause methods to be incorrectly matched across layers,
     * which is really hard to debug and can have unexpected consequences.
     */
    private final Map<String, AnalysisMethod> methodDescriptors = new HashMap<>();
    private final Map<AnalysisMethod, Set<AnalysisMethod>> polymorphicSignatureCallers = new ConcurrentHashMap<>();
    private final LayerGraphStore graphStore;
    private final boolean useSharedLayerGraphs;
    private final boolean useSharedLayerStrengthenedGraphs;

    private NativeImageHeap nativeImageHeap;
    private HostedUniverse hUniverse;
    private final ClassInitializationSupport classInitializationSupport;
    private SimulateClassInitializerSupport simulateClassInitializerSupport;

    private boolean polymorphicSignatureSealed = false;

    /**
     * Used to encode {@link NodeClass} ids in {@link #persistGraph}.
     */
    private final NodeClassMap nodeClassMap = GraphEncoder.GLOBAL_NODE_CLASS_MAP;

    private record ConstantParent(int constantId, int index) {
        static ConstantParent NONE = new ConstantParent(UNDEFINED_CONSTANT_ID, UNDEFINED_FIELD_INDEX);
    }

    private record MethodGraphsInfo(String analysisGraphLocation, boolean analysisGraphIsIntrinsic,
                    String strengthenedGraphLocation) {

        static final MethodGraphsInfo NO_GRAPHS = new MethodGraphsInfo(null, false, null);

        MethodGraphsInfo withAnalysisGraph(AnalysisMethod method, String location, boolean isIntrinsic) {
            assert analysisGraphLocation == null && !analysisGraphIsIntrinsic : "Only one analysis graph can be persisted for a given method: " + method;
            return new MethodGraphsInfo(location, isIntrinsic, strengthenedGraphLocation);
        }

        MethodGraphsInfo withStrengthenedGraph(AnalysisMethod method, String location) {
            assert strengthenedGraphLocation == null : "Only one strengthened graph can be persisted for a given method: " + method;
            return new MethodGraphsInfo(analysisGraphLocation, analysisGraphIsIntrinsic, location);
        }
    }

    public SVMImageLayerWriter(SVMImageLayerSnapshotUtil imageLayerSnapshotUtil, boolean useSharedLayerGraphs, boolean useSharedLayerStrengthenedGraphs) {
        this.imageLayerSnapshotUtil = imageLayerSnapshotUtil;
        this.useSharedLayerGraphs = useSharedLayerGraphs;
        this.useSharedLayerStrengthenedGraphs = useSharedLayerStrengthenedGraphs;
        Path snapshotGraphsPath = HostedImageLayerBuildingSupport.singleton().getWriteLayerArchiveSupport().getSnapshotGraphsPath();
        graphStore = LayerGraphStore.openForWriting(snapshotGraphsPath);
        this.classInitializationSupport = ClassInitializationSupport.singleton();
    }

    public void setInternedStringsIdentityMap(IdentityHashMap<String, String> map) {
        this.internedStringsIdentityMap = map;
    }

    public void setImageHeap(ImageHeap heap) {
        this.imageHeap = heap;
    }

    public void setAnalysisUniverse(AnalysisUniverse aUniverse) {
        this.aUniverse = aUniverse;
    }

    public void setSimulateClassInitializerSupport(SimulateClassInitializerSupport simulateClassInitializerSupport) {
        this.simulateClassInitializerSupport = simulateClassInitializerSupport;
    }

    public void setNativeImageHeap(NativeImageHeap nativeImageHeap) {
        this.nativeImageHeap = nativeImageHeap;
    }

    public void setHostedUniverse(HostedUniverse hUniverse) {
        this.hUniverse = hUniverse;
    }

    public void dumpFiles() {
        SVMImageLayerSnapshotUtil.SVMGraphEncoder graphEncoder = imageLayerSnapshotUtil.getGraphEncoder(null);
        byte[] encodedNodeClassMap = ObjectCopier.encode(graphEncoder, nodeClassMap);
        String location = graphStore.write(encodedNodeClassMap);
        snapshotWriter.setNodeClassMapLocation(location);
        graphStore.close();

        Path snapshotFile = HostedImageLayerBuildingSupport.singleton().getWriteLayerArchiveSupport().getSnapshotPath();
        try {
            sharedLayerSnapshotFormat.write(snapshotFile, snapshotWriter);
        } catch (IOException e) {
            throw VMError.shouldNotReachHere("Unable to write " + snapshotFile, e);
        }
    }

    public void initializeExternalValues() {
        imageLayerSnapshotUtil.initializeExternalValues();
    }

    public void setEndOffset(long endOffset) {
        snapshotWriter.setImageHeapEndOffset(endOffset);
    }

    @Override
    public void onTrackedAcrossLayer(AnalysisMethod method, Object reason) {
        if (method.wrapped instanceof FactoryMethod factoryMethod) {
            AnalysisMethod targetConstructor = method.getUniverse().lookup(factoryMethod.getTargetConstructor());
            targetConstructor.registerAsTrackedAcrossLayers(reason);
        }
    }

    public void persistAnalysisInfo() {
        ImageHeapConstant staticPrimitiveFields = (ImageHeapConstant) hUniverse.getSnippetReflection().forObject(StaticFieldsSupport.getCurrentLayerStaticPrimitiveFields());
        ImageHeapConstant staticObjectFields = (ImageHeapConstant) hUniverse.getSnippetReflection().forObject(StaticFieldsSupport.getCurrentLayerStaticObjectFields());

        snapshotWriter.setStaticPrimitiveFieldsConstantId(ImageHeapConstant.getConstantID(staticPrimitiveFields));
        snapshotWriter.setStaticObjectFieldsConstantId(ImageHeapConstant.getConstantID(staticObjectFields));

        // Late constant scan so all of them are known with values available (readers installed)
        List<ImageHeapConstant> constantsToScan = new ArrayList<>();
        imageHeap.getReachableObjects().values().forEach(constantsToScan::addAll);
        constantsMap = HashMap.newHashMap(constantsToScan.size());
        constantsToScan.forEach(c -> constantsMap.put(c, ConstantParent.NONE));
        /*
         * Some child constants of reachable constants are not reachable because they are only used
         * in snippets, but still need to be persisted.
         */
        while (!constantsToScan.isEmpty()) {
            List<ImageHeapConstant> discoveredConstants = new ArrayList<>();
            constantsToScan.forEach(con -> scanConstantReferencedObjects(con, discoveredConstants));
            constantsToScan = discoveredConstants;
        }

        snapshotWriter.setNextTypeId(aUniverse.getNextTypeId());
        snapshotWriter.setNextMethodId(aUniverse.getNextMethodId());
        snapshotWriter.setNextFieldId(aUniverse.getNextFieldId());
        snapshotWriter.setNextConstantId(ImageHeapConstant.getCurrentId());

        polymorphicSignatureSealed = true;

        AnalysisType[] typesToPersist = aUniverse.getTypes().stream().filter(AnalysisType::isTrackedAcrossLayers).sorted(Comparator.comparingInt(AnalysisType::getId))
                        .toArray(AnalysisType[]::new);
        initSortedArray(snapshotWriter::initTypes, typesToPersist, this::persistType);
        var dispatchTableSingleton = LayeredDispatchTableFeature.singleton();
        initSortedArray(snapshotWriter::initDynamicHubInfos, typesToPersist,
                        (AnalysisType aType, Supplier<DynamicHubInfoData.Writer> builderSupplier) -> dispatchTableSingleton
                                        .persistDynamicHubInfo(hUniverse.lookup(aType), builderSupplier));

        AnalysisMethod[] methodsToPersist = aUniverse.getMethods().stream().filter(AnalysisMethod::isTrackedAcrossLayers).sorted(Comparator.comparingInt(AnalysisMethod::getId))
                        .toArray(AnalysisMethod[]::new);
        initSortedArray(snapshotWriter::initMethods, methodsToPersist, this::persistMethod);
        methodDescriptors.clear();

        AnalysisField[] fieldsToPersist = aUniverse.getFields().stream().filter(AnalysisField::isTrackedAcrossLayers).sorted(Comparator.comparingInt(AnalysisField::getId))
                        .toArray(AnalysisField[]::new);
        initSortedArray(snapshotWriter::initFields, fieldsToPersist, this::persistField);

        InitialLayerCGlobalTracking initialLayerCGlobalTracking = CGlobalDataFeature.singleton().getInitialLayerCGlobalTracking();
        initSortedArray(snapshotWriter::initCGlobals, initialLayerCGlobalTracking.getInfosOrderedByIndex(), initialLayerCGlobalTracking::persistCGlobalInfo);

        /*
         * Note the set of elements within the hosted method array are created as a side effect of
         * persisting methods and dynamic hubs, so it must persisted after these operations.
         */
        HostedMethod[] hMethodsToPersist = dispatchTableSingleton.acquireHostedMethodArray();
        initSortedArray(snapshotWriter::initHostedMethods, hMethodsToPersist, dispatchTableSingleton::persistHostedMethod);
        dispatchTableSingleton.releaseHostedMethodArray();

        @SuppressWarnings({"unchecked", "cast"})
        Entry<ImageHeapConstant, ConstantParent>[] constantsToPersist = (Entry<ImageHeapConstant, ConstantParent>[]) constantsMap.entrySet().stream()
                        .sorted(Comparator.comparingInt(a -> ImageHeapConstant.getConstantID(a.getKey())))
                        .toArray(Entry[]::new);
        Set<Integer> constantsToRelink = new HashSet<>(); // noEconomicSet(streaming)
        initSortedArray(snapshotWriter::initConstants, constantsToPersist,
                        (entry, bsupplier) -> persistConstant(entry.getKey(), entry.getValue(), bsupplier.get(), constantsToRelink));
        initInts(snapshotWriter::initConstantsToRelink, constantsToRelink.stream().mapToInt(i -> i).sorted());
    }

    private void persistType(AnalysisType type, Supplier<PersistedAnalysisTypeData.Writer> builderSupplier) {
        PersistedAnalysisTypeData.Writer builder = builderSupplier.get();
        HostVM hostVM = aUniverse.hostVM();
        SVMHost svmHost = (SVMHost) hostVM;
        DynamicHub hub = svmHost.dynamicHub(type);
        builder.setHubIdentityHashCode(System.identityHashCode(hub));
        builder.setHasArrayType(hub.getArrayHub() != null);

        ClassInitializationInfo info = hub.getClassInitializationInfo();
        if (info == null) {
            /* Type metadata was not initialized. */
            assert !type.isReachable() : type;
            builder.setHasClassInitInfo(false);
        } else {
            builder.setHasClassInitInfo(true);
            ClassInitializationInfoData.Writer b = builder.initClassInitializationInfo();
            b.setIsInitialized(info.isInitialized());
            b.setIsInErrorState(info.isInErrorState());
            b.setIsLinked(info.isExactlyLinked());
            b.setHasInitializer(info.hasInitializer());
            b.setIsBuildTimeInitialized(info.isBuildTimeInitialized());
            b.setIsTracked(info.isTracked());
            FunctionPointerHolder classInitializer = info.getRuntimeClassInitializer();
            if (classInitializer != null) {
                MethodPointer methodPointer = (MethodPointer) classInitializer.functionPointer;
                VMError.guarantee(methodPointer.permitsRewriteToPLT() == MethodPointer.DEFAULT_PERMIT_REWRITE_TO_PLT, "Non-default value currently not supported, add in schema if needed");
                AnalysisMethod classInitializerMethod = (AnalysisMethod) methodPointer.getMethod();
                b.setInitializerMethodId(classInitializerMethod.getId());
            }
        }

        builder.setId(type.getId());
        builder.setDescriptor(imageLayerSnapshotUtil.getTypeDescriptor(type));

        initInts(builder::initFields, Arrays.stream(type.getInstanceFields(true)).mapToInt(f -> ((AnalysisField) f).getId()));
        builder.setClassJavaName(type.toJavaName());
        builder.setClassName(type.getName());
        builder.setModifiers(type.getModifiers());
        builder.setIsInterface(type.isInterface());
        builder.setIsEnum(type.isEnum());
        builder.setIsRecord(type.isRecord());
        builder.setIsInitialized(type.isInitialized());
        boolean successfulSimulation = simulateClassInitializerSupport.isSuccessfulSimulation(type);
        boolean failedSimulation = simulateClassInitializerSupport.isFailedSimulation(type);
        VMError.guarantee(!(successfulSimulation && failedSimulation), "Class init simulation cannot be both successful and failed.");
        builder.setIsSuccessfulSimulation(successfulSimulation);
        builder.setIsFailedSimulation(failedSimulation);
        builder.setIsFailedInitialization(classInitializationSupport.isFailedInitialization(type.getJavaClass()));
        builder.setIsLinked(type.isLinked());
        if (type.getSourceFileName() != null) {
            builder.setSourceFileName(type.getSourceFileName());
        }
        try {
            AnalysisType enclosingType = type.getEnclosingType();
            if (enclosingType != null) {
                builder.setEnclosingTypeId(enclosingType.getId());
            }
        } catch (InternalError | TypeNotPresentException | LinkageError e) {
            /* Ignore missing type errors. */
        }
        if (type.isArray()) {
            builder.setComponentTypeId(type.getComponentType().getId());
        }
        if (type.getSuperclass() != null) {
            builder.setSuperClassTypeId(type.getSuperclass().getId());
        }
        initInts(builder::initInterfaces, Arrays.stream(type.getInterfaces()).mapToInt(AnalysisType::getId));
        initInts(builder::initInstanceFieldIds, Arrays.stream(type.getInstanceFields(false)).mapToInt(f -> ((AnalysisField) f).getId()));
        initInts(builder::initInstanceFieldIdsWithSuper, Arrays.stream(type.getInstanceFields(true)).mapToInt(f -> ((AnalysisField) f).getId()));
        initInts(builder::initStaticFieldIds, Arrays.stream(type.getStaticFields()).mapToInt(f -> ((AnalysisField) f).getId()));
        AnnotationSnapshotCodec.writeAnnotations(type, builder::initAnnotationList);

        builder.setIsInstantiated(type.isInstantiated());
        builder.setIsUnsafeAllocated(type.isUnsafeAllocated());
        builder.setIsReachable(type.isReachable());

        delegatePersistType(type, builder);

        Set<AnalysisType> subTypes = type.getSubTypes().stream().filter(AnalysisElement::isTrackedAcrossLayers).collect(Collectors.toSet());
        var subTypesBuilder = builder.initSubTypes(subTypes.size());
        int i = 0;
        for (AnalysisType subType : subTypes) {
            subTypesBuilder.set(i, subType.getId());
            i++;
        }
        builder.setIsAnySubtypeInstantiated(type.isAnySubtypeInstantiated());

        afterTypeAdded(type);
    }

    protected void delegatePersistType(AnalysisType type, PersistedAnalysisTypeData.Writer builder) {
        if (type.toJavaName(true).contains(GENERATED_SERIALIZATION)) {
            WrappedType.SerializationGenerated.Writer b = builder.getWrappedType().initSerializationGenerated();
            var key = SerializationSupport.currentLayer().getKeyFromConstructorAccessorClass(type.getJavaClass());
            b.setRawDeclaringClassId(key.declaringClassId());
            b.setRawTargetConstructorId(key.targetConstructorClassId());
        } else if (LambdaUtils.isLambdaType(type)) {
            WrappedType.Lambda.Writer b = builder.getWrappedType().initLambda();
            b.setCapturingClass(LambdaUtils.capturingClass(type.toJavaName()));
        } else if (ProxyRenamingSubstitutionProcessor.isProxyType(type)) {
            builder.getWrappedType().setProxyType();
        }
    }

    /**
     * Some types can have an unstable name between two different image builds. To avoid producing
     * wrong results, a warning should be printed if such types exist in the resulting image.
     */
    private static void afterTypeAdded(AnalysisType type) {
        /*
         * Lambda functions containing the same method invocations will return the same hash. They
         * will still have a different name, but in a multi threading context, the names can be
         * switched.
         */
        if (type.getWrapped() instanceof LambdaSubstitutionType lambdaSubstitutionType) {
            if (!LambdaProxyRenamingSubstitutionProcessor.singleton().isNameAlwaysStable(lambdaSubstitutionType.getName())) {
                String message = "The lambda method " + lambdaSubstitutionType.getName() + " might not have a stable name in the extension image.";
                handleNameConflict(message);
            }
        }
        /*
         * Method handle with the same inner method handles will return the same hash. They will
         * still have a different name, but in a multi threading context, the names can be switched.
         */
        if (type.getWrapped() instanceof MethodHandleInvokerSubstitutionType methodHandleSubstitutionType) {
            if (!MethodHandleInvokerRenamingSubstitutionProcessor.singleton().isNameAlwaysStable(methodHandleSubstitutionType.getName())) {
                String message = "The method handle " + methodHandleSubstitutionType.getName() + " might not have a stable name in the extension image.";
                handleNameConflict(message);
            }
        }

        if (type.getWrapped() instanceof ProxySubstitutionType proxySubstitutionType) {
            if (!ProxyRenamingSubstitutionProcessor.isNameAlwaysStable(proxySubstitutionType.getName())) {
                String message = "The Proxy type " + proxySubstitutionType.getName() + " might not have a stable name in the extension image.";
                handleNameConflict(message);
            }
        }
    }

    private static void handleNameConflict(String message) {
        if (LayeredImageOptions.LayeredImageDiagnosticOptions.AbortOnNameConflict.getValue()) {
            throw VMError.shouldNotReachHere(message);
        } else if (LayeredImageOptions.LayeredImageDiagnosticOptions.LogOnNameConflict.getValue()) {
            LogUtils.warning(message);
        }
    }

    private void persistMethod(AnalysisMethod method, Supplier<PersistedAnalysisMethodData.Writer> builderSupplier) {
        PersistedAnalysisMethodData.Writer builder = builderSupplier.get();
        MethodGraphsInfo graphsInfo = methodsMap.putIfAbsent(method, MethodGraphsInfo.NO_GRAPHS);
        Executable executable = method.getJavaMethod();

        if (executable != null) {
            initStringList(builder::initArgumentClassNames, Arrays.stream(executable.getParameterTypes()).map(Class::getName));
            builder.setClassName(executable.getDeclaringClass().getName());
        }

        String methodDescriptor = imageLayerSnapshotUtil.getMethodDescriptor(method);
        if (methodDescriptors.putIfAbsent(methodDescriptor, method) != null) {
            throw GraalError.shouldNotReachHere("The method descriptor should be unique, but %s got added twice.\nThe first method is %s and the second is %s."
                            .formatted(methodDescriptor, methodDescriptors.get(methodDescriptor), method));
        }
        builder.setDescriptor(methodDescriptor);
        builder.setDeclaringTypeId(method.getDeclaringClass().getId());
        initInts(builder::initArgumentTypeIds, method.getSignature().toParameterList(null).stream().mapToInt(AnalysisType::getId));
        builder.setId(method.getId());
        builder.setName(method.getName());
        builder.setReturnTypeId(method.getSignature().getReturnType().getId());
        builder.setIsVarArgs(method.isVarArgs());
        builder.setIsBridge(method.isBridge());
        builder.setCanBeStaticallyBound(method.canBeStaticallyBound());
        builder.setModifiers(method.getModifiers());
        builder.setIsConstructor(method.isConstructor());
        builder.setIsSynthetic(method.isSynthetic());
        builder.setIsDeclared(method.isDeclared());
        byte[] code = method.getCode();
        if (code != null) {
            builder.setBytecode(code);
        }
        builder.setBytecodeSize(method.getCodeSize());
        IntrinsicMethod intrinsicMethod = aUniverse.getBigbang().getConstantReflectionProvider().getMethodHandleAccess().lookupMethodHandleIntrinsic(method);
        if (intrinsicMethod != null) {
            builder.setMethodHandleIntrinsicName(intrinsicMethod.name());
        }
        AnnotationSnapshotCodec.writeAnnotations(method, builder::initAnnotationList);

        builder.setIsVirtualRootMethod(method.isVirtualRootMethod());
        builder.setIsDirectRootMethod(method.isDirectRootMethod());
        builder.setIsInvoked(method.isSimplyInvoked());
        builder.setIsImplementationInvoked(method.isSimplyImplementationInvoked());
        builder.setIsIntrinsicMethod(method.isIntrinsicMethod());

        var compilationBehavior = method.getCompilationBehavior();
        if (compilationBehavior == LayeredCompilationBehavior.Behavior.PINNED_TO_INITIAL_LAYER) {
            UserError.guarantee(hUniverse.lookup(method).isCompiled(), "User methods with layered compilation behavior %s must be registered via %s in the initial layer",
                            LayeredCompilationBehavior.Behavior.PINNED_TO_INITIAL_LAYER, LayeredCompilationSupport.class);
        }
        builder.setCompilationBehaviorOrdinal((byte) compilationBehavior.ordinal());

        if (graphsInfo != null && graphsInfo.analysisGraphLocation != null) {
            assert !method.isDelayed() : "The method " + method + " has an analysis graph, but is delayed to the application layer";
            builder.setAnalysisGraphLocation(graphsInfo.analysisGraphLocation);
            builder.setAnalysisGraphIsIntrinsic(graphsInfo.analysisGraphIsIntrinsic);
        }
        if (graphsInfo != null && graphsInfo.strengthenedGraphLocation != null) {
            assert !method.isDelayed() : "The method " + method + " has a strengthened graph, but is delayed to the application layer";
            builder.setStrengthenedGraphLocation(graphsInfo.strengthenedGraphLocation);
        }

        delegatePersistMethod(method, builder);

        HostedMethod hMethod = hUniverse.lookup(method);
        builder.setHostedMethodIndex(LayeredDispatchTableFeature.singleton().getPersistedHostedMethodIndex(hMethod));
    }

    protected void delegatePersistMethod(AnalysisMethod method, PersistedAnalysisMethodData.Writer builder) {
        if (method.wrapped instanceof FactoryMethod factoryMethod) {
            WrappedMethod.FactoryMethod.Writer b = builder.getWrappedMethod().initFactoryMethod();
            AnalysisMethod targetConstructor = method.getUniverse().lookup(factoryMethod.getTargetConstructor());
            b.setTargetConstructorId(targetConstructor.getId());
            b.setThrowAllocatedObject(factoryMethod.throwAllocatedObject());
            AnalysisType instantiatedType = method.getUniverse().lookup(factoryMethod.getInstantiatedType());
            b.setInstantiatedTypeId(instantiatedType.getId());
        } else if (method.wrapped instanceof CEntryPointCallStubMethod cEntryPointCallStubMethod) {
            WrappedMethod.CEntryPointCallStub.Writer b = builder.getWrappedMethod().initCEntryPointCallStub();
            AnalysisMethod originalMethod = CEntryPointCallStubSupport.singleton().getMethodForStub(cEntryPointCallStubMethod);
            b.setOriginalMethodId(originalMethod.getId());
            b.setNotPublished(cEntryPointCallStubMethod.isNotPublished());
        } else if (method.wrapped instanceof ReflectionExpandSignatureMethod reflectionExpandSignatureMethod) {
            WrappedMethod.WrappedMember.Writer b = builder.getWrappedMethod().initWrappedMember();
            b.setReflectionExpandSignature();
            Executable member = reflectionExpandSignatureMethod.getMember();
            persistMethodWrappedMember(b, member);
        } else if (method.wrapped instanceof JNIJavaCallVariantWrapperMethod jniJavaCallVariantWrapperMethod) {
            WrappedMethod.WrappedMember.Writer b = builder.getWrappedMethod().initWrappedMember();
            b.setJavaCallVariantWrapper();
            Executable executable = jniJavaCallVariantWrapperMethod.getMember();
            persistMethodWrappedMember(b, executable);
        } else if (method.wrapped instanceof SubstitutionMethod substitutionMethod && substitutionMethod.getAnnotated() instanceof PolymorphicSignatureWrapperMethod) {
            WrappedMethod.PolymorphicSignature.Writer b = builder.getWrappedMethod().initPolymorphicSignature();
            Set<AnalysisMethod> callers = polymorphicSignatureCallers.get(method);
            var callersBuilder = b.initCallers(callers.size());
            int i = 0;
            for (AnalysisMethod caller : callers) {
                callersBuilder.set(i, caller.getId());
                i++;
            }
        }
    }

    private static void persistMethodWrappedMember(PersistedAnalysisMethodData.WrappedMethod.WrappedMember.Writer b, Executable member) {
        b.setName(member instanceof Constructor<?> ? CONSTRUCTOR_NAME : member.getName());
        b.setDeclaringClassName(member.getDeclaringClass().getName());
        Parameter[] params = member.getParameters();
        SnapshotStringList.Writer atb = b.initArgumentTypeNames(params.length);
        for (int i = 0; i < params.length; i++) {
            atb.set(i, params[i].getType().getName());
        }
    }

    private void persistField(AnalysisField field, Supplier<PersistedAnalysisFieldData.Writer> fieldBuilderSupplier) {
        PersistedAnalysisFieldData.Writer builder = fieldBuilderSupplier.get();

        builder.setId(field.getId());
        builder.setDeclaringTypeId(field.getDeclaringClass().getId());
        builder.setName(field.getName());
        builder.setIsAccessed(field.getAccessedReason() != null);
        builder.setIsRead(field.getReadReason() != null);
        builder.setIsWritten(field.getWrittenReason() != null);
        builder.setIsFolded(field.getFoldedReason() != null);
        builder.setIsUnsafeAccessed(field.isUnsafeAccessed());
        builder.setIsStatic(field.isStatic());
        builder.setIsInternal(field.isInternal());
        builder.setIsSynthetic(field.isSynthetic());
        builder.setTypeId(field.getType().getId());
        builder.setModifiers(field.getModifiers());
        builder.setPosition(field.getPosition());

        HostedField hostedField = hUniverse.lookup(field);
        builder.setLocation(hostedField.getLocation());
        int fieldInstalledNum = MultiLayeredImageSingleton.LAYER_NUM_UNINSTALLED;
        LayeredStaticFieldSupport.LayerAssignmentStatus assignmentStatus = LayeredStaticFieldSupport.singleton().getAssignmentStatus(field);
        if (hostedField.hasInstalledLayerNum()) {
            fieldInstalledNum = hostedField.getInstalledLayerNum();
            if (assignmentStatus == LayeredStaticFieldSupport.LayerAssignmentStatus.UNSPECIFIED) {
                assignmentStatus = LayeredStaticFieldSupport.LayerAssignmentStatus.PRIOR_LAYER;
            } else {
                assert assignmentStatus == LayeredStaticFieldSupport.LayerAssignmentStatus.APP_LAYER_REQUESTED ||
                                assignmentStatus == LayeredStaticFieldSupport.LayerAssignmentStatus.APP_LAYER_DEFERRED : assignmentStatus;
            }
        }
        builder.setPriorInstalledLayerNum(fieldInstalledNum);
        builder.setAssignmentStatus(assignmentStatus.ordinal());

        var updatableReceivers = LayeredFieldValueTransformerSupport.singleton().getUpdatableReceivers(field);
        var receivers = builder.initUpdatableReceivers(updatableReceivers.size());
        int idx = 0;
        for (var receiver : updatableReceivers) {
            receivers.set(idx, ImageHeapConstant.getConstantID(receiver));
            idx++;
        }

        AnnotationSnapshotCodec.writeAnnotations(field, builder::initAnnotationList);

        JavaConstant simulatedFieldValue = simulateClassInitializerSupport.getSimulatedFieldValue(field);
        writeConstant(simulatedFieldValue, builder.initSimulatedFieldValue());
    }

    private void persistConstant(ImageHeapConstant imageHeapConstant, ConstantParent parent, PersistedConstantData.Writer builder, Set<Integer> constantsToRelink) {
        ObjectInfo objectInfo = nativeImageHeap.getConstantInfo(imageHeapConstant);
        builder.setObjectOffset((objectInfo == null) ? -1 : objectInfo.getOffset());

        int id = ImageHeapConstant.getConstantID(imageHeapConstant);
        builder.setId(id);
        AnalysisType type = imageHeapConstant.getType();
        AnalysisError.guarantee(type.isTrackedAcrossLayers(), "Type %s from constant %s should have been marked as trackedAcrossLayers, but was not", type, imageHeapConstant);
        builder.setTypeId(type.getId());

        ConstantReflectionProvider constantReflection = aUniverse.getBigbang().getConstantReflectionProvider();
        int identityHashCode = constantReflection.identityHashCode(imageHeapConstant);
        builder.setIdentityHashCode(identityHashCode);

        switch (imageHeapConstant) {
            case ImageHeapInstance imageHeapInstance -> {
                builder.initObject().setInstance();
                persistConstantObjectData(builder.getObject(), imageHeapInstance::getFieldValue, imageHeapInstance.getFieldValuesSize());
                persistConstantRelinkingInfo(builder, imageHeapConstant, constantsToRelink, aUniverse.getBigbang());
            }
            case ImageHeapObjectArray imageHeapObjectArray -> {
                builder.initObject().setObjectArray();
                persistConstantObjectData(builder.getObject(), imageHeapObjectArray::getElement, imageHeapObjectArray.getLength());
            }
            case ImageHeapPrimitiveArray imageHeapPrimitiveArray ->
                SnapshotPrimitiveArrays.write(builder.initPrimitiveData(), imageHeapPrimitiveArray.getType().getComponentType().getJavaKind(), imageHeapPrimitiveArray.getArray());
            case ImageHeapRelocatableConstant relocatableConstant ->
                builder.initRelocatable().setKey(relocatableConstant.getConstantData().key);
            default -> throw AnalysisError.shouldNotReachHere("Unexpected constant type " + imageHeapConstant);
        }

        if (!constantsToRelink.contains(id) && parent != ConstantParent.NONE) {
            builder.setParentConstantId(parent.constantId);
            assert parent.index != UNDEFINED_FIELD_INDEX : "Tried to persist child constant %s from parent constant %d, but got index %d".formatted(imageHeapConstant, parent.constantId, parent.index);
            builder.setParentIndex(parent.index);
        }
    }

    private void persistConstantRelinkingInfo(PersistedConstantData.Writer builder, ImageHeapConstant imageHeapConstant, Set<Integer> constantsToRelink, BigBang bb) {
        AnalysisType type = imageHeapConstant.getType();
        JavaConstant hostedObject = imageHeapConstant.getHostedObject();
        boolean simulated = hostedObject == null;
        builder.setIsSimulated(simulated);
        if (!simulated) {
            RelinkingData.Writer relinkingBuilder = builder.getObject().getRelinking();
            int id = ImageHeapConstant.getConstantID(imageHeapConstant);
            boolean tryStaticFinalFieldRelink = true;
            if (aUniverse.lookup(DYNAMIC_HUB).equals(type)) {
                AnalysisType constantType = (AnalysisType) bb.getConstantReflectionProvider().asJavaType(hostedObject);
                relinkingBuilder.initClassConstant().setTypeId(constantType.getId());
                constantsToRelink.add(id);
                tryStaticFinalFieldRelink = false;
            } else if (aUniverse.lookup(STRING).equals(type)) {
                StringConstant.Writer stringConstantBuilder = relinkingBuilder.initStringConstant();
                String value = bb.getSnippetReflectionProvider().asObject(String.class, hostedObject);
                if (internedStringsIdentityMap.containsKey(value)) {
                    /*
                     * Interned strings must be relinked.
                     */
                    stringConstantBuilder.setValue(value);
                    constantsToRelink.add(id);
                    tryStaticFinalFieldRelink = false;
                }
            } else if (aUniverse.lookup(ENUM).isAssignableFrom(type)) {
                EnumConstant.Writer enumBuilder = relinkingBuilder.initEnumConstant();
                Enum<?> value = bb.getSnippetReflectionProvider().asObject(Enum.class, hostedObject);
                enumBuilder.setEnumClass(value.getDeclaringClass().getName());
                enumBuilder.setEnumName(value.name());
                constantsToRelink.add(id);
                tryStaticFinalFieldRelink = false;
            }
            if (tryStaticFinalFieldRelink && shouldRelinkConstant(imageHeapConstant) && imageHeapConstant.getOrigin() != null) {
                AnalysisField field = imageHeapConstant.getOrigin();
                if (shouldRelinkField(field)) {
                    FieldConstant.Writer fieldConstantBuilder = relinkingBuilder.initFieldConstant();
                    fieldConstantBuilder.setOriginFieldId(field.getId());
                    fieldConstantBuilder.setRequiresLateLoading(requiresLateLoading(imageHeapConstant, field));
                }
            }
        }
    }

    private boolean shouldRelinkConstant(ImageHeapConstant heapConstant) {
        /*
         * FastThreadLocals need to be registered by the object replacer and relinking constants
         * from the CrossLayerRegistry would skip the custom code associated.
         */
        Object o = aUniverse.getHostedValuesProvider().asObject(Object.class, heapConstant.getHostedObject());
        return !(o instanceof FastThreadLocal) && !CrossLayerConstantRegistryFeature.singleton().isConstantRegistered(o);
    }

    private static boolean shouldRelinkField(AnalysisField field) {
        return !AnnotationUtil.isAnnotationPresent(field, Delete.class) &&
                        ClassInitializationSupport.singleton().maybeInitializeAtBuildTime(field.getDeclaringClass()) &&
                        field.isStatic() && field.isFinal() && field.isTrackedAcrossLayers() && field.installableInLayer();
    }

    private static boolean requiresLateLoading(ImageHeapConstant imageHeapConstant, AnalysisField field) {
        /*
         * CustomSubstitutionTypes need to be loaded after the substitution are installed.
         *
         * Intercepted fields need to be loaded after the interceptor is installed.
         */
        return imageHeapConstant.getType().getWrapped() instanceof CustomSubstitutionType ||
                        FieldValueInterceptionSupport.hasFieldValueInterceptor(field);
    }

    private void persistConstantObjectData(ObjectValue.Writer builder, IntFunction<Object> valuesFunction, int size) {
        SnapshotStructList.Writer<ConstantReferenceData.Writer> refsBuilder = builder.initData(size);
        for (int i = 0; i < size; ++i) {
            Object object = valuesFunction.apply(i);
            ConstantReferenceData.Writer b = refsBuilder.get(i);
            if (delegateProcessing(b, object)) {
                /* The object was already persisted */
                continue;
            }
            if (object instanceof JavaConstant javaConstant && maybeWriteConstant(javaConstant, b)) {
                continue;
            }
            AnalysisError.guarantee(object instanceof AnalysisFuture<?>, "Unexpected constant %s", object);
            b.setNotMaterialized();
        }
    }

    private boolean maybeWriteConstant(JavaConstant constant, ConstantReferenceData.Writer builder) {
        if (constant instanceof ImageHeapConstant imageHeapConstant) {
            assert constantsMap.containsKey(imageHeapConstant) : imageHeapConstant;
            var ocb = builder.initObjectConstant();
            ocb.setConstantId(ImageHeapConstant.getConstantID(imageHeapConstant));
        } else if (constant instanceof PrimitiveConstant primitiveConstant) {
            var pb = builder.initPrimitiveValue();
            pb.setTypeChar(NumUtil.safeToUByte(primitiveConstant.getJavaKind().getTypeChar()));
            pb.setRawValue(primitiveConstant.getRawValue());
        } else if (constant.equals(JavaConstant.NULL_POINTER)) {
            builder.setNullPointer();
        } else {
            return false;
        }
        return true;
    }

    private static boolean delegateProcessing(ConstantReferenceData.Writer builder, Object constant) {
        if (constant instanceof PatchedWordConstant patchedWordConstant) {
            WordBase word = patchedWordConstant.getWord();
            if (word instanceof MethodRef methodRef) {
                AnalysisMethod method = getRelocatableConstantMethod(methodRef);
                switch (methodRef) {
                    case MethodOffset _ -> builder.initMethodOffset().setMethodId(method.getId());
                    case MethodPointer mp -> {
                        ConstantReferenceData.MethodPointer.Writer b = builder.initMethodPointer();
                        b.setMethodId(method.getId());
                        b.setPermitsRewriteToPLT(mp.permitsRewriteToPLT());
                    }
                    default -> throw VMError.shouldNotReachHere("Unsupported method ref: " + methodRef);
                }
                return true;
            } else if (word instanceof CEntryPointLiteralCodePointer cp) {
                CEntryPointLiteralReferenceData.Writer b = builder.initCEntryPointLiteralCodePointer();
                b.setMethodName(cp.methodName);
                b.setDefiningClass(cp.definingClass.getName());
                b.initParameterNames(cp.parameterTypes.length);
                for (int i = 0; i < cp.parameterTypes.length; i++) {
                    b.getParameterNames().set(i, cp.parameterTypes[i].getName());
                }
                return true;
            } else if (word instanceof CGlobalDataBasePointer) {
                builder.setCGlobalDataBasePointer();
                return true;
            }
        }
        return false;
    }

    private void scanConstantReferencedObjects(ImageHeapConstant constant, Collection<ImageHeapConstant> discoveredConstants) {
        if (Objects.requireNonNull(constant) instanceof ImageHeapInstance instance) {
            scanConstantReferencedObjects(constant, instance::getFieldValue, instance.getFieldValuesSize(), discoveredConstants);
        } else if (constant instanceof ImageHeapObjectArray objArray) {
            scanConstantReferencedObjects(constant, objArray::getElement, objArray.getLength(), discoveredConstants);
        }
    }

    private void scanConstantReferencedObjects(ImageHeapConstant constant, IntFunction<Object> referencedObjectFunction, int size, Collection<ImageHeapConstant> discoveredConstants) {
        for (int i = 0; i < size; i++) {
            AnalysisType parentType = constant.getType();
            Object obj = referencedObjectFunction.apply(i);
            if (obj instanceof ImageHeapConstant con && !constantsMap.containsKey(con)) {
                /*
                 * Some constants are not in imageHeap#reachableObjects, but are still created in
                 * reachable constants. They can be created in the extension image, but should not
                 * be used.
                 */
                Set<Integer> relinkedFields = imageLayerSnapshotUtil.getRelinkedFields(parentType, aUniverse);
                ConstantParent parent = relinkedFields.contains(i) ? new ConstantParent(ImageHeapConstant.getConstantID(constant), i) : ConstantParent.NONE;

                discoveredConstants.add(con);
                constantsMap.put(con, parent);
            } else if (obj instanceof MethodRef mr) {
                getRelocatableConstantMethod(mr).registerAsTrackedAcrossLayers("In method ref");
            }
        }
    }

    private static AnalysisMethod getRelocatableConstantMethod(MethodRef methodRef) {
        ResolvedJavaMethod method = methodRef.getMethod();
        if (method instanceof HostedMethod hostedMethod) {
            return hostedMethod.wrapped;
        } else {
            return (AnalysisMethod) method;
        }
    }

    @Override
    public void persistAnalysisParsedGraph(AnalysisMethod method, AnalysisParsedGraph analysisParsedGraph) {
        String location = persistGraph(method, analysisParsedGraph.getEncodedGraph());
        if (location != null) {
            /*
             * This method should only be called once for each method. This check is performed by
             * withAnalysisGraph as it will throw if the MethodGraphsInfo already has an analysis
             * graph.
             */
            methodsMap.compute(method, (_, mgi) -> (mgi != null ? mgi : MethodGraphsInfo.NO_GRAPHS)
                            .withAnalysisGraph(method, location, analysisParsedGraph.isIntrinsic()));
        }
    }

    public void persistMethodStrengthenedGraph(AnalysisMethod method) {
        if (!useSharedLayerStrengthenedGraphs) {
            return;
        }

        EncodedGraph analyzedGraph = method.getAnalyzedGraph();
        String location = persistGraph(method, analyzedGraph);
        /*
         * This method should only be called once for each method. This check is performed by
         * withStrengthenedGraph as it will throw if the MethodGraphsInfo already has a strengthened
         * graph.
         */
        methodsMap.compute(method, (_, mgi) -> (mgi != null ? mgi : MethodGraphsInfo.NO_GRAPHS).withStrengthenedGraph(method, location));
    }

    private String persistGraph(AnalysisMethod method, EncodedGraph analyzedGraph) {
        if (!useSharedLayerGraphs) {
            return null;
        }
        if (Arrays.stream(analyzedGraph.getObjects()).anyMatch(o -> o instanceof AnalysisFuture<?>)) {
            /*
             * GR-61103: After the AnalysisFuture in this node is handled, this check can be
             * removed.
             */
            return null;
        }
        byte[] encodedGraph = ObjectCopier.encode(imageLayerSnapshotUtil.getGraphEncoder(nodeClassMap), analyzedGraph);
        if (contains(encodedGraph, LambdaUtils.LAMBDA_CLASS_NAME_SUBSTRING.getBytes(StandardCharsets.UTF_8))) {
            throw AnalysisError.shouldNotReachHere("The graph for the method %s contains a reference to a lambda type, which cannot be decoded: %s".formatted(method, encodedGraph));
        }
        return graphStore.write(encodedGraph);
    }

    private static boolean contains(byte[] data, byte[] seq) {
        outer: for (int i = 0; i <= data.length - seq.length; i++) {
            for (int j = 0; j < seq.length; j++) {
                if (data[i + j] != seq[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    public void addPolymorphicSignatureCaller(AnalysisMethod polymorphicSignature, AnalysisMethod caller) {
        AnalysisError.guarantee(!polymorphicSignatureSealed, "The caller %s for method %s was added after the methods were persisted", caller, polymorphicSignature);
        polymorphicSignatureCallers.computeIfAbsent(polymorphicSignature, _ -> ConcurrentHashMap.newKeySet()).add(caller);
    }

    public void writeImageSingletonInfo(List<Entry<Class<?>, SingletonInfo>> layeredImageSingletons) {
        SVMImageSingletonSnapshotWriter.writeImageSingletonInfo(layeredImageSingletons, snapshotWriter, aUniverse, hUniverse);
    }

    public void writeConstant(JavaConstant constant, ConstantReferenceData.Writer builder) {
        if (constant == null) {
            return;
        }
        if (!maybeWriteConstant(constant, builder)) {
            throw VMError.shouldNotReachHere("Unexpected constant: " + constant);
        }
    }

}
