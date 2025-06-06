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
package com.oracle.svm.core.genscavenge.remset;

import static com.oracle.svm.core.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

import java.util.List;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk.AlignedHeader;
import com.oracle.svm.core.genscavenge.HeapChunk;
import com.oracle.svm.core.genscavenge.HeapParameters;
import com.oracle.svm.core.genscavenge.ObjectHeaderImpl;
import com.oracle.svm.core.genscavenge.SerialGCOptions;
import com.oracle.svm.core.genscavenge.compacting.ObjectMoveInfo;
import com.oracle.svm.core.genscavenge.graal.ForcedSerialPostWriteBarrier;
import com.oracle.svm.core.heap.UninterruptibleObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.image.ImageHeapObject;
import com.oracle.svm.core.util.HostedByteBufferPointer;
import com.oracle.svm.core.util.PointerUtils;
import com.oracle.svm.core.util.UnsignedUtils;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.nodes.memory.address.OffsetAddressNode;
import jdk.graal.compiler.replacements.nodes.AssertionNode;
import jdk.graal.compiler.word.Word;

public final class AlignedChunkRememberedSet {
    private AlignedChunkRememberedSet() {
    }

    @Fold
    public static int wordSize() {
        return ConfigurationValues.getTarget().wordSize;
    }

    @Fold
    static UnsignedWord getHeaderSize() {
        UnsignedWord headerSize = getFirstObjectTableLimitOffset();
        if (SerialGCOptions.useCompactingOldGen()) {
            // Compaction needs room for a ObjectMoveInfo structure before the first object.
            headerSize = headerSize.add(ObjectMoveInfo.getSize());
        }
        UnsignedWord alignment = Word.unsigned(ConfigurationValues.getObjectLayout().getAlignment());
        return UnsignedUtils.roundUp(headerSize, alignment);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    static void enableRememberedSet(HostedByteBufferPointer chunk, int chunkPosition, List<ImageHeapObject> objects) {
        // Completely clean the card table and the first object table.
        CardTable.cleanTable(getCardTableStart(chunk), getCardTableSize());
        FirstObjectTable.initializeTable(getFirstObjectTableStart(chunk), getFirstObjectTableSize());

        Pointer fotStart = getFirstObjectTableStart(chunk);
        UnsignedWord objectsStartOffset = AlignedHeapChunk.getObjectsStartOffset();
        for (ImageHeapObject obj : objects) {
            long offsetWithinChunk = obj.getOffset() - chunkPosition;
            assert offsetWithinChunk > 0 && Word.unsigned(offsetWithinChunk).aboveOrEqual(objectsStartOffset);

            UnsignedWord startOffset = Word.unsigned(offsetWithinChunk).subtract(objectsStartOffset);
            UnsignedWord endOffset = startOffset.add(Word.unsigned(obj.getSize()));
            FirstObjectTable.setTableForObject(fotStart, startOffset, endOffset);
            // The remembered set bit in the header will be set by the code that writes the objects.
        }
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void enableRememberedSetForObject(AlignedHeader chunk, Object obj, UnsignedWord objSize) {
        Pointer fotStart = getFirstObjectTableStart(chunk);
        Pointer objectsStart = AlignedHeapChunk.getObjectsStart(chunk);

        Word objPtr = Word.objectToUntrackedPointer(obj);
        UnsignedWord startOffset = objPtr.subtract(objectsStart);
        UnsignedWord endOffset = startOffset.add(objSize);

        FirstObjectTable.setTableForObject(fotStart, startOffset, endOffset);
        ObjectHeaderImpl.setRememberedSetBit(obj);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void enableRememberedSet(AlignedHeader chunk) {
        // Completely clean the card table and the first object table as further objects may be
        // added later on to this chunk.
        CardTable.cleanTable(getCardTableStart(chunk), getCardTableSize());
        FirstObjectTable.initializeTable(getFirstObjectTableStart(chunk), getFirstObjectTableSize());

        Pointer offset = AlignedHeapChunk.getObjectsStart(chunk);
        Pointer top = HeapChunk.getTopPointer(chunk);
        while (offset.belowThan(top)) {
            Object obj = offset.toObjectNonNull();
            UnsignedWord objSize = LayoutEncoding.getSizeFromObjectInGC(obj);
            enableRememberedSetForObject(chunk, obj, objSize);
            offset = offset.add(objSize);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void clearRememberedSet(AlignedHeader chunk) {
        CardTable.cleanTable(getCardTableStart(chunk), getCardTableSize());
    }

    /**
     * Dirty the card corresponding to the given Object. This has to be fast, because it is used by
     * the post-write barrier.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void dirtyCardForObject(Object object, boolean verifyOnly) {
        Pointer objectPointer = Word.objectToUntrackedPointer(object);
        AlignedHeader chunk = AlignedHeapChunk.getEnclosingChunkFromObjectPointer(objectPointer);
        Pointer cardTableStart = getCardTableStart(chunk);
        UnsignedWord index = getObjectIndex(chunk, objectPointer);
        if (verifyOnly) {
            AssertionNode.assertion(false, CardTable.isDirty(cardTableStart, index), "card must be dirty", "", "", 0L, 0L);
        } else {
            CardTable.setDirty(cardTableStart, index);
        }
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static void dirtyAllReferencesOf(Object obj) {
        ForcedSerialPostWriteBarrier.force(OffsetAddressNode.address(obj, 0), false);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void walkDirtyObjects(AlignedHeader chunk, UninterruptibleObjectVisitor visitor, boolean clean) {
        Pointer objectsStart = AlignedHeapChunk.getObjectsStart(chunk);
        Pointer objectsLimit = HeapChunk.getTopPointer(chunk);
        UnsignedWord memorySize = objectsLimit.subtract(objectsStart);

        Pointer cardTableStart = getCardTableStart(chunk);
        Pointer cardTableLimit = cardTableStart.add(CardTable.tableSizeForMemorySize(memorySize));

        assert cardTableStart.unsignedRemainder(wordSize()).equal(0);
        assert getCardTableSize().unsignedRemainder(wordSize()).equal(0);

        Pointer dirtyHeapStart = objectsLimit;
        Pointer dirtyHeapEnd = objectsLimit;
        Pointer cardPos = cardTableLimit.subtract(1);
        Pointer heapPos = CardTable.cardToHeapAddress(cardTableStart, cardPos, objectsStart);

        while (cardPos.aboveOrEqual(cardTableStart)) {
            if (cardPos.readByte(0) != CardTable.CLEAN_ENTRY) {
                if (clean) {
                    cardPos.writeByte(0, CardTable.CLEAN_ENTRY);
                }
                dirtyHeapStart = heapPos;
            } else {
                /* Hit a clean card, so process the dirty range. */
                if (dirtyHeapStart.belowThan(dirtyHeapEnd)) {
                    walkObjects(chunk, dirtyHeapStart, dirtyHeapEnd, visitor);
                }

                if (PointerUtils.isAMultiple(cardPos, Word.unsigned(wordSize()))) {
                    /* Fast forward through word-aligned continuous range of clean cards. */
                    cardPos = cardPos.subtract(wordSize());
                    while (cardPos.aboveOrEqual(cardTableStart) && ((UnsignedWord) cardPos.readWord(0)).equal(CardTable.CLEAN_WORD)) {
                        cardPos = cardPos.subtract(wordSize());
                    }
                    cardPos = cardPos.add(wordSize());
                    heapPos = CardTable.cardToHeapAddress(cardTableStart, cardPos, objectsStart);
                }

                /* Reset the dirty range. */
                dirtyHeapEnd = heapPos;
                dirtyHeapStart = heapPos;
            }

            cardPos = cardPos.subtract(1);
            heapPos = heapPos.subtract(CardTable.BYTES_COVERED_BY_ENTRY);
        }

        /* Process the remaining dirty range. */
        if (dirtyHeapStart.belowThan(dirtyHeapEnd)) {
            walkObjects(chunk, dirtyHeapStart, dirtyHeapEnd, visitor);
        }
    }

    @Uninterruptible(reason = "Forced inlining (StoredContinuation objects must not move).")
    private static void walkObjects(AlignedHeader chunk, Pointer start, Pointer end, UninterruptibleObjectVisitor visitor) {
        Pointer fotStart = getFirstObjectTableStart(chunk);
        Pointer objectsStart = AlignedHeapChunk.getObjectsStart(chunk);
        UnsignedWord index = CardTable.memoryOffsetToIndex(start.subtract(objectsStart));
        Pointer ptr = FirstObjectTable.getFirstObjectImprecise(fotStart, objectsStart, index);
        while (ptr.belowThan(end)) {
            Object obj = ptr.toObjectNonNull();
            visitor.visitObject(obj);
            ptr = LayoutEncoding.getObjectEndInlineInGC(obj);
        }
    }

    static boolean verify(AlignedHeader chunk) {
        boolean success = true;
        success &= CardTable.verify(getCardTableStart(chunk), getCardTableEnd(chunk), AlignedHeapChunk.getObjectsStart(chunk), HeapChunk.getTopPointer(chunk));
        success &= FirstObjectTable.verify(getFirstObjectTableStart(chunk), AlignedHeapChunk.getObjectsStart(chunk), HeapChunk.getTopPointer(chunk));
        return success;
    }

    static boolean usePreciseCardMarking() {
        return false;
    }

    /** Return the index of an object within the tables of a chunk. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static UnsignedWord getObjectIndex(AlignedHeader chunk, Pointer objectPointer) {
        UnsignedWord offset = AlignedHeapChunk.getObjectOffset(chunk, objectPointer);
        return CardTable.memoryOffsetToIndex(offset);
    }

    @Fold
    static UnsignedWord getStructSize() {
        return Word.unsigned(SizeOf.get(AlignedHeader.class));
    }

    @Fold
    static UnsignedWord getCardTableSize() {
        // We conservatively compute the size as a fraction of the size of the entire chunk.
        UnsignedWord structSize = getStructSize();
        UnsignedWord available = HeapParameters.getAlignedHeapChunkSize().subtract(structSize);
        UnsignedWord requiredSize = CardTable.tableSizeForMemorySize(available);
        UnsignedWord alignment = Word.unsigned(ConfigurationValues.getObjectLayout().getAlignment());
        return UnsignedUtils.roundUp(requiredSize, alignment);
    }

    @Fold
    public static UnsignedWord getFirstObjectTableSize() {
        return getCardTableSize();
    }

    @Fold
    static UnsignedWord getFirstObjectTableStartOffset() {
        UnsignedWord cardTableLimit = getCardTableLimitOffset();
        UnsignedWord alignment = Word.unsigned(ConfigurationValues.getObjectLayout().getAlignment());
        return UnsignedUtils.roundUp(cardTableLimit, alignment);
    }

    @Fold
    static UnsignedWord getFirstObjectTableLimitOffset() {
        UnsignedWord fotStart = getFirstObjectTableStartOffset();
        UnsignedWord fotSize = getFirstObjectTableSize();
        UnsignedWord fotLimit = fotStart.add(fotSize);
        UnsignedWord alignment = Word.unsigned(ConfigurationValues.getObjectLayout().getAlignment());
        return UnsignedUtils.roundUp(fotLimit, alignment);
    }

    @Fold
    static UnsignedWord getCardTableStartOffset() {
        UnsignedWord structSize = getStructSize();
        UnsignedWord alignment = Word.unsigned(ConfigurationValues.getObjectLayout().getAlignment());
        return UnsignedUtils.roundUp(structSize, alignment);
    }

    @Fold
    static UnsignedWord getCardTableLimitOffset() {
        UnsignedWord tableStart = getCardTableStartOffset();
        UnsignedWord tableSize = getCardTableSize();
        UnsignedWord tableLimit = tableStart.add(tableSize);
        UnsignedWord alignment = Word.unsigned(ConfigurationValues.getObjectLayout().getAlignment());
        return UnsignedUtils.roundUp(tableLimit, alignment);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static Pointer getCardTableStart(AlignedHeader chunk) {
        return getCardTableStart(HeapChunk.asPointer(chunk));
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer getCardTableStart(Pointer chunk) {
        return chunk.add(getCardTableStartOffset());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer getCardTableEnd(AlignedHeader chunk) {
        return getCardTableStart(chunk).add(getCardTableSize());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static Pointer getFirstObjectTableStart(AlignedHeader chunk) {
        return getFirstObjectTableStart(HeapChunk.asPointer(chunk));
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer getFirstObjectTableStart(Pointer chunk) {
        return chunk.add(getFirstObjectTableStartOffset());
    }
}
