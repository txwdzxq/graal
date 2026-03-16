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
package com.oracle.svm.hosted.foreign;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_UNKNOWN;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_UNKNOWN;

import java.lang.invoke.MethodHandle;

import com.oracle.svm.hosted.ForeignHostedSupport;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.replacements.nodes.MacroNode;
import jdk.graal.compiler.replacements.nodes.MethodHandleNode;
import jdk.graal.compiler.replacements.nodes.MethodHandleNode.GraphAdder;
import jdk.graal.compiler.replacements.nodes.MethodHandleNode.InvokeFactory;
import jdk.graal.compiler.replacements.nodes.MethodHandleWithExceptionNode;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MethodHandleAccessProvider;
import jdk.vm.ci.meta.MethodHandleAccessProvider.IntrinsicMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Signature;

/**
 * Same as {@link MethodHandleWithExceptionNode} but in addition, this node can resolve a
 * NativeEntryPoint to the appropriate downcall stub. Since SVM downcall stubs are
 * {@link com.oracle.svm.hosted.code.NonBytecodeMethod non-bytecode methods}, this allows inlining
 * them.
 */
@NodeInfo(cycles = CYCLES_UNKNOWN, cyclesRationale = "see MacroNode", size = SIZE_UNKNOWN, sizeRationale = "see MacroNode")
public final class NativeMethodHandleWithExceptionNode extends MethodHandleWithExceptionNode {
    public static final NodeClass<NativeMethodHandleWithExceptionNode> TYPE = NodeClass.create(NativeMethodHandleWithExceptionNode.class);

    public NativeMethodHandleWithExceptionNode(IntrinsicMethod intrinsicMethod, MacroNode.MacroParams p) {
        super(TYPE, intrinsicMethod, p);
    }

    /**
     * Attempts to transform an invocation of an intrinsifiable {@link MethodHandle} method into an
     * invocation on another method with possibly transformed arguments.
     *
     * @param methodHandleAccess objects for accessing the implementation internals of a
     *            {@link MethodHandle}
     * @return a more direct invocation derived from the {@link MethodHandle} call or null
     */
    @Override
    protected <T extends Invoke> T tryResolveTargetInvoke(GraphAdder adder, InvokeFactory<T> factory, MethodHandleAccessProvider methodHandleAccess, ValueNode... argumentsArray) {
        if (intrinsicMethod == IntrinsicMethod.LINK_TO_NATIVE) {
            return getLinkToNativeTarget(adder, factory, methodHandleAccess, targetMethod, bci, returnStamp, argumentsArray);
        }
        return super.tryResolveTargetInvoke(adder, factory, methodHandleAccess, argumentsArray);
    }

    /**
     * Get the NativeEntryPoint argument of a MethodHandle.linkToNative call.
     *
     * @return the NativeEntryPoint argument node (which is the last argument)
     */
    private static ValueNode getNativeEntryPoint(ValueNode[] arguments) {
        return arguments[arguments.length - 1];
    }

    /**
     * Used for the MethodHandle.linkToNative method ({@link IntrinsicMethod#LINK_TO_NATIVE} method)
     * to get the target {@link InvokeNode} (an invocation to the downcall stub) if the
     * NativeEntryPoint argument is constant.
     *
     * @return invoke node for the member name target
     */
    static <T extends Invoke> T getLinkToNativeTarget(GraphAdder adder, InvokeFactory<T> factory, MethodHandleAccessProvider methodHandleAccess, ResolvedJavaMethod original, int bci,
                    StampPair returnStamp, ValueNode[] arguments) {
        ValueNode nativeEntryPoint = getNativeEntryPoint(arguments);
        if (nativeEntryPoint.isConstant()) {
            ResolvedJavaMethod downcallStub = ForeignHostedSupport.singleton().resolveDowncallStub(nativeEntryPoint.asJavaConstant());
            return NativeMethodHandleWithExceptionNode.getTargetInvokeNode(adder, factory, methodHandleAccess, bci, returnStamp, arguments, downcallStub, original);
        }
        return null;
    }

