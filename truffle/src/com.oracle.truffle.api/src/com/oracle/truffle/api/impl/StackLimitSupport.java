/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.impl;

import com.oracle.truffle.api.InternalResource;
import com.sun.management.HotSpotDiagnosticMXBean;
import org.graalvm.nativeimage.ImageInfo;
import sun.misc.Unsafe;

import javax.management.MBeanServer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;

final class StackLimitSupport {

    private StackLimitSupport() {
    }

    static long getStackOverflowLimit() {
        if (ImageInfo.inImageCode()) {
            // TODO: On native image we can use intrinsic
            throw new UnsupportedOperationException();
        }

        long platformStackEnd = getPlatformStackEnd0();
        if (platformStackEnd == 0L) {
            throw new UnsupportedOperationException("Unable to determine platform stack end for the current thread.");
        }
        HotSpotStackConfig config = HotSpotStackConfig.INSTANCE;
        long red = alignUp(config.redZoneSize(), config.pageSize());
        long yellow = alignUp(config.yellowZoneSize(), config.pageSize());
        long reserved = alignUp(config.reservedZoneSize(), config.pageSize());
        long shadow = alignUp(config.shadowZoneSize(), config.pageSize());
        long guardZone = red + yellow + reserved;
        return platformStackEnd + config.transitionSafetyMargin() + Math.max(guardZone, shadow);
    }

    private static long alignUp(long x, long a) {
        return ((x + a - 1) / a) * a;
    }

    private record HotSpotStackConfig(long redZoneSize, long yellowZoneSize, long reservedZoneSize,
                    long shadowZoneSize, long transitionSafetyMargin, long pageSize) {

        HotSpotStackConfig {
            assert transitionSafetyMargin % pageSize == 0 : "transitionSafetyMargin must be a multiple of pageSize";
        }

        private static final HotSpotStackConfig INSTANCE = init();

        private static HotSpotStackConfig init() {
            if (ImageInfo.inImageCode()) {
                return null;
            }
            long pageSize = getUnsafe().pageSize();
            MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
            try {
                HotSpotDiagnosticMXBean bean = ManagementFactory.newPlatformMXBeanProxy(platformMBeanServer, "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
                return new HotSpotStackConfig(
                                // uses 4KB units
                                Long.parseLong(bean.getVMOption("StackRedPages").getValue()) * 4096L,
                                // uses 4KB units
                                Long.parseLong(bean.getVMOption("StackYellowPages").getValue()) * 4096L,
                                // uses 4KB units
                                Long.parseLong(bean.getVMOption("StackReservedPages").getValue()) * 4096L,
                                // uses 4KB units
                                Long.parseLong(bean.getVMOption("StackShadowPages").getValue()) * 4096L,
                                // always add a glibc guard page which is not included in stack
                                // bottom on some glibc versions
                                InternalResource.OS.getCurrent() == InternalResource.OS.LINUX ? pageSize : 0L,
                                // OS page size in bytes
                                pageSize);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }

        private static Unsafe getUnsafe() {
            try {
                // Fast path when we are trusted.
                return Unsafe.getUnsafe();
            } catch (SecurityException se) {
                // Slow path when we are not trusted.
                try {
                    Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
                    theUnsafe.setAccessible(true);
                    return (Unsafe) theUnsafe.get(Unsafe.class);
                } catch (Exception e) {
                    throw new RuntimeException("exception while trying to get Unsafe", e);
                }
            }
        }
    }

    private static native long getPlatformStackEnd0();
}
