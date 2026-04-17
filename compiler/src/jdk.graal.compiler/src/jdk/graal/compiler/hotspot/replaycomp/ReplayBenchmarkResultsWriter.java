/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot.replaycomp;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import jdk.graal.compiler.util.json.JsonBuilder;
import jdk.graal.compiler.util.json.JsonWriter;

/**
 * Writes replay benchmark results as a JSON array.
 */
public final class ReplayBenchmarkResultsWriter implements AutoCloseable {
    public record CompilationRecord(int iteration, int compileId, String methodName, int entryBci,
                    long wallTimeNanos, long threadTimeNanos, long allocatedMemory, int compiledBytecodes, int targetCodeSize, int targetCodeHash,
                    Map<String, Long> eventValues) {
    }

    public record IterationTotalRecord(int iteration,
                    long wallTimeNanos, long threadTimeNanos, long allocatedMemory, int compiledBytecodes, int targetCodeSize, int targetCodeHash,
                    Map<String, Long> eventValues) {
    }

    private final JsonWriter writer;

    private final JsonBuilder.ArrayBuilder records;

    private final List<String> eventNames;

    public ReplayBenchmarkResultsWriter(Path path, List<String> eventNames) throws IOException {
        this(new JsonWriter(path), eventNames);
    }

    public ReplayBenchmarkResultsWriter(Writer writer, List<String> eventNames) throws IOException {
        this(new JsonWriter(writer), eventNames);
    }

    private ReplayBenchmarkResultsWriter(JsonWriter writer, List<String> eventNames) throws IOException {
        this.writer = writer;
        this.records = writer.arrayBuilder();
        this.eventNames = List.copyOf(eventNames);
    }

    public void writeCompilation(CompilationRecord record) throws IOException {
        try (var object = records.nextEntry().object()) {
            object.append("type", "compilation");
            object.append("iteration", record.iteration());
            object.append("compile_id", record.compileId());
            object.append("method_name", record.methodName());
            object.append("entry_bci", record.entryBci());
            appendMetrics(object, record.wallTimeNanos(), record.threadTimeNanos(), record.allocatedMemory(), record.compiledBytecodes(), record.targetCodeSize(), record.targetCodeHash());
            appendEvents(object, record.eventValues());
        }
    }

    public void writeIterationTotal(IterationTotalRecord record) throws IOException {
        try (var object = records.nextEntry().object()) {
            object.append("type", "iteration_total");
            object.append("iteration", record.iteration());
            appendMetrics(object, record.wallTimeNanos(), record.threadTimeNanos(), record.allocatedMemory(), record.compiledBytecodes(), record.targetCodeSize(), record.targetCodeHash());
            appendEvents(object, record.eventValues());
        }
    }

    private static void appendMetrics(JsonBuilder.ObjectBuilder object,
                    long wallTimeNanos, long threadTimeNanos, long allocatedMemory, int compiledBytecodes, int targetCodeSize, int targetCodeHash) throws IOException {
        object.append("wall_time_ns", wallTimeNanos);
        object.append("thread_time_ns", threadTimeNanos);
        object.append("allocated_memory", allocatedMemory);
        object.append("compiled_bytecodes", compiledBytecodes);
        object.append("target_code_size", targetCodeSize);
        object.append("target_code_hash", String.format("%08x", targetCodeHash));
    }

    private void appendEvents(JsonBuilder.ObjectBuilder object, Map<String, Long> eventValues) throws IOException {
        if (eventNames.isEmpty()) {
            return;
        }
        try (var events = object.append("events").object()) {
            for (String eventName : eventNames) {
                events.append(eventName, eventValues.get(eventName));
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            records.close();
        } finally {
            writer.close();
        }
    }
}
