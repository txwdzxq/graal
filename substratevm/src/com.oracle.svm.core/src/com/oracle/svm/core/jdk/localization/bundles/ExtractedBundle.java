/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.jdk.localization.bundles;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

public final class ExtractedBundle extends AbstractMap<String, Object> implements StoredBundle, ConcurrentMap<String, Object> {
    private static final String[] EMPTY_KEYS = new String[0];
    private static final Object[] EMPTY_VALUES = new Object[0];

    private final String[] keys;
    private final Object[] values;

    public ExtractedBundle(Map<String, Object> lookup) {
        if (lookup.isEmpty()) {
            this.keys = EMPTY_KEYS;
            this.values = EMPTY_VALUES;
        } else {
            String[] copiedKeys = lookup.keySet().toArray(new String[0]);
            Arrays.sort(copiedKeys);

            Object[] copiedValues = new Object[copiedKeys.length];
            for (int i = 0; i < copiedKeys.length; i++) {
                String key = Objects.requireNonNull(copiedKeys[i], "Bundle content keys must not be null");
                copiedValues[i] = Objects.requireNonNull(lookup.get(key), () -> "Bundle content value must not be null for key: " + key);
            }
            this.keys = copiedKeys;
            this.values = copiedValues;
        }
    }

    @Override
    public Map<String, Object> getContent(Object bundle) {
        return this;
    }

    @Override
    public int size() {
        return keys.length;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String stringKey && indexOf(stringKey) >= 0;
    }

    @Override
    public Object get(Object key) {
        if (!(key instanceof String stringKey)) {
            return null;
        }
        int index = indexOf(stringKey);
        return index >= 0 ? values[index] : null;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        Set<Entry<String, Object>> entries = new LinkedHashSet<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            entries.add(Map.entry(keys[i], values[i]));
        }
        return Collections.unmodifiableSet(entries);
    }

    @Override
    public Set<String> keySet() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(keys)));
    }

    @Override
    public Collection<Object> values() {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    @Override
    public Object putIfAbsent(String key, Object value) {
        throw immutable();
    }

    @Override
    public boolean remove(Object key, Object value) {
        throw immutable();
    }

    @Override
    public boolean replace(String key, Object oldValue, Object newValue) {
        throw immutable();
    }

    @Override
    public Object replace(String key, Object value) {
        throw immutable();
    }

    private int indexOf(String key) {
        return Arrays.binarySearch(keys, key);
    }

    private static UnsupportedOperationException immutable() {
        return new UnsupportedOperationException("ExtractedBundle is immutable");
    }
}
