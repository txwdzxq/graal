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
package com.oracle.svm.test.jni;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.word.impl.Word;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import com.oracle.svm.core.jni.JNIObjectHandles;
import com.oracle.svm.core.jni.headers.JNIObjectHandle;
import com.oracle.svm.core.jni.headers.JNIObjectRefType;
import com.oracle.svm.test.NativeImageBuildArgs;

@NativeImageBuildArgs({
                "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jni=ALL-UNNAMED",
                "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jni.headers=ALL-UNNAMED",
                "-da:com.oracle.svm.core.jni..."
})
public class JNIGlobalHandlesTest {
    private static final long HANDLE_BITS_MASK = (1L << 31) - 1;
    private static final long VALIDATION_BITS_SHIFT = 31;
    private static final long VALIDATION_BITS_MASK = ((1L << 32) - 1) << VALIDATION_BITS_SHIFT;
    private static final long MSB = 1L << 63;

    @Test
    public void getObjectRefTypeRejectsForeignGlobalHandle() {
        assumeNativeImageRuntime();

        TestObject object = new TestObject("ref-type");
        JNIObjectHandle global = newGlobalRef(object);
        try {
            JNIObjectHandle foreignGlobal = withDifferentOwnerTag(global);
            assertValidGlobalHandle(global, object);
            assertSameSlotDifferentOwnerTag(global, foreignGlobal);

            Assert.assertEquals(JNIObjectRefType.Invalid, getObjectRefType(foreignGlobal));
        } finally {
            JNIObjectHandles.deleteGlobalRef(global);
        }
    }

    @Test
    public void newGlobalRefRejectsForeignGlobalHandle() {
        assumeNativeImageRuntime();

        TestObject object = new TestObject("new-global-ref");
        JNIObjectHandle global = newGlobalRef(object);
        try {
            JNIObjectHandle foreignGlobal = withDifferentOwnerTag(global);
            assertValidGlobalHandle(global, object);
            assertSameSlotDifferentOwnerTag(global, foreignGlobal);

            try {
                JNIObjectHandle duplicate = JNIObjectHandles.newGlobalRef(foreignGlobal);
                if (duplicate.notEqual(JNIObjectHandles.nullHandle())) {
                    JNIObjectHandles.deleteGlobalRef(duplicate);
                }
                Assert.fail("NewGlobalRef must reject a global handle owned by another isolate.");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        } finally {
            JNIObjectHandles.deleteGlobalRef(global);
        }
    }

    @Test
    public void dereferenceRejectsForeignGlobalHandle() {
        assumeNativeImageRuntime();

        TestObject object = new TestObject("dereference");
        JNIObjectHandle global = newGlobalRef(object);
        try {
            JNIObjectHandle foreignGlobal = withDifferentOwnerTag(global);
            assertValidGlobalHandle(global, object);
            assertSameSlotDifferentOwnerTag(global, foreignGlobal);

            try {
                Object resolved = JNIObjectHandles.getObject(foreignGlobal);
                Assert.fail("JNI dereference paths must reject a global handle owned by another isolate: " + resolved);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        } finally {
            JNIObjectHandles.deleteGlobalRef(global);
        }
    }

    private static void assumeNativeImageRuntime() {
        Assume.assumeTrue(ImageInfo.inImageRuntimeCode());
    }

    private static JNIObjectHandle newGlobalRef(Object object) {
        JNIObjectHandle local = JNIObjectHandles.createLocal(object);
        try {
            return JNIObjectHandles.newGlobalRef(local);
        } finally {
            JNIObjectHandles.deleteLocalRef(local);
        }
    }

    private static JNIObjectRefType getObjectRefType(JNIObjectHandle handle) {
        try {
            return JNIObjectHandles.getHandleType(handle);
        } catch (IllegalArgumentException e) {
            return JNIObjectRefType.Invalid;
        }
    }

    private static void assertValidGlobalHandle(JNIObjectHandle handle, Object object) {
        Assert.assertEquals(JNIObjectRefType.Global, getObjectRefType(handle));
        Assert.assertSame(object, JNIObjectHandles.getObject(handle));
    }

    private static void assertSameSlotDifferentOwnerTag(JNIObjectHandle global, JNIObjectHandle foreignGlobal) {
        Assert.assertNotEquals(global.rawValue(), foreignGlobal.rawValue());
        Assert.assertTrue(foreignGlobal.rawValue() < 0);
        Assert.assertEquals(slot(global), slot(foreignGlobal));
        Assert.assertNotEquals(ownerTag(global), ownerTag(foreignGlobal));
    }

    private static JNIObjectHandle withDifferentOwnerTag(JNIObjectHandle handle) {
        long rawValue = handle.rawValue();
        long slot = slot(handle);
        long validationBits = ownerTag(handle);
        long differentValidationBits = validationBits ^ (1L << VALIDATION_BITS_SHIFT);
        return (JNIObjectHandle) Word.pointer(MSB | differentValidationBits | slot);
    }

    private static long slot(JNIObjectHandle handle) {
        return handle.rawValue() & HANDLE_BITS_MASK;
    }

    private static long ownerTag(JNIObjectHandle handle) {
        return handle.rawValue() & VALIDATION_BITS_MASK;
    }

    private record TestObject(String name) {
    }
}
