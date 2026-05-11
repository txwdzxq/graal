/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.graal.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.oracle.svm.core.graal.GraalConfiguration;
import com.oracle.svm.core.graal.code.SubstrateRuntimeConstantBlindingPhase;

import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.PhaseSuite;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.common.LowTierLoweringPhase;
import jdk.graal.compiler.phases.constantblinding.ConstantBlindingPhase;
import jdk.graal.compiler.phases.constantblinding.ConstantPreBlindingPhase;
import jdk.graal.compiler.phases.schedule.SchedulePhase.FinalSchedulePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.graal.compiler.phases.tiers.LowTierContext;
import jdk.graal.compiler.phases.tiers.MidTierContext;
import jdk.graal.compiler.phases.tiers.Suites;

/*
 * The test directly instantiates compiler-suite internals from concealed packages. mx unittest
 * recognizes AddExports annotations and exports these packages before executing the test.
 */
@AddExports({
                "jdk.graal.compiler/jdk.graal.compiler.debug",
                "jdk.graal.compiler/jdk.graal.compiler.nodes",
                "jdk.graal.compiler/jdk.graal.compiler.phases",
                "jdk.graal.compiler/jdk.graal.compiler.phases.common",
                "jdk.graal.compiler/jdk.graal.compiler.phases.constantblinding",
                "jdk.graal.compiler/jdk.graal.compiler.phases.schedule",
                "jdk.graal.compiler/jdk.graal.compiler.phases.tiers",
                "jdk.graal.compiler.options/jdk.graal.compiler.options",
                "jdk.internal.vm.ci/jdk.vm.ci.code",
                "jdk.internal.vm.ci/jdk.vm.ci.meta",
                "jdk.internal.vm.ci/jdk.vm.ci.meta.annotation"
})
public class RuntimeConstantBlindingPhaseSuiteTest {

    @Test
    public void testRuntimeConstantBlindingPhasesAreInstalledBeforeRuntimeOptionIsSet() {
        Suites suites = newSuites();

        /*
         * BlindConstants is deliberately false here: sandbox policy can enable it only after these
         * runtime suites have been cached, so phase installation must not depend on this value.
         */
        TestGraalConfiguration.addRuntimeConstantBlinding(runtimeOptions(false), false, suites);

        List<BasePhase<? super LowTierContext>> phases = suites.getLowTier().getPhases();
        int preBlindingIndex = indexOf(phases, ConstantPreBlindingPhase.class);
        int loweringIndex = indexOf(phases, LowTierLoweringPhase.class);
        int runtimeBlindingIndex = indexOf(phases, SubstrateRuntimeConstantBlindingPhase.class);
        int finalScheduleIndex = indexOf(phases, FinalSchedulePhase.class);

        assertTrue("Constant pre-blinding must be present even when BlindConstants is enabled only later at image runtime", preBlindingIndex >= 0);
        assertTrue("Runtime constant blinding must be present even when BlindConstants is enabled only later at image runtime", runtimeBlindingIndex >= 0);
        assertTrue("Constant pre-blinding must run before low-tier lowering", preBlindingIndex < loweringIndex);
        assertTrue("Runtime constant blinding must run before final scheduling", runtimeBlindingIndex < finalScheduleIndex);
    }

    @Test
    public void testHostedSuitesDoNotInstallRuntimeConstantBlinding() {
        Suites suites = newSuites();

        TestGraalConfiguration.addRuntimeConstantBlinding(runtimeOptions(true), true, suites);

        List<BasePhase<? super LowTierContext>> phases = suites.getLowTier().getPhases();
        assertFalse(contains(phases, ConstantPreBlindingPhase.class));
        assertFalse(contains(phases, SubstrateRuntimeConstantBlindingPhase.class));
    }

    private static Suites newSuites() {
        PhaseSuite<HighTierContext> highTier = new PhaseSuite<>();
        PhaseSuite<MidTierContext> midTier = new PhaseSuite<>();
        PhaseSuite<LowTierContext> lowTier = new PhaseSuite<>();
        lowTier.appendPhase(new LowTierLoweringPhase(CanonicalizerPhase.create()));
        lowTier.appendPhase(new FinalSchedulePhase());
        return new Suites(highTier, midTier, lowTier);
    }

    private static OptionValues runtimeOptions(boolean blindConstants) {
        return new OptionValues(null, ConstantBlindingPhase.Options.BlindConstants, blindConstants);
    }

    private static boolean contains(List<BasePhase<? super LowTierContext>> phases, Class<? extends BasePhase<? super LowTierContext>> phaseClass) {
        return indexOf(phases, phaseClass) >= 0;
    }

    private static int indexOf(List<BasePhase<? super LowTierContext>> phases, Class<? extends BasePhase<? super LowTierContext>> phaseClass) {
        for (int i = 0; i < phases.size(); i++) {
            if (phaseClass.isInstance(phases.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /*
     * The helper under test is protected production API. Use a test-only subclass instead of
     * widening visibility or constructing ImageSingleton state just to reach the hook.
     */
    private static final class TestGraalConfiguration extends GraalConfiguration {
        static void addRuntimeConstantBlinding(OptionValues options, boolean hosted, Suites suites) {
            maybeAddRuntimeConstantBlinding(options, hosted, suites);
        }
    }

}
