/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.option;

import java.lang.reflect.Field;

import org.graalvm.collections.UnmodifiableEconomicMap;
import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.graal.pointsto.ObjectScanner;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.svm.core.IsolateArgumentParser;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.option.HostedOptionValues;
import com.oracle.svm.core.option.RuntimeOptionKey;
import com.oracle.svm.core.option.RuntimeOptionParser;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FeatureImpl;

import jdk.graal.compiler.options.OptionDescriptor;
import jdk.graal.compiler.options.OptionKey;

@AutomaticallyRegisteredFeature
public class RuntimeOptionFeature implements InternalFeature {

    private RuntimeOptionParser runtimeOptionParser;

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        runtimeOptionParser = new RuntimeOptionParser();
        ImageSingletons.add(RuntimeOptionParser.class, runtimeOptionParser);
    }

    @Override
    public void duringSetup(DuringSetupAccess a) {
        FeatureImpl.DuringSetupAccessImpl access = (FeatureImpl.DuringSetupAccessImpl) a;
        access.registerObjectReachableCallback(OptionKey.class, this::collectOptionKeys);
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        FeatureImpl.BeforeAnalysisAccessImpl accessImpl = (FeatureImpl.BeforeAnalysisAccessImpl) access;

        UnmodifiableEconomicMap<OptionKey<?>, Object> map = HostedOptionValues.singleton().getMap();
        for (OptionKey<?> key : map.getKeys()) {
            if (key instanceof RuntimeOptionKey<?> runtimeOptionKey && runtimeOptionKey.shouldRegisterForIsolateArgumentParser()) {
                /*
                 * The list of options IsolateArgumentParser has to parse, is built dynamically, to
                 * include only options of the current configuration. Here, all options that should
                 * get parsed by the IsolateArgumentParser are added to this list.
                 */
                IsolateArgumentParser.singleton().register(runtimeOptionKey);
                registerOptionAsRead(accessImpl, runtimeOptionKey.getDescriptor().getDeclaringClass(), runtimeOptionKey.getName());
            }
        }

        IsolateArgumentParser.singleton().sealOptions();
    }

    @SuppressWarnings("unused")
    private void collectOptionKeys(DuringAnalysisAccess access, OptionKey<?> optionKey, ObjectScanner.ScanReason reason) {
        if (optionKey instanceof HostedOptionKey<?>) {
            /* HostedOptionKey's are reached when building the NativeImage driver executable. */
            return;
        }
        OptionDescriptor optionDescriptor = optionKey.getDescriptor();
        if (optionDescriptor == null) {
            throw VMError.shouldNotReachHere("No OptionDescriptor registered for an OptionKey. Often that is the result of an incomplete build. " +
                            "The registration code is generated by an annotation processor at build time, so a clean and full rebuild often helps to solve this problem");
        }

        if (optionDescriptor.getContainer().optionsAreDiscoverable()) {
            runtimeOptionParser.addDescriptor(optionDescriptor);
        }
    }

    public static void registerOptionAsRead(FeatureImpl.BeforeAnalysisAccessImpl accessImpl, Class<?> clazz, String fieldName) {
        try {
            Field javaField = clazz.getField(fieldName);
            AnalysisField analysisField = accessImpl.getMetaAccess().lookupJavaField(javaField);
            accessImpl.registerAsRead(analysisField, "it is a runtime option field");
        } catch (NoSuchFieldException | SecurityException e) {
            throw VMError.shouldNotReachHere(e);
        }
    }
}
