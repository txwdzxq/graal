/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.genscavenge;

import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.VERY_SLOW_PATH_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.probability;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.genscavenge.GCImpl.ChunkReleaser;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.graal.snippets.SubstrateAllocationSnippets;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.identityhashcode.IdentityHashCodeSupport;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.thread.VMThreads;

import jdk.graal.compiler.word.ObjectAccess;
import jdk.graal.compiler.word.Word;

/**
 * A Space is a collection of HeapChunks.
 *
 * Each Space keeps two collections: one of {@link AlignedHeapChunk} and one of
 * {@link UnalignedHeapChunk}.
 */
public final class Space {
    private final String name;
    private final String shortName;
    private final boolean isToSpace;
    private final int age;
    private final ChunksAccounting accounting;

    /* Heads and tails of the HeapChunk lists. */
    private AlignedHeapChunk.AlignedHeader firstAlignedHeapChunk;
    private AlignedHeapChunk.AlignedHeader lastAlignedHeapChunk;
    private UnalignedHeapChunk.UnalignedHeader firstUnalignedHeapChunk;
    private UnalignedHeapChunk.UnalignedHeader lastUnalignedHeapChunk;

    /**
     * Space creation is HOSTED_ONLY because all Spaces must be constructed during native image
     * generation so they end up in the native image heap because they need to be accessed during
     * collections so they should not move.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    Space(String name, String shortName, boolean isToSpace, int age) {
        this(name, shortName, isToSpace, age, null);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    Space(String name, String shortName, boolean isToSpace, int age, ChunksAccounting parentAccounting) {
        assert name != null : "Space name should not be null.";
        this.name = name;
        this.shortName = shortName;
        this.isToSpace = isToSpace;
        this.age = age;
        this.accounting = new ChunksAccounting(parentAccounting);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public String getName() {
        return name;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public String getShortName() {
        return shortName;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isEmpty() {
        return (getFirstAlignedHeapChunk().isNull() && getFirstUnalignedHeapChunk().isNull());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void tearDown() {
        HeapChunkProvider.freeAlignedChunkList(getFirstAlignedHeapChunk());
        HeapChunkProvider.freeUnalignedChunkList(getFirstUnalignedHeapChunk());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    boolean isEdenSpace() {
        return age == 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isYoungSpace() {
        return age <= HeapParameters.getMaxSurvivorSpaces();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    boolean isSurvivorSpace() {
        return age > 0 && age <= HeapParameters.getMaxSurvivorSpaces();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isOldSpace() {
        return age == (HeapParameters.getMaxSurvivorSpaces() + 1);
    }

    @AlwaysInline("GC performance.")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isCompactingOldSpace() {
        return SerialGCOptions.useCompactingOldGen() && isOldSpace();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    int getAge() {
        return age;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    int getNextAgeForPromotion() {
        return age + 1;
    }

    @AlwaysInline("GC performance.")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    boolean isFromSpace() {
        return !isToSpace && !isCompactingOldSpace();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    boolean isToSpace() {
        return isToSpace;
    }

    public void walkObjects(ObjectVisitor visitor) {
        AlignedHeapChunk.AlignedHeader aChunk = getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            AlignedHeapChunk.walkObjects(aChunk, visitor);
            aChunk = HeapChunk.getNext(aChunk);
        }
        UnalignedHeapChunk.UnalignedHeader uChunk = getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            UnalignedHeapChunk.walkObjects(uChunk, visitor);
            uChunk = HeapChunk.getNext(uChunk);
        }
    }

    void walkAlignedHeapChunks(AlignedHeapChunk.Visitor visitor) {
        AlignedHeapChunk.AlignedHeader chunk = getFirstAlignedHeapChunk();
        while (chunk.isNonNull()) {
            visitor.visitChunk(chunk);
            chunk = HeapChunk.getNext(chunk);
        }
    }

    public void logUsage(Log log, boolean logIfEmpty) {
        UnsignedWord chunkBytes;
        if (isEdenSpace() && !VMOperation.isGCInProgress()) {
            chunkBytes = HeapImpl.getAccounting().getEdenUsedBytes();
        } else {
            chunkBytes = getChunkBytes();
        }

        if (logIfEmpty || chunkBytes.aboveThan(0)) {
            log.string(getName()).string(": ").rational(chunkBytes, GCImpl.M, 2).string("M (")
                            .rational(accounting.getAlignedChunkBytes(), GCImpl.M, 2).string("M in ").signed(accounting.getAlignedChunkCount()).string(" aligned chunks, ")
                            .rational(accounting.getUnalignedChunkBytes(), GCImpl.M, 2).string("M in ").signed(accounting.getUnalignedChunkCount()).string(" unaligned chunks)").newline();
        }
    }

    public void logChunks(Log log) {
        HeapChunkLogging.logChunks(log, getFirstAlignedHeapChunk(), shortName, isToSpace());
        HeapChunkLogging.logChunks(log, getFirstUnalignedHeapChunk(), shortName, isToSpace());
    }

    /**
     * Allocate memory from an AlignedHeapChunk in this Space.
     */
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private Pointer allocateMemory(UnsignedWord objectSize) {
        Pointer result = Word.nullPointer();
        /* Fast-path: try allocating in the last chunk. */
        AlignedHeapChunk.AlignedHeader oldChunk = getLastAlignedHeapChunk();
        if (oldChunk.isNonNull()) {
            result = AlignedHeapChunk.allocateMemory(oldChunk, objectSize);
        }
        if (result.isNonNull()) {
            return result;
        }
        /* Slow-path: try allocating a new chunk for the requested memory. */
        return allocateInNewChunk(objectSize);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private Pointer allocateInNewChunk(UnsignedWord objectSize) {
        AlignedHeapChunk.AlignedHeader newChunk = requestAlignedHeapChunk();
        if (newChunk.isNonNull()) {
            return AlignedHeapChunk.allocateMemory(newChunk, objectSize);
        }
        return Word.nullPointer();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void releaseChunks(ChunkReleaser chunkReleaser) {
        chunkReleaser.add(firstAlignedHeapChunk);
        chunkReleaser.add(firstUnalignedHeapChunk);

        firstAlignedHeapChunk = Word.nullPointer();
        lastAlignedHeapChunk = Word.nullPointer();
        firstUnalignedHeapChunk = Word.nullPointer();
        lastUnalignedHeapChunk = Word.nullPointer();
        accounting.reset();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void appendAlignedHeapChunk(AlignedHeapChunk.AlignedHeader aChunk) {
        /*
         * This method is used while detaching a thread, so it cannot guarantee that it is inside a
         * VMOperation, only that there is some mutual exclusion.
         */
        VMThreads.guaranteeOwnsThreadMutex("Trying to append an aligned heap chunk but no mutual exclusion.", true);
        appendAlignedHeapChunkUninterruptibly(aChunk);
        accounting.noteAlignedHeapChunk();
    }

    @Uninterruptible(reason = "Must not interact with garbage collections.")
    private void appendAlignedHeapChunkUninterruptibly(AlignedHeapChunk.AlignedHeader aChunk) {
        AlignedHeapChunk.AlignedHeader oldLast = getLastAlignedHeapChunk();
        HeapChunk.setSpace(aChunk, this);
        HeapChunk.setPrevious(aChunk, oldLast);
        HeapChunk.setNext(aChunk, Word.nullPointer());
        if (oldLast.isNonNull()) {
            HeapChunk.setNext(oldLast, aChunk);
        }
        setLastAlignedHeapChunk(aChunk);
        if (getFirstAlignedHeapChunk().isNull()) {
            setFirstAlignedHeapChunk(aChunk);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void extractAlignedHeapChunk(AlignedHeapChunk.AlignedHeader aChunk) {
        assert VMOperation.isGCInProgress() : "Should only be called by the collector.";
        extractAlignedHeapChunkUninterruptibly(aChunk);
        accounting.unnoteAlignedHeapChunk();
    }

    @Uninterruptible(reason = "Must not interact with garbage collections.")
    private void extractAlignedHeapChunkUninterruptibly(AlignedHeapChunk.AlignedHeader aChunk) {
        AlignedHeapChunk.AlignedHeader chunkNext = HeapChunk.getNext(aChunk);
        AlignedHeapChunk.AlignedHeader chunkPrev = HeapChunk.getPrevious(aChunk);
        if (chunkPrev.isNonNull()) {
            HeapChunk.setNext(chunkPrev, chunkNext);
        } else {
            setFirstAlignedHeapChunk(chunkNext);
        }
        if (chunkNext.isNonNull()) {
            HeapChunk.setPrevious(chunkNext, chunkPrev);
        } else {
            setLastAlignedHeapChunk(chunkPrev);
        }
        HeapChunk.setNext(aChunk, Word.nullPointer());
        HeapChunk.setPrevious(aChunk, Word.nullPointer());
        HeapChunk.setSpace(aChunk, null);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void appendUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader uChunk) {
        /*
         * This method is used while detaching a thread, so it cannot guarantee that it is inside a
         * VMOperation, only that there is some mutual exclusion.
         */
        VMThreads.guaranteeOwnsThreadMutex("Trying to append an unaligned chunk but no mutual exclusion.", true);
        appendUnalignedHeapChunkUninterruptibly(uChunk);
        accounting.noteUnalignedHeapChunk(uChunk);
    }

    @Uninterruptible(reason = "Must not interact with garbage collections.")
    private void appendUnalignedHeapChunkUninterruptibly(UnalignedHeapChunk.UnalignedHeader uChunk) {
        UnalignedHeapChunk.UnalignedHeader oldLast = getLastUnalignedHeapChunk();
        HeapChunk.setSpace(uChunk, this);
        HeapChunk.setPrevious(uChunk, oldLast);
        HeapChunk.setNext(uChunk, Word.nullPointer());
        if (oldLast.isNonNull()) {
            HeapChunk.setNext(oldLast, uChunk);
        }
        setLastUnalignedHeapChunk(uChunk);
        if (getFirstUnalignedHeapChunk().isNull()) {
            setFirstUnalignedHeapChunk(uChunk);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void extractUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader uChunk) {
        assert VMOperation.isGCInProgress() : "Trying to extract an unaligned chunk but not in a VMOperation.";
        extractUnalignedHeapChunkUninterruptibly(uChunk);
        accounting.unnoteUnalignedHeapChunk(uChunk);
    }

    @Uninterruptible(reason = "Must not interact with garbage collections.")
    private void extractUnalignedHeapChunkUninterruptibly(UnalignedHeapChunk.UnalignedHeader uChunk) {
        UnalignedHeapChunk.UnalignedHeader chunkNext = HeapChunk.getNext(uChunk);
        UnalignedHeapChunk.UnalignedHeader chunkPrev = HeapChunk.getPrevious(uChunk);
        if (chunkPrev.isNonNull()) {
            HeapChunk.setNext(chunkPrev, chunkNext);
        } else {
            setFirstUnalignedHeapChunk(chunkNext);
        }
        if (chunkNext.isNonNull()) {
            HeapChunk.setPrevious(chunkNext, chunkPrev);
        } else {
            setLastUnalignedHeapChunk(chunkPrev);
        }
        /* Reset the fields that the result chunk keeps for Space. */
        HeapChunk.setNext(uChunk, Word.nullPointer());
        HeapChunk.setPrevious(uChunk, Word.nullPointer());
        HeapChunk.setSpace(uChunk, null);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public AlignedHeapChunk.AlignedHeader getFirstAlignedHeapChunk() {
        return firstAlignedHeapChunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void setFirstAlignedHeapChunk(AlignedHeapChunk.AlignedHeader chunk) {
        firstAlignedHeapChunk = chunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    AlignedHeapChunk.AlignedHeader getLastAlignedHeapChunk() {
        return lastAlignedHeapChunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void setLastAlignedHeapChunk(AlignedHeapChunk.AlignedHeader chunk) {
        lastAlignedHeapChunk = chunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public UnalignedHeapChunk.UnalignedHeader getFirstUnalignedHeapChunk() {
        return firstUnalignedHeapChunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void setFirstUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader chunk) {
        this.firstUnalignedHeapChunk = chunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    UnalignedHeapChunk.UnalignedHeader getLastUnalignedHeapChunk() {
        return lastUnalignedHeapChunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void setLastUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader chunk) {
        lastUnalignedHeapChunk = chunk;
    }

    /** Promote an aligned Object to this Space. */
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    Object copyAlignedObject(Object original, Space originalSpace) {
        assert ObjectHeaderImpl.isAlignedObject(original);
        assert originalSpace.isFromSpace() || (originalSpace == this && isCompactingOldSpace());

        Object copy = copyAlignedObject(original);
        if (copy != null) {
            ObjectHeaderImpl.getObjectHeaderImpl().installForwardingPointer(original, copy);
        }
        return copy;
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private Object copyAlignedObject(Object originalObj) {
        assert VMOperation.isGCInProgress();
        assert ObjectHeaderImpl.isAlignedObject(originalObj);

        UnsignedWord originalSize = LayoutEncoding.getSizeFromObjectInlineInGC(originalObj, false);
        UnsignedWord copySize = originalSize;
        boolean addIdentityHashField = false;
        if (ConfigurationValues.getObjectLayout().isIdentityHashFieldOptional()) {
            ObjectHeader oh = Heap.getHeap().getObjectHeader();
            Word header = oh.readHeaderFromObject(originalObj);
            if (probability(SLOW_PATH_PROBABILITY, ObjectHeaderImpl.hasIdentityHashFromAddressInline(header))) {
                addIdentityHashField = true;
                copySize = LayoutEncoding.getSizeFromObjectInlineInGC(originalObj, true);
                assert copySize.aboveOrEqual(originalSize);
            }
        }

        Pointer copyMemory = allocateMemory(copySize);
        if (probability(VERY_SLOW_PATH_PROBABILITY, copyMemory.isNull())) {
            return null;
        }

        /*
         * This does a direct memory copy, without regard to whether the copied data contains object
         * references. That's okay, because all references in the copy are visited and overwritten
         * later on anyways (the card table is also updated at that point if necessary).
         */
        Pointer originalMemory = Word.objectToUntrackedPointer(originalObj);
        UnmanagedMemoryUtil.copyLongsForward(originalMemory, copyMemory, originalSize);

        Object copy = copyMemory.toObjectNonNull();
        if (probability(SLOW_PATH_PROBABILITY, addIdentityHashField)) {
            // Must do first: ensures correct object size below and in other places
            int value = IdentityHashCodeSupport.computeHashCodeFromAddress(originalObj);
            int offset = LayoutEncoding.getIdentityHashOffset(copy);
            ObjectAccess.writeInt(copy, offset, value, IdentityHashCodeSupport.IDENTITY_HASHCODE_LOCATION);
            ObjectHeaderImpl.getObjectHeaderImpl().setIdentityHashInField(copy);
        }
        if (isOldSpace()) {
            if (SerialGCOptions.useCompactingOldGen() && GCImpl.getGCImpl().isCompleteCollection()) {
                /*
                 * In a compacting complete collection, the remembered set bit is set already during
                 * marking and the first object table is built later during fix-up.
                 */
            } else {
                /*
                 * If an object was copied to the old generation, its remembered set bit must be set
                 * and the first object table must be updated (even when copying from old to old).
                 */
                AlignedHeapChunk.AlignedHeader copyChunk = AlignedHeapChunk.getEnclosingChunk(copy);
                RememberedSet.get().enableRememberedSetForObject(copyChunk, copy, copySize);
            }
        }
        return copy;
    }

    /** Promote an AlignedHeapChunk by moving it to this space. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void promoteAlignedHeapChunk(AlignedHeapChunk.AlignedHeader chunk, Space originalSpace) {
        assert this != originalSpace && originalSpace.isFromSpace();

        originalSpace.extractAlignedHeapChunk(chunk);
        appendAlignedHeapChunk(chunk);

        if (this.isOldSpace()) {
            if (originalSpace.isYoungSpace()) {
                RememberedSet.get().enableRememberedSetForChunk(chunk);
            } else {
                assert originalSpace.isOldSpace();
                RememberedSet.get().clearRememberedSet(chunk);
            }
        }
    }

    /** Promote an UnalignedHeapChunk by moving it to this Space. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void promoteUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader chunk, Space originalSpace) {
        assert this != originalSpace && originalSpace.isFromSpace();

        originalSpace.extractUnalignedHeapChunk(chunk);
        appendUnalignedHeapChunk(chunk);

        if (this.isOldSpace()) {
            if (originalSpace.isYoungSpace()) {
                RememberedSet.get().enableRememberedSetForChunk(chunk);
            } else {
                assert originalSpace.isOldSpace();
                RememberedSet.get().clearRememberedSet(chunk);
            }
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private AlignedHeapChunk.AlignedHeader requestAlignedHeapChunk() {
        AlignedHeapChunk.AlignedHeader chunk;
        if (isYoungSpace()) {
            assert isSurvivorSpace();
            chunk = HeapImpl.getHeapImpl().getYoungGeneration().requestAlignedSurvivorChunk();
        } else {
            chunk = HeapImpl.getHeapImpl().getOldGeneration().requestAlignedChunk();
        }
        if (chunk.isNonNull()) {
            appendAlignedHeapChunk(chunk);
        }
        return chunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void absorb(Space src) {
        /*
         * Absorb the chunks of a source into this Space. I cannot just copy the lists, because each
         * HeapChunk has a reference to the Space it is in, so I have to touch them all.
         */
        AlignedHeapChunk.AlignedHeader aChunk = src.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            AlignedHeapChunk.AlignedHeader next = HeapChunk.getNext(aChunk);
            src.extractAlignedHeapChunk(aChunk);
            appendAlignedHeapChunk(aChunk);
            aChunk = next;
        }
        UnalignedHeapChunk.UnalignedHeader uChunk = src.getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            UnalignedHeapChunk.UnalignedHeader next = HeapChunk.getNext(uChunk);
            src.extractUnalignedHeapChunk(uChunk);
            appendUnalignedHeapChunk(uChunk);
            uChunk = next;
        }
        assert src.isEmpty();
    }

    /**
     * This value is only updated during a GC. Be careful when calling this method during a GC as it
     * might wrongly include chunks that will be freed at the end of the GC.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    UnsignedWord getChunkBytes() {
        assert !isEdenSpace() || areEdenBytesCorrect() : "eden bytes are only accurate during a GC, or at a safepoint after a TLAB flush";
        return getAlignedChunkBytes().add(accounting.getUnalignedChunkBytes());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean areEdenBytesCorrect() {
        if (VMOperation.isGCInProgress()) {
            return true;
        } else if (VMOperation.isInProgressAtSafepoint()) {
            /* Verify that there are no threads that have a TLAB. */
            for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
                ThreadLocalAllocation.Descriptor tlab = ThreadLocalAllocation.getTlab(thread);
                if (tlab.getAlignedAllocationStart(SubstrateAllocationSnippets.TLAB_START_IDENTITY).isNonNull() || tlab.getUnalignedChunk().isNonNull()) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    UnsignedWord getAlignedChunkBytes() {
        return accounting.getAlignedChunkBytes();
    }

    UnsignedWord computeObjectBytes() {
        assert !isEdenSpace() || areEdenBytesCorrect() : "eden bytes are only accurate during a GC, or at a safepoint after a TLAB flush";
        return computeAlignedObjectBytes().add(computeUnalignedObjectBytes());
    }

    private UnsignedWord computeAlignedObjectBytes() {
        UnsignedWord result = Word.zero();
        AlignedHeapChunk.AlignedHeader aChunk = getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            UnsignedWord allocatedBytes = HeapChunk.getTopOffset(aChunk).subtract(AlignedHeapChunk.getObjectsStartOffset());
            result = result.add(allocatedBytes);
            aChunk = HeapChunk.getNext(aChunk);
        }
        return result;
    }

    private UnsignedWord computeUnalignedObjectBytes() {
        UnsignedWord result = Word.zero();
        UnalignedHeapChunk.UnalignedHeader uChunk = getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            UnsignedWord allocatedBytes = HeapChunk.getTopOffset(uChunk).subtract(UnalignedHeapChunk.getObjectStartOffset(uChunk));
            result = result.add(allocatedBytes);
            uChunk = HeapChunk.getNext(uChunk);
        }
        return result;
    }

    boolean contains(Pointer p) {
        AlignedHeapChunk.AlignedHeader aChunk = getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            Pointer start = AlignedHeapChunk.getObjectsStart(aChunk);
            if (start.belowOrEqual(p) && p.belowThan(HeapChunk.getTopPointer(aChunk))) {
                return true;
            }
            aChunk = HeapChunk.getNext(aChunk);
        }

        UnalignedHeapChunk.UnalignedHeader uChunk = getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            Pointer start = UnalignedHeapChunk.getObjectStart(uChunk);
            if (start.belowOrEqual(p) && p.belowThan(HeapChunk.getTopPointer(uChunk))) {
                return true;
            }
            uChunk = HeapChunk.getNext(uChunk);
        }
        return false;
    }

    public boolean printLocationInfo(Log log, Pointer p) {
        AlignedHeapChunk.AlignedHeader aChunk = getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            if (HeapChunk.asPointer(aChunk).belowOrEqual(p) && p.belowThan(HeapChunk.getEndPointer(aChunk))) {
                boolean unusablePart = p.aboveOrEqual(HeapChunk.getTopPointer(aChunk));
                printChunkInfo(log, aChunk, "aligned", unusablePart);
                return true;
            }
            aChunk = HeapChunk.getNext(aChunk);
        }

        UnalignedHeapChunk.UnalignedHeader uChunk = getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            if (HeapChunk.asPointer(uChunk).belowOrEqual(p) && p.belowThan(HeapChunk.getEndPointer(uChunk))) {
                boolean unusablePart = p.aboveOrEqual(HeapChunk.getTopPointer(uChunk));
                printChunkInfo(log, uChunk, "unaligned", unusablePart);
                return true;
            }
            uChunk = HeapChunk.getNext(uChunk);
        }
        return false;
    }

    private void printChunkInfo(Log log, HeapChunk.Header<?> chunk, String chunkType, boolean unusablePart) {
        String toSpace = isToSpace ? "-T" : "";
        String unusable = unusablePart ? "unusable part of " : "";
        log.string("points into ").string(unusable).string(chunkType).string(" chunk ").zhex(chunk).spaces(1);
        log.string("(").string(getShortName()).string(toSpace).string(")");
    }
}
