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
package com.oracle.svm.test;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

@NativeImageBuildArgs({
                "-H:+UnlockExperimentalVMOptions",
                "-H:+RuntimeClassLoading",
                "-H:+AllowJRTFileSystem",
                "-H:-UnlockExperimentalVMOptions",
                "--enable-url-protocols=jrt",
                "--add-modules=java.sql,java.xml"
})
public class BootstrapLoaderPlatformModuleTest {

    @BeforeClass
    public static void setJavaHomeForJrtFileSystem() {
        if (System.getProperty("java.home") == null) {
            String javaHome = System.getenv("JAVA_HOME");
            Assert.assertNotNull("JAVA_HOME must be set so the native image can open the runtime jrt:/ file system", javaHome);
            System.setProperty("java.home", javaHome);
        }
    }

    @Test
    public void classForNameWithBootstrapLoaderDoesNotLoadPlatformModuleClass() {
        Assert.assertNotNull("The jrt:/ boot class path needs java.home to be set", System.getProperty("java.home"));
        Assert.assertNotNull(ModuleLayer.boot().findModule("java.sql").orElseThrow().getClassLoader());
        try {
            Class.forName("java.sql.DriverManager", false, null);
            Assert.fail("The bootstrap loader must not load classes from platform-loader modules");
        } catch (ClassNotFoundException expected) {
            // expected
        }
    }

    @Test
    public void classForNameWithBootstrapLoaderCanLoadBootModuleClass() throws ClassNotFoundException {
        Class<?> xmlConstants = Class.forName("javax.xml.XMLConstants", false, null);
        Assert.assertSame(null, xmlConstants.getClassLoader());
    }
}
