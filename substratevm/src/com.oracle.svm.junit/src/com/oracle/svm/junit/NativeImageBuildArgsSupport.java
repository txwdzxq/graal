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
package com.oracle.svm.junit;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.junit.runners.Suite;

/**
 * Computes the effective {@code @NativeImageBuildArgs} for the selected JUnit tests without
 * initializing the classes and groups tests by that effective argument list.
 *
 * <p>For each selected test, this helper:
 * <ul>
 * <li>loads the selected test class without running class initialization,</li>
 * <li>expands {@code @SuiteClasses} transitively,</li>
 * <li>collects {@code @NativeImageBuildArgs} from the class and its superclasses, preserving the
 * subclass-to-superclass order and removing duplicates, and</li>
 * <li>groups tests that end up with the same effective build-arg list.</li>
 * </ul>
 *
 * <p>{@code mx native-unittest} then consumes the grouped manifest and decides which groups should
 * produce separate images for the current invocation.
 */
public final class NativeImageBuildArgsSupport {
    /*
     * JUNIT_SUPPORT is shared across native-unittest configurations, so this helper avoids a direct
     * compile-time dependency on com.oracle.svm.test.NativeImageBuildArgs and reads the annotation
     * reflectively by name instead.
     */
    private static final String NATIVE_IMAGE_BUILD_ARGS_ANNOTATION = "com.oracle.svm.test.NativeImageBuildArgs";
    private static final String OUTPUT_SEPARATOR = "\t";

