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

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.TargetElement;
import com.oracle.svm.espresso.classfile.descriptors.ByteSequence;
import com.oracle.svm.espresso.classfile.descriptors.NameSymbols;
import com.oracle.svm.espresso.classfile.descriptors.SignatureSymbols;
import com.oracle.svm.espresso.classfile.descriptors.Symbol;
import com.oracle.svm.espresso.classfile.descriptors.Symbols;
import com.oracle.svm.espresso.classfile.descriptors.TypeSymbols;
import com.oracle.svm.espresso.classfile.descriptors.Utf8Symbols;
import com.oracle.svm.shared.singletons.LayeredImageSingletonSupport;
import com.oracle.svm.shared.singletons.MultiLayeredImageSingleton;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.PartiallyLayerAware;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.MultiLayer;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.SubstrateUtil;

@SingletonTraits(access = AllAccess.class, layeredCallbacks = BuiltinTraits.NoLayeredCallbacks.class, layeredInstallationKind = MultiLayer.class, other = PartiallyLayerAware.class)
public final class SymbolsSupport {
    @Platforms(Platform.HOSTED_ONLY.class) //
    private static final SymbolsSupport TEST_SINGLETON = ImageInfo.inImageCode() ? null : new SymbolsSupport();

    final Symbols symbols;
    final Utf8Symbols utf8;
    final NameSymbols names;
    final TypeSymbols types;
    final SignatureSymbols signatures;

    @Platforms(Platform.HOSTED_ONLY.class)
    public SymbolsSupport() {
        int initialSymbolTableCapacity = 4 * 1024;
        symbols = Symbols.fromExisting(SVMSymbols.SYMBOLS.freeze(), initialSymbolTableCapacity, 0);
        // let this resize when first used at runtime
        utf8 = new Utf8Symbols(symbols);
        names = new NameSymbols(symbols);
        types = new TypeSymbols(symbols);
        signatures = new SignatureSymbols(symbols, types);
    }

    public static TypeSymbols getTypes() {
        return currentLayer().types;
    }

    public static SignatureSymbols getSignatures() {
        return currentLayer().signatures;
    }

    public static NameSymbols getNames() {
        return currentLayer().names;
    }

    public static Utf8Symbols getUtf8() {
        return currentLayer().utf8;
    }

    /*
     * Substituted at runtime to return the topmost layer. This ensures that symbols defined at
     * runtime end up in a single location.
     */
    public static SymbolsSupport currentLayer() {
        if (TEST_SINGLETON != null) {
            /*
             * Some unit tests use com.oracle.svm.interpreter.metadata outside the context of
             * native-image.
             */
            assert !ImageInfo.inImageCode();
            return TEST_SINGLETON;
        }
        return LayeredImageSingletonSupport.singleton().lookup(SymbolsSupport.class, false, true);
    }

    public static SymbolsSupport[] layeredSingletons() {
        return MultiLayeredImageSingleton.getAllLayers(SymbolsSupport.class);
    }
}

/**
 * This substitution class is here to avoid a massive refactoring of the symbols support. The only
 * part of this code that needs to be layer-aware is the Symbols class (and its implementation). We
 * simply need getOrCreate to act on the current layer at build-time, and the lookup and getOrCreate
 * calls to be checking all layered singletons at run-time.
 */
@TargetClass(className = "com.oracle.svm.espresso.classfile.descriptors.SymbolsImpl")
final class Target_com_oracle_svm_espresso_classfile_descriptors_SymbolsImpl {
    @Substitute
    @SuppressWarnings({"static-method"})
    public <T> Symbol<T> lookup(ByteSequence byteSequence) {
        for (var singleton : SymbolsSupport.layeredSingletons()) {
            var symbols = SubstrateUtil.cast(singleton.symbols, Target_com_oracle_svm_espresso_classfile_descriptors_SymbolsImpl.class);
            Symbol<T> symbol = symbols.originalLookup(byteSequence);
            if (symbol != null) {
                return symbol;
            }
        }
        return null;
    }

    @Alias
    @TargetElement(name = "lookup")
    public native <T> Symbol<T> originalLookup(ByteSequence byteSequence);

    @Substitute
    public <T> Symbol<T> getOrCreate(ByteSequence byteSequence, boolean ensureStrongReference) {
        for (var singleton : SymbolsSupport.layeredSingletons()) {
            var symbols = SubstrateUtil.cast(singleton.symbols, Target_com_oracle_svm_espresso_classfile_descriptors_SymbolsImpl.class);
            Symbol<T> symbol = symbols.originalLookup(byteSequence);
            if (symbol != null && !(ensureStrongReference && symbols.isWeak(symbol))) {
                return symbol;
            }
        }
        return originalGetOrCreate(byteSequence, ensureStrongReference);
    }

    @Alias
    @TargetElement(name = "getOrCreate")
    public native <T> Symbol<T> originalGetOrCreate(ByteSequence byteSequence, boolean ensureStrongReference);

    @Alias
    public native boolean isWeak(Symbol<?> symbol);
}

@TargetClass(SymbolsSupport.class)
final class Target_com_oracle_svm_core_hub_registry_SymbolsSupport {
    @Substitute
    public static SymbolsSupport currentLayer() {
        SymbolsSupport[] allLayers = SymbolsSupport.layeredSingletons();
        return allLayers[allLayers.length - 1];
    }
}