    /**
     * Helper function to get the {@link InvokeNode} for the downcall stub specified by the
     * jdk.internal.foreign.abi.NativeEntryPoint argument.
     *
     * @param target the target, already loaded from the native entry point node
     * @return invoke node for the native entry point target
     */
    private static <T extends Invoke> T getTargetInvokeNode(GraphAdder adder, InvokeFactory<T> factory,
                    MethodHandleAccessProvider methodHandleAccess, int bci, StampPair returnStamp, ValueNode[] originalArguments, ResolvedJavaMethod target, ResolvedJavaMethod original) {
        if (target == null || !isConsistentInfo(methodHandleAccess, original, target)) {
            return null;
        }

        /*
         * In lambda forms we erase signature types to avoid resolving issues involving class
         * loaders. When we optimize a method handle invoke to a direct call we must cast the
         * receiver and arguments to its actual types.
         */
        Signature signature = target.getSignature();
        assert target.isStatic() : "downcall stub is expected to be static";

        Assumptions assumptions = adder.getAssumptions();

        // Don't mutate the passed in arguments
        ValueNode[] arguments = originalArguments.clone();

        // Cast reference arguments to its type.
        for (int index = 0; index < signature.getParameterCount(false); index++) {
            JavaType parameterType = signature.getParameterType(index, target.getDeclaringClass());
            MethodHandleNode.maybeCastArgument(adder, arguments, index, parameterType);
        }
        T invoke = createDowncallStubInvokeNode(factory, assumptions, target, bci, returnStamp, arguments);
        assert invoke != null : "graph has been modified so this must result an invoke";
        return invoke;
    }

    /**
     * Creates an {@link InvokeNode} for the given target method (i.e. the downcall stub). The
     * {@link CallTargetNode} passed to the InvokeNode is in fact a {@link MethodCallTargetNode}.
     *
     * @return invoke node for the native entry point target (i.e. downcall stub)
     */
    private static <T extends Invoke> T createDowncallStubInvokeNode(InvokeFactory<T> factory, Assumptions assumptions,
                    ResolvedJavaMethod target, int bci, StampPair returnStamp, ValueNode[] arguments) {
        assert target.isStatic() : "downcall stub is expected to be static";
        JavaType targetReturnType = target.getSignature().getReturnType(null);

        StampPair targetReturnStamp = StampFactory.forDeclaredType(assumptions, targetReturnType, false);

        // All arguments are passed to 'linkToNative'. Even the trailing NativeEntryPoint argument.
        MethodCallTargetNode callTarget = new MethodCallTargetNode(InvokeKind.Static, target, arguments, targetReturnStamp, null);

        /*
         * The call target can have a different return type than the invoker, e.g. the target
         * returns an Object but the invoker void. In this case we need to use the stamp of the
         * invoker. Note: always using the invoker's stamp would be wrong because it's a less
         * concrete type (usually java.lang.Object).
         */
        Stamp stamp = targetReturnStamp.getTrustedStamp();
        if (returnStamp.getTrustedStamp().getStackKind() == JavaKind.Void) {
            stamp = StampFactory.forVoid();
        }
        return factory.create(callTarget, bci, stamp);
    }

    private static boolean isConsistentInfo(MethodHandleAccessProvider methodHandleAccess, ResolvedJavaMethod original, ResolvedJavaMethod target) {
        IntrinsicMethod originalIntrinsicMethod = methodHandleAccess.lookupMethodHandleIntrinsic(original);
        if (originalIntrinsicMethod != IntrinsicMethod.LINK_TO_NATIVE) {
            return false;
        }
        IntrinsicMethod targetIntrinsicMethod = methodHandleAccess.lookupMethodHandleIntrinsic(target);
        Signature originalSignature = original.getSignature();
        Signature targetSignature = target.getSignature();

        boolean invokeThroughMHIntrinsic = targetIntrinsicMethod == null;
        if (!invokeThroughMHIntrinsic) {
            return original.getName().equals(target.getName()) && originalSignature.equals(targetSignature);
        }

        // Linkers have appendix argument which is not passed to callee.
        if (originalSignature.getParameterCount(original.hasReceiver()) != targetSignature.getParameterCount(target.hasReceiver())) {
            return false; // parameter count mismatch
        }
        // LINK_TO_NATIVE is static -> no receiver
        if (target.hasReceiver()) {
            return false;
        }

        assert targetSignature.getParameterCount(false) == originalSignature.getParameterCount(false) : "argument count mismatch";
        int argCount = targetSignature.getParameterCount(false);
        for (int i = 0; i < argCount; i++) {
            if (originalSignature.getParameterKind(i).getStackKind() != targetSignature.getParameterKind(i).getStackKind()) {
                return false;
            }
        }
        // Only check the return type if the symbolic info has non-void return type.
        // I.e. the return value of the resolved method can be dropped.
        return originalSignature.getReturnKind() == JavaKind.Void ||
                        originalSignature.getReturnKind().getStackKind() == targetSignature.getReturnKind().getStackKind();
    }
}
