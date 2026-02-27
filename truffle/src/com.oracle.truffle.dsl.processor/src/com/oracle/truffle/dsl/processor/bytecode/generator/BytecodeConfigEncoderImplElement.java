/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.dsl.processor.bytecode.generator;

import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;

import java.util.Set;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

import com.oracle.truffle.dsl.processor.SuppressFBWarnings;
import com.oracle.truffle.dsl.processor.bytecode.model.CustomOperationModel;
import com.oracle.truffle.dsl.processor.generator.GeneratorUtils;
import com.oracle.truffle.dsl.processor.java.ElementUtils;
import com.oracle.truffle.dsl.processor.java.model.CodeExecutableElement;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.java.model.CodeVariableElement;

final class BytecodeConfigEncoderImplElement extends AbstractElement {

    BytecodeConfigEncoderImplElement(BytecodeRootNodeElement parent) {
        super(parent, Set.of(PRIVATE, STATIC, FINAL), ElementKind.CLASS, null, "BytecodeConfigEncoderImpl");
        this.setSuperClass(types.BytecodeConfigEncoder);

        CodeExecutableElement constructor = this.add(new CodeExecutableElement(Set.of(), null, this.getSimpleName().toString()));
        CodeTreeBuilder b = constructor.createBuilder();
        b.startStatement().startSuperCall().staticReference(parent.bytecodeRootNodesImpl.asType(), "VISIBLE_TOKEN").end().end();

        CodeVariableElement configEncoderVar = this.add(new CodeVariableElement(Set.of(PRIVATE, STATIC, FINAL), this.asType(), "INSTANCE"));
        configEncoderVar.createInitBuilder().startNew(this.asType()).end();

        this.add(createEncodeInstrumentation());
        this.add(createDecode1());
        this.add(createDecode2());

        CodeExecutableElement encodeTag = GeneratorUtils.override(types.BytecodeConfigEncoder, "encodeTag", new String[]{"c"});
        b = encodeTag.createBuilder();

        if (parent.model.getProvidedTags().isEmpty()) {
            parent.createFailInvalidTag(b, "c");
        } else {
            b.startReturn().string("((long) CLASS_TO_TAG_MASK.get(c)) << " + BytecodeRootNodeElement.TAG_OFFSET).end().build();
        }

        this.add(encodeTag);

    }

    private CodeExecutableElement createDecode1() {
        CodeExecutableElement ex = new CodeExecutableElement(Set.of(PRIVATE, Modifier.STATIC), type(long.class), "decode");
        ex.addParameter(new CodeVariableElement(types.BytecodeConfig, "config"));
        CodeTreeBuilder b = ex.createBuilder();
        b.startReturn();
        b.startCall("decode").string("getEncoder(config)").string("getEncoding(config)").end();
        b.end();
        return ex;
    }

    @SuppressFBWarnings(value = "BSHIFT_WRONG_ADD_PRIORITY", justification = "the shift priority is expected. FindBugs false positive.")
    private CodeExecutableElement createDecode2() {
        CodeExecutableElement ex = new CodeExecutableElement(Set.of(PRIVATE, Modifier.STATIC), type(long.class), "decode");
        ex.addParameter(new CodeVariableElement(types.BytecodeConfigEncoder, "encoder"));
        ex.addParameter(new CodeVariableElement(type(long.class), "encoding"));
        CodeTreeBuilder b = ex.createBuilder();

        b.startIf().string("encoder != null && encoder != ").staticReference(this.asType(), "INSTANCE").end().startBlock();
        b.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
        b.startThrow().startNew(type(IllegalArgumentException.class)).doubleQuote("Encoded config is not compatible with this bytecode node.").end().end();
        b.end();

        long mask = 1L;
        if (parent.model.getInstrumentationsCount() > BytecodeRootNodeElement.MAX_INSTRUMENTATIONS) {
            throw new AssertionError("Unsupported instrumentation size.");
        }
        if (parent.model.getProvidedTags().size() > BytecodeRootNodeElement.MAX_TAGS) {
            throw new AssertionError("Unsupported instrumentation size.");
        }

        if (parent.model.traceInstructionInstrumentationIndex != -1) {
            mask |= 1L << (BytecodeRootNodeElement.INSTRUMENTATION_OFFSET + parent.model.traceInstructionInstrumentationIndex);
        }

        for (int i = 0; i < parent.model.getInstrumentations().size(); i++) {
            mask |= 1L << (BytecodeRootNodeElement.INSTRUMENTATION_OFFSET + i);
        }

        for (int i = 0; i < parent.model.getProvidedTags().size(); i++) {
            mask |= 1L << (BytecodeRootNodeElement.TAG_OFFSET + i);
        }

        b.startReturn().string("(encoding & 0x" + Long.toHexString(mask) + "L)").end();
        return ex;
    }

    private CodeExecutableElement createEncodeInstrumentation() {
        CodeExecutableElement encodeInstrumentation = GeneratorUtils.override(types.BytecodeConfigEncoder, "encodeInstrumentation", new String[]{"c"});
        CodeTreeBuilder b = encodeInstrumentation.createBuilder();

        if (parent.model.hasInstrumentations()) {
            b.declaration("long", "encoding", "0L");
            boolean elseIf = b.startIf(false);
            b.string("c == ").typeLiteral(types.InstructionTracer);
            b.end().startBlock();
            if (parent.model.enableInstructionTracing) {
                b.statement("encoding |= 0x" + Integer.toHexString(1 << parent.model.traceInstructionInstrumentationIndex));
            } else {
                b.lineComment("Instruction tracing disabled");
            }
            b.end();
            for (CustomOperationModel customOperation : parent.model.getInstrumentations()) {
                elseIf = b.startIf(elseIf);
                b.string("c == ").typeLiteral(customOperation.operation.instruction.nodeType.asType());
                b.end().startBlock();
                b.statement("encoding |= 0x" + Integer.toHexString(1 << customOperation.operation.instrumentationIndex));
                b.end();
            }
            b.startElseBlock();
        }
        b.startThrow().startNew(type(IllegalArgumentException.class)).startCall("String.format").doubleQuote(
                        "Invalid instrumentation specified. Instrumentation '%s' does not exist or is not an instrumentation for '" + ElementUtils.getQualifiedName(parent.model.templateType) + "'. " +
                                        "Instrumentations can be specified using the @Instrumentation annotation.").string("c.getName()").end().end().end();
        if (parent.model.hasInstrumentations()) {
            b.end(); // else
            b.startReturn().string("encoding << 1").end();
        }
        return encodeInstrumentation;
    }

}
