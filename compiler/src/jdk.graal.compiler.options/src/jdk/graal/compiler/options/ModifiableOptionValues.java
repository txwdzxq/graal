/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.options;

import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Equivalence;
import org.graalvm.collections.UnmodifiableEconomicMap;
import org.graalvm.collections.UnmodifiableMapCursor;

/**
 * A context for obtaining values for {@link OptionKey}s that allows for key/value pairs to be
 * updated. Updates have atomic copy-on-write semantics which means a thread may see an old value
 * when reading but writers will never lose updates.
 */
public class ModifiableOptionValues extends OptionValues {

    private final AtomicReference<UnmodifiableEconomicMap<OptionKey<?>, Object>> v = new AtomicReference<>();

    private static final EconomicMap<OptionKey<?>, Object> EMPTY_MAP = newOptionMap();

    public ModifiableOptionValues(UnmodifiableEconomicMap<OptionKey<?>, Object> values) {
        super(EMPTY_MAP);
        EconomicMap<OptionKey<?>, Object> map = newOptionMap();
        initMap(map, values);
        v.set(map);
    }

    /**
     * Updates this object with the given key/value pair.
     */
    public void update(OptionKey<?> key, Object value) {
        UnmodifiableEconomicMap<OptionKey<?>, Object> expect;
        EconomicMap<OptionKey<?>, Object> newMap;
        do {
            expect = v.get();
            newMap = EconomicMap.create(Equivalence.IDENTITY, expect);
            key.notifySet();
            key.update(newMap, value);
            newMap.put(key, value);
        } while (!v.compareAndSet(expect, newMap));

        key.afterValueUpdate();
    }

    /**
     * Updates this object with the key/value pairs in {@code values}.
     */
    public void update(UnmodifiableEconomicMap<OptionKey<?>, Object> values) {
        if (values.isEmpty()) {
            return;
        }
        UnmodifiableEconomicMap<OptionKey<?>, Object> expect;
        EconomicMap<OptionKey<?>, Object> newMap;
        do {
            expect = v.get();
            newMap = EconomicMap.create(Equivalence.IDENTITY, expect);
            UnmodifiableMapCursor<OptionKey<?>, Object> cursor = values.getEntries();
            while (cursor.advance()) {
                OptionKey<?> key = cursor.getKey();
                Object value = cursor.getValue();
                key.notifySet();
                key.update(newMap, value);
                newMap.put(key, value);
            }
        } while (!v.compareAndSet(expect, newMap));

        UnmodifiableMapCursor<OptionKey<?>, Object> cursor = values.getEntries();
        while (cursor.advance()) {
            OptionKey<?> key = cursor.getKey();
            key.afterValueUpdate();
        }
    }

    @Override
    protected boolean containsKey(OptionKey<?> key) {
        return v.get().containsKey(key);
    }

    @Override
    public UnmodifiableEconomicMap<OptionKey<?>, Object> getMap() {
        return v.get();
    }
}
