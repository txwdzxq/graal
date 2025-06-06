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

package com.oracle.svm.hosted.webimage.wasmgc.debug;

import org.graalvm.nativeimage.Platforms;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.webimage.wasm.debug.WasmDebug;
import com.oracle.svm.webimage.platform.WebImageWasmGCPlatform;

@AutomaticallyRegisteredFeature
@Platforms(WebImageWasmGCPlatform.class)
public class WasmGCDebugFeature implements InternalFeature {

    @Override
    public void registerForeignCalls(SubstrateForeignCallsProvider foreignCalls) {
        foreignCalls.register(WasmDebug.NULL_METHOD_POINTER);
        foreignCalls.register(WasmDebug.LOG_ERROR);
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess a) {
        FeatureImpl.BeforeAnalysisAccessImpl access = (FeatureImpl.BeforeAnalysisAccessImpl) a;
        access.getBigBang().addRootMethod((AnalysisMethod) WasmDebug.NULL_METHOD_POINTER.findMethod(access.getMetaAccess()), true,
                        "Target for null method pointer, registered in " + WasmGCDebugFeature.class);
        access.getBigBang().addRootMethod((AnalysisMethod) WasmDebug.LOG_ERROR.findMethod(access.getMetaAccess()), true,
                        "Low level error logging, registered in " + WasmGCDebugFeature.class);
    }
}