    private NativeImageBuildArgsSupport() {
    }

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        if (args.length != 1 && args.length != 2) {
            throw new IllegalArgumentException("Expected the selected test classes file and an optional manifest output file.");
        }
        Path testClassesFile = Path.of(args[0]);
        List<TestBuildArgs> testsWithBuildArgs = collectBuildArgsByTest(Files.readAllLines(testClassesFile), Thread.currentThread().getContextClassLoader());
        if (args.length == 1) {
            for (TestBuildArgs testBuildArgs : testsWithBuildArgs) {
                System.out.println(testBuildArgs.toOutputLine());
            }
        } else {
            writeGroupedManifest(Path.of(args[1]), groupTestsByBuildArgs(testsWithBuildArgs));
        }
    }

    /**
     * Returns one entry per selected test with the fully expanded effective build-arg list for
     * that test.
     */
    static List<TestBuildArgs> collectBuildArgsByTest(List<String> selectedTests, ClassLoader classLoader) throws ClassNotFoundException {
        List<TestBuildArgs> testsWithBuildArgs = new ArrayList<>();
        for (String selectedTest : selectedTests) {
            if (!selectedTest.isBlank()) {
                testsWithBuildArgs.add(new TestBuildArgs(selectedTest, collectBuildArgs(selectedTest, classLoader)));
            }
        }
        return testsWithBuildArgs;
    }

    /**
     * Collects the effective build args for {@code selectedTest}.
     *
     * <p>The selected class is the starting point. If it is a JUnit suite, its
     * {@code @SuiteClasses} members are visited too. For every visited class, build args from
     * the class hierarchy are appended from subclass to superclass, and duplicates are removed
     * while preserving that first-seen order.
     */
    private static List<String> collectBuildArgs(String selectedTest, ClassLoader classLoader) throws ClassNotFoundException {
        LinkedHashSet<String> buildArgs = new LinkedHashSet<>();
        LinkedHashSet<Class<?>> visitedClasses = new LinkedHashSet<>();
        Deque<Class<?>> pendingClasses = new ArrayDeque<>();
        pendingClasses.add(loadSelectedTestClass(selectedTest, classLoader));
        while (!pendingClasses.isEmpty()) {
            Class<?> clazz = pendingClasses.removeFirst();
            if (!visitedClasses.add(clazz)) {
                continue;
            }
            addSuiteClasses(clazz, pendingClasses, classLoader);
            for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
                for (String buildArg : getNativeImageBuildArgs(current)) {
                    buildArgs.add(buildArg);
                }
            }
        }
        return new ArrayList<>(buildArgs);
    }

    private static Class<?> loadSelectedTestClass(String selectedTest, ClassLoader classLoader) throws ClassNotFoundException {
        String className = selectedTest;
        int methodSeparatorIndex = selectedTest.indexOf('#');
        if (methodSeparatorIndex != -1) {
            className = selectedTest.substring(0, methodSeparatorIndex);
        }
        return Class.forName(className, false, classLoader);
    }

    private static void addSuiteClasses(Class<?> clazz, Deque<Class<?>> pendingClasses, ClassLoader classLoader) throws ClassNotFoundException {
        // Checkstyle: allow direct annotation access
        Suite.SuiteClasses suiteClasses = clazz.getDeclaredAnnotation(Suite.SuiteClasses.class);
        // Checkstyle: disallow direct annotation access
        if (suiteClasses != null) {
            for (Class<?> suiteClass : suiteClasses.value()) {
                pendingClasses.add(Class.forName(suiteClass.getName(), false, classLoader));
            }
        }
    }

    private static List<String> getNativeImageBuildArgs(Class<?> clazz) {
        List<String> buildArgs = new ArrayList<>();
        // Checkstyle: allow direct annotation access
        for (Annotation annotation : clazz.getAnnotations()) {
            if (annotation.annotationType().getName().equals(NATIVE_IMAGE_BUILD_ARGS_ANNOTATION)) {
                Optional<String[]> value = getStringArrayElement("value", annotation);
                if (value.isPresent()) {
                    for (String buildArg : value.get()) {
                        if (!buildArg.isBlank()) {
                            buildArgs.add(buildArg);
                        }
                    }
                }
            }
        }
        // Checkstyle: disallow direct annotation access
        return buildArgs;
    }

    private static Optional<String[]> getStringArrayElement(String elementName, Annotation annotation) {
        try {
            Method elementMethod = annotation.annotationType().getMethod(elementName);
            Object value = elementMethod.invoke(annotation);
            if (!value.getClass().isArray() || value.getClass().getComponentType() != String.class) {
                return Optional.empty();
            }
            String[] result = new String[Array.getLength(value)];
            for (int i = 0; i < result.length; i++) {
                result[i] = (String) Array.get(value, i);
            }
            return Optional.of(result);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Failed to read @" + annotation.annotationType().getName() + "." + elementName, e);
        }
    }

    /**
     * Groups tests by their effective build-arg list. Groups preserve first encounter order from
     * the selected test list so the Python side can iterate them deterministically.
     */
    private static List<TestBuildArgsGroup> groupTestsByBuildArgs(List<TestBuildArgs> testsWithBuildArgs) {
        LinkedHashMap<List<String>, List<String>> groupedTests = new LinkedHashMap<>();
        for (TestBuildArgs testBuildArgs : testsWithBuildArgs) {
            List<String> buildArgs = List.copyOf(testBuildArgs.buildArgs);
            groupedTests.computeIfAbsent(buildArgs, _ -> new ArrayList<>()).add(testBuildArgs.selectedTest);
        }
        List<TestBuildArgsGroup> result = new ArrayList<>(groupedTests.size());
        groupedTests.forEach((buildArgs, selectedTests) -> result.add(new TestBuildArgsGroup(buildArgs, selectedTests)));
        return result;
    }

    /**
     * Writes the grouped test/build-args manifest as JSON.
     *
     * <p>This helper is packaged in {@code JUNIT_SUPPORT}, which is shared across native-unittest
     * configurations. The manifest format is intentionally simple, so keep the writer local instead
     * of adding a JSON library dependency just for this handoff file.
     */
    private static void writeGroupedManifest(Path manifestFile, List<TestBuildArgsGroup> groups) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("[\n");
        for (int i = 0; i < groups.size(); i++) {
            if (i != 0) {
                json.append(",\n");
            }
            groups.get(i).appendJson(json, "  ");
        }
        json.append("\n]\n");
        Files.writeString(manifestFile, json.toString());
    }

    private static void appendJsonArray(StringBuilder json, List<String> values) {
        json.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i != 0) {
                json.append(", ");
            }
            appendJsonString(json, values.get(i));
        }
        json.append(']');
    }

    /**
     * Escapes exactly the characters that must be escaped in a JSON string, plus any remaining
     * ASCII control characters.
     */
    private static void appendJsonString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> json.append("\\\\");
                case '"' -> json.append("\\\"");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        json.append(String.format("\\u%04x", (int) ch));
                    } else {
                        json.append(ch);
                    }
                }
            }
        }
        json.append('"');
    }

    static final class TestBuildArgs {
        private final String selectedTest;
        private final List<String> buildArgs;

        private TestBuildArgs(String selectedTest, List<String> buildArgs) {
            this.selectedTest = selectedTest;
            this.buildArgs = buildArgs;
        }

        private String toOutputLine() {
            /* One line per selected test: test-id TAB arg TAB arg ... */
            StringBuilder outputLine = new StringBuilder(selectedTest);
            for (String buildArg : buildArgs) {
                outputLine.append(OUTPUT_SEPARATOR).append(buildArg);
            }
            return outputLine.toString();
        }
    }

    static final class TestBuildArgsGroup {
        private final List<String> buildArgs;
        private final List<String> selectedTests;

        private TestBuildArgsGroup(List<String> buildArgs, List<String> selectedTests) {
            this.buildArgs = List.copyOf(buildArgs);
            this.selectedTests = List.copyOf(selectedTests);
        }

        private void appendJson(StringBuilder json, String indent) {
            json.append(indent).append("{\"buildArgs\": ");
            appendJsonArray(json, buildArgs);
            json.append(", \"tests\": ");
            appendJsonArray(json, selectedTests);
            json.append('}');
        }
    }
}
