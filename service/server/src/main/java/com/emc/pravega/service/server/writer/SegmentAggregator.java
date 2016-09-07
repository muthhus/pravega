/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.emc.pravega.service.server.writer;

import com.emc.pravega.common.AutoStopwatch;
import com.emc.pravega.common.Exceptions;
import com.emc.pravega.common.TimeoutTimer;
import com.emc.pravega.common.concurrent.FutureHelpers;
import com.emc.pravega.service.contracts.RuntimeStreamingException;
import com.emc.pravega.service.contracts.SegmentProperties;
import com.emc.pravega.service.server.CacheKey;
import com.emc.pravega.service.server.ContainerMetadata;
import com.emc.pravega.service.server.DataCorruptionException;
import com.emc.pravega.service.server.SegmentMetadata;
import com.emc.pravega.service.server.UpdateableSegmentMetadata;
import com.emc.pravega.service.server.logs.operations.CachedStreamSegmentAppendOperation;
import com.emc.pravega.service.server.logs.operations.MergeBatchOperation;
import com.emc.pravega.service.server.logs.operations.Operation;
import com.emc.pravega.service.server.logs.operations.StorageOperation;
import com.emc.pravega.service.server.logs.operations.StreamSegmentAppendOperation;
import com.emc.pravega.service.server.logs.operations.StreamSegmentSealOperation;
import com.emc.pravega.service.storage.Storage;
import com.google.common.base.Preconditions;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Aggregates contents for a specific StreamSegment.
 */
@Slf4j
class SegmentAggregator implements AutoCloseable {
    //region Members

    private final UpdateableSegmentMetadata metadata;
    private final WriterConfig config;
    private final LinkedList<StorageOperation> operations;
    private final AutoStopwatch stopwatch;
    private final String traceObjectId;
    private final Storage storage;
    private final WriterDataSource dataSource;
    private Duration lastFlush;
    private long outstandingLength;
    private long lastAddedOffset;
    private int mergeBatchCount;
    private boolean hasSealPending;
    private boolean closed;

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the SegmentAggregator class.
     *
     * @param segmentMetadata The Metadata for the StreamSegment to construct this Aggregator for.
     * @param dataSource      The WriterDataSource to use.
     * @param storage         The Storage to use (for flushing).
     * @param config          The Configuration to use.
     * @param stopwatch       A Stopwatch to use to determine elapsed time.
     */
    SegmentAggregator(UpdateableSegmentMetadata segmentMetadata, WriterDataSource dataSource, Storage storage, WriterConfig config, AutoStopwatch stopwatch) {
        Preconditions.checkNotNull(segmentMetadata, "segmentMetadata");
        Preconditions.checkNotNull(dataSource, "dataSource");
        Preconditions.checkNotNull(storage, "storage");
        Preconditions.checkNotNull(config, "config");
        Preconditions.checkNotNull(stopwatch, "stopwatch");

        this.metadata = segmentMetadata;
        Preconditions.checkArgument(this.metadata.getContainerId() == dataSource.getId(), "SegmentMetadata.ContainerId is different from WriterDataSource.Id");

        this.config = config;
        this.storage = storage;
        this.dataSource = dataSource;
        this.stopwatch = stopwatch;
        this.lastFlush = stopwatch.elapsed();
        this.outstandingLength = 0;
        this.lastAddedOffset = -1; // Will be set properly in initialize().
        this.mergeBatchCount = 0;
        this.operations = new LinkedList<>();
        this.traceObjectId = String.format("StorageWriter[%d-%d]", this.metadata.getContainerId(), this.metadata.getId());
    }

    //endregion

    //region AutoCloseable Implementation

    @Override
    public void close() {
        if (!this.closed) {
            log.info("{}: Closed.");
            this.closed = true;
        }
    }

    //endregion

    //region Properties

    /**
     * Gets a reference to the SegmentMetadata related to this Aggregator.
     *
     * @return The metadata.
     */
    SegmentMetadata getMetadata() {
        return this.metadata;
    }

    /**
     * Gets the SequenceNumber of the first operation that is not fully committed to Storage.
     *
     * @return The result.
     */
    long getLowestUncommittedSequenceNumber() {
        return this.operations.size() == 0 ? Operation.NO_SEQUENCE_NUMBER : this.operations.getFirst().getSequenceNumber();
    }

    /**
     * Gets a value representing the amount of time since the last successful call to flush(). If no such call has been
     * made yet, this returns the amount of time since the creation of this SegmentAggregator object.
     *
     * @return The result.
     */
    Duration getElapsedSinceLastFlush() {
        return this.stopwatch.elapsed().minus(this.lastFlush);
    }

    /**
     * Gets a value indicating whether a call to flush() is required given the current state of this SegmentAggregator.
     * <p>
     * Any of the following conditions can trigger a flush:
     * <ul>
     * <li> There is more data in the SegmentAggregator than the configuration allows (getOutstandingLength >= FlushThresholdBytes)
     * <li> Too much time has passed since the last call to flush() (getElapsedSinceLastFlush >= FlushThresholdTime)
     * <li> The SegmentAggregator contains a StreamSegmentSealOperation or MergeBatchOperation (hasSealPending == true)
     * </ul>
     *
     * @return The result.
     */
    boolean mustFlush() {
        return exceedsThresholds()
                || this.hasSealPending
                || this.mergeBatchCount > 0;
    }

    /**
     * Gets a value indicating whether the SegmentAggregator is closed (for any kind of operations).
     */
    boolean isClosed() {
        return this.closed;
    }

    /**
     * Gets a value indicating whether the Flush thresholds are exceeded for this SegmentAggregator.
     *
     * @return
     */
    private boolean exceedsThresholds() {
        return this.outstandingLength >= this.config.getFlushThresholdBytes()
                || getElapsedSinceLastFlush().compareTo(this.config.getFlushThresholdTime()) >= 0;
    }

    @Override
    public String toString() {
        return String.format(
                "[%d: %s] Count = %d, Length = %d, LastOffset = %d, LastFlush = %ds",
                this.metadata.getId(),
                this.metadata.getName(),
                this.operations.size(),
                this.outstandingLength,
                this.lastAddedOffset,
                this.getElapsedSinceLastFlush().toMillis() / 1000);
    }

    //endregion

    //region Operations

    /**
     * Initializes the SegmentAggregator by pulling information from the given Storage.
     *
     * @param timeout Timeout for the operation.
     * @return A CompletableFuture that, when completed, will indicate that the operation finished successfully. If any
     * errors occurred during the operation, the Future will be completed with the appropriate exception.
     */
    CompletableFuture<Void> initialize(Duration timeout) {
        Exceptions.checkNotClosed(this.closed, this);
        Preconditions.checkState(this.lastAddedOffset < 0, "SegmentAggregator has already been initialized.");

        return this.storage
                .getStreamSegmentInfo(this.metadata.getName(), timeout)
                .thenAccept(segmentInfo -> {
                    // Check & Update StorageLength in metadata.
                    if (this.metadata.getStorageLength() != segmentInfo.getLength()) {
                        if (this.metadata.getStorageLength() >= 0) {
                            // Only log warning if the StorageLength has actually been initialized, but is different.
                            log.warn("{}: SegmentMetadata has a StorageLength ({}) that is different than the actual one ({}) - updating metadata.", this.traceObjectId, this.metadata.getStorageLength(), segmentInfo.getLength());
                        }

                        // It is very important to keep this value up-to-date and correct.
                        this.metadata.setStorageLength(segmentInfo.getLength());
                    }

                    // Check if the Storage segment is sealed, but it's not in metadata (this is 100% indicative of some data corruption happening).
                    if (!this.metadata.isSealed() && segmentInfo.isSealed()) {
                        throw new RuntimeStreamingException(new DataCorruptionException(String.format("Segment '%s' is sealed in Storage but not in the metadata.", this.metadata.getName())));
                    }

                    this.lastAddedOffset = this.metadata.getStorageLength();
                    log.info("{}: Initialized. StorageLength = {}, Sealed = {}.", this.traceObjectId, segmentInfo.getLength(), segmentInfo.isSealed());
                });
    }

    /**
     * Adds the given StorageOperation to the Aggregator.
     *
     * @param operation the Operation to add.
     * @throws DataCorruptionException  If the validation of the given Operation indicates a possible data corruption in
     *                                  the code (offset gaps, out-of-order operations, etc.)
     * @throws IllegalArgumentException If the validation of the given Operation indicates a possible non-corrupting bug
     *                                  in the code.
     */
    void add(StorageOperation operation) throws DataCorruptionException {
        ensureInitializedAndNotClosed();

        // Verify operation Segment Id.
        checkSegmentId(operation);

        // Verify operation validity (this also takes care of extra operations after Seal or Merge; no need for further checks).
        checkValidOperation(operation);

        // Add operation to list
        this.operations.addLast(operation);
        if (operation instanceof MergeBatchOperation) {
            this.mergeBatchCount++;
        } else if (operation instanceof StreamSegmentSealOperation) {
            this.hasSealPending = true;
        }

        // Update current state (note that MergeBatchOperations have a length of 0 if added to the BatchStreamSegment - because they don't have any effect on it).
        this.outstandingLength += operation.getLength();
        this.lastAddedOffset = operation.getStreamSegmentOffset() + operation.getLength();
    }

    //endregion

    //region Flushing and Merging

    /**
     * Flushes the contents of the Aggregator to the given Storage.
     *
     * @param timeout Timeout for the operation.
     * @return A CompletableFuture that, when completed, will contain a summary of the flush operation. If any errors
     * occurred during the flush, the Future will be completed with the appropriate exception.
     */
    CompletableFuture<FlushResult> flush(Duration timeout) {
        ensureInitializedAndNotClosed();

        try {
            TimeoutTimer timer = new TimeoutTimer(timeout);
            boolean hasMerge = this.mergeBatchCount > 0;
            if (this.hasSealPending || hasMerge) {
                // If we have a Seal or Merge Pending, flush everything until we reach that operation.
                CompletableFuture<FlushResult> result = flushFully(timer);
                if (hasMerge) {
                    result = result.thenCompose(flushResult -> mergeIfNecessary(flushResult, timer));
                }

                if (this.hasSealPending) {
                    result = result.thenCompose(flushResult -> sealIfNecessary(flushResult, timer));
                }

                return result;
            } else {
                // Otherwise, just flush the excess as long as we have something to flush.
                return flushExcess(timer);
            }
        } catch (Throwable ex) {
            return FutureHelpers.failedFuture(ex);
        }
    }

    /**
     * Flushes all Append Operations that can be flushed at the given moment (until the entire Aggregator is emptied out
     * or until a StreamSegmentSealOperation or MergeBatchOperation is encountered).
     *
     * @param timer Timer for the operation.
     * @return A CompletableFuture that, when completed, will contain the result from the flush operation.
     * @throws DataCorruptionException If a CachedStreamSegmentAppendOperation does not have any data in the cache.
     */
    private CompletableFuture<FlushResult> flushFully(TimeoutTimer timer) throws DataCorruptionException {
        return flushConditionally(timer, () -> this.operations.size() > 0 && isAppendOperation(this.operations.getFirst()));
    }

    /**
     * Flushes as many Append Operations as needed as long as the data inside this SegmentAggregator exceeds size/time thresholds.
     * This will stop when either the thresholds are not exceeded anymore or when a non-Append Operation is encountered.
     *
     * @param timer Timer for the operation.
     * @return A CompletableFuture that, when completed, will contain the result from the flush operation.
     * @throws DataCorruptionException If a CachedStreamSegmentAppendOperation does not have any data in the cache.
     */
    private CompletableFuture<FlushResult> flushExcess(TimeoutTimer timer) throws DataCorruptionException {
        return flushConditionally(timer, this::exceedsThresholds);
    }

    /**
     * Flushes all Append Operations that can be flushed at the given moment (as long ast he given condition holds true).
     *
     * @param timer Timer for the operation.
     * @return A CompletableFuture that, when completed, will contain the result from the flush operation.
     * @throws DataCorruptionException If a CachedStreamSegmentAppendOperation does not have any data in the cache.
     */
    private CompletableFuture<FlushResult> flushConditionally(TimeoutTimer timer, Supplier<Boolean> condition) throws DataCorruptionException {
        FlushResult result = new FlushResult();

        // Flush all outstanding data as long as the threshold is exceeded.
        while (condition.get()) {
            // TODO: figure out how to get rid of this join. Is there something like an AsyncLoop with Futures?
            FlushResult partialFlushResult = flushOnce(timer.getRemaining()).join();
            result.withFlushResult(partialFlushResult);
        }

        return CompletableFuture.completedFuture(result);
    }

    /**
     * Flushes all Append Operations that can be flushed up to the maximum allowed flush size.
     *
     * @param timeout Timeout for the operation.
     * @return A CompletableFuture that, when completed, will contain the result from the flush operation.
     * @throws DataCorruptionException If a CachedStreamSegmentAppendOperation does not have any data in the cache.
     */
    private CompletableFuture<FlushResult> flushOnce(Duration timeout) throws DataCorruptionException {
        // Gather an InputStream made up of all the operations we can flush.
        FlushArgs flushArgs = getFlushArgs();

        if (flushArgs.getTotalLength() == 0) {
            // Nothing to flush.
            return CompletableFuture.completedFuture(new FlushResult());
        }

        // Flush them.
        InputStream inputStream = flushArgs.getInputStream();
        return this.storage
                .write(this.metadata.getName(), this.metadata.getStorageLength(), inputStream, flushArgs.getTotalLength(), timeout)
                .thenApply(v -> updateStatePostFlush(flushArgs));
    }

    /**
     * Aggregates all outstanding Append Operations into a single object that can be used for flushing. This continues
     * to aggregate operations until a non-Append operation is encountered or until we have accumulated enough data (exceeding config.getMaxFlushSizeBytes).
     *
     * @return The aggregated object that can be used for flushing.
     * @throws DataCorruptionException If a CachedStreamSegmentAppendOperation does not have any data in the cache.
     */
    private FlushArgs getFlushArgs() throws DataCorruptionException {
        FlushArgs result = new FlushArgs();
        for (StorageOperation op : this.operations) {
            if (result.getTotalLength() > 0 && result.getTotalLength() + op.getLength() > this.config.getMaxFlushSizeBytes()) {
                // We will be exceeding the maximum flush size if we include this operation. Stop here and return the result.
                // However, we want to make sure we flush at least one item, which is why we check TotalLength to be 0.
                // The add() method should make sure the length of one single operation does not exceed the total flush size.
                break;
            }

            byte[] data;
            if (op instanceof StreamSegmentAppendOperation) {
                data = ((StreamSegmentAppendOperation) op).getData();
            } else if (op instanceof CachedStreamSegmentAppendOperation) {
                CacheKey key = ((CachedStreamSegmentAppendOperation) op).getCacheKey();
                data = this.dataSource.getAppendData(key);
                if (data == null) {
                    throw new DataCorruptionException(String.format("Unable to retrieve CacheContents for operation '%s', with key '%s'.", op, key));
                }
            } else {
                // We found one operation that is not an append; this is as much as we can flush.
                break;
            }

            result.add(data);
        }

        return result;
    }

    /**
     * Executes a merger of a Batch StreamSegment into this one.
     * Conditions for merger:
     * <ul>
     * <li> This StreamSegment is stand-alone (not a batch).
     * <li> The next outstanding operation is a MergeBatchOperation for a BatchStreamSegment of this StreamSegment.
     * <li> The StreamSegment to merge is not deleted, it is sealed and is fully flushed to Storage.
     * </ul>
     * Effects of the merger:
     * <ul> The entire contents of the given batch StreamSegment will be concatenated to this StreamSegment as one unit.
     * <li> The metadata for this StreamSegment will be updated to reflect the new length of this StreamSegment.
     * <li> The given batch Segment will cease to exist.
     * </ul>
     * <p>
     * Note that various other data integrity checks are done pre and post merger as part of this operation which are meant
     * to ensure the StreamSegment is not in a corrupted state.
     *
     * @param flushResult The flush result from the previous chained operation.
     * @param timer       Timer for the operation.
     * @return A CompletableFuture that, when completed, will contain the number of bytes that were merged into this
     * StreamSegment. If failed, the Future will contain the exception that caused it.
     */
    private CompletableFuture<FlushResult> mergeIfNecessary(FlushResult flushResult, TimeoutTimer timer) {
        ensureInitializedAndNotClosed();
        assert this.metadata.getParentId() == ContainerMetadata.NO_STREAM_SEGMENT_ID : "Cannot merge into a Batch StreamSegment.";

        if (this.operations.size() == 0 || !(this.operations.getFirst() instanceof MergeBatchOperation)) {
            // Either no operation or first operation is not a MergeBatch. Nothing to do.
            return CompletableFuture.completedFuture(flushResult);
        }

        // TODO: This only processes one merge at a time. If we had several, that would mean each is done in a different iteration. Should we fix this?
        MergeBatchOperation mergeBatchOperation = (MergeBatchOperation) this.operations.getFirst();
        UpdateableSegmentMetadata batchMetadata = this.dataSource.getStreamSegmentMetadata(mergeBatchOperation.getBatchStreamSegmentId());
        return mergeWith(batchMetadata, timer)
                .thenApply(flushResult::withFlushResult);
    }

    /**
     * Merges the batch StreamSegment with given metadata into this one at the current offset.
     *
     * @param batchMetadata The metadata of the batchStreamSegment to merge.
     * @param timer         Timer for the operation.
     * @return A CompletableFuture that, when completed, will contain the number of bytes that were merged into this
     * StreamSegment. If failed, the Future will contain the exception that caused it.
     */
    private CompletableFuture<FlushResult> mergeWith(UpdateableSegmentMetadata batchMetadata, TimeoutTimer timer) {
        if (batchMetadata.isDeleted()) {
            return FutureHelpers.failedFuture(new DataCorruptionException(String.format("Attempted to merge with deleted batch segment '%s'.", batchMetadata.getName())));
        }

        FlushResult result = new FlushResult();
        if (!batchMetadata.isSealedInStorage() || batchMetadata.getDurableLogLength() > batchMetadata.getStorageLength()) {
            // Nothing to do. Given Batch is not eligible for merger yet.
            return CompletableFuture.completedFuture(result);
        }

        AtomicLong mergedLength = new AtomicLong();
        return this.storage
                .getStreamSegmentInfo(batchMetadata.getName(), timer.getRemaining())
                .thenAccept(batchSegmentProperties -> {
                    // One last verification before the actual merger:
                    // Check that the Storage agrees with our metadata (if not, we have a problem ...)
                    if (batchSegmentProperties.getLength() != batchMetadata.getStorageLength()) {
                        throw new CompletionException(new DataCorruptionException(String.format(
                                "Batch Segment '%s' cannot be merged into parent '%s' because its metadata disagrees with the Storage. Metadata.StorageLength=%d, Storage.StorageLength=%d",
                                batchMetadata.getName(),
                                this.metadata.getName(),
                                batchMetadata.getStorageLength(),
                                batchSegmentProperties.getLength())));
                    }

                    mergedLength.set(batchSegmentProperties.getLength());
                })
                .thenCompose(v1 -> storage.concat(this.metadata.getName(), batchMetadata.getName(), timer.getRemaining()))
                .thenCompose(v2 -> storage.getStreamSegmentInfo(this.metadata.getName(), timer.getRemaining()))
                .thenApply(segmentProperties -> {
                    // We have processed a MergeBatchOperation, pop the first operation off and decrement the counter.
                    StorageOperation processedOperation = this.operations.removeFirst();
                    assert processedOperation instanceof MergeBatchOperation : "First outstanding operation was not a MergeBatchOperation";
                    assert ((MergeBatchOperation) processedOperation).getBatchStreamSegmentId() == batchMetadata.getId() : "First outstanding operation was a MergeBatchOperation for the wrong batch id.";
                    this.mergeBatchCount--;
                    assert this.mergeBatchCount >= 0 : "Negative value for mergeBatchCount";

                    // Post-merger validation. Verify we are still in agreement with the storage.
                    long expectedNewLength = this.metadata.getStorageLength() + mergedLength.get();
                    if (segmentProperties.getLength() != expectedNewLength) {
                        throw new CompletionException(new DataCorruptionException(String.format(
                                "Batch Segment '%s' was merged into parent '%s' but the parent segment has an unexpected StorageLength after the merger. Previous=%d, MergeLength=%d, Expected=%d, Actual=%d",
                                batchMetadata.getName(),
                                this.metadata.getName(),
                                segmentProperties.getLength(),
                                mergedLength.get(),
                                expectedNewLength,
                                segmentProperties.getLength())));
                    }

                    updateMetadata(segmentProperties);
                    updateMetadataForBatchPostMerger(batchMetadata);

                    this.lastFlush = this.stopwatch.elapsed();
                    return result.withMergedBytes(mergedLength.get());
                });
    }

    /**
     * Seals the StreamSegment in Storage, if necessary.
     *
     * @param flushResult The FlushResult from a previous Flush operation. This will just be passed-through.
     * @param timer       Timer for the operation.
     * @return The FlushResult passed in as an argument.
     */
    private CompletableFuture<FlushResult> sealIfNecessary(FlushResult flushResult, TimeoutTimer timer) {
        if (!this.hasSealPending || !(this.operations.getFirst() instanceof StreamSegmentSealOperation)) {
            // Either no Seal is pending or the next operation is not a seal - we cannot execute a seal.
            return CompletableFuture.completedFuture(flushResult);
        }

        return this.storage
                .seal(this.metadata.getName(), timer.getRemaining())
                .thenApply(v -> {
                    this.metadata.markSealedInStorage();
                    this.operations.removeFirst();

                    // Validate we have no more unexpected items and then close (as we shouldn't be getting anything else).
                    assert this.operations.size() == 0 : "Processed StreamSegmentSeal operation but more operations are outstanding.";
                    this.hasSealPending = false;
                    close();
                    return flushResult;
                });
    }

    //endregion

    //region Helpers

    /**
     * Ensures the following conditions are met:
     * * Regular Operations: SegmentId matches this SegmentAggregator's SegmentId
     * * Batches: TargetSegmentId/SegmentId matches this SegmentAggregator's SegmentId.
     *
     * @param operation The operation to check.
     * @throws IllegalArgumentException If any of the validations failed.
     */
    private void checkSegmentId(StorageOperation operation) {
        // All exceptions thrown from here are RuntimeExceptions (as opposed from DataCorruptionExceptions); they are indicative
        // of bad code (objects got routed to wrong SegmentAggregators) and not data corruption.
        if (operation instanceof MergeBatchOperation) {
            Preconditions.checkArgument(
                    this.metadata.getParentId() == ContainerMetadata.NO_STREAM_SEGMENT_ID,
                    "MergeBatchOperations can only be added to the parent StreamSegment; received '%s'.", operation);

            // Since we are a stand-alone StreamSegment; verify that the Operation has us as a parent (target).
            Preconditions.checkArgument(
                    operation.getStreamSegmentId() == this.metadata.getId(),
                    "Operation '%s' refers to a different StreamSegment as a target (parent) than this one (%s).", operation, this.metadata.getId());
        } else {
            // Regular operation.
            Preconditions.checkArgument(
                    operation.getStreamSegmentId() == this.metadata.getId(),
                    "Operation '%s' refers to a different StreamSegment than this one (%s).", operation, this.metadata.getId());
        }
    }

    /**
     * Ensures the following conditions are met:
     * * Operation Offset matches the last Offset from the previous operation (that is, operations are contiguous).
     *
     * @param operation The operation to check.
     * @throws DataCorruptionException  If any of the validations failed.
     * @throws IllegalArgumentException If the operation has an undefined Offset or Length (these are not considered data-
     *                                  corrupting issues).
     */
    private void checkValidOperation(StorageOperation operation) throws DataCorruptionException {
        if (this.hasSealPending) {
            // After a StreamSegmentSeal, we do not allow any other operation.
            throw new DataCorruptionException(String.format("No operation is allowed for a sealed segment; received '%s' .", operation));
        }

        // Verify operation offset against the lastAddedOffset (whether the last Op in the list or StorageLength).
        long offset = operation.getStreamSegmentOffset();
        long length = operation.getLength();
        Preconditions.checkArgument(offset >= 0, "Operation '%s' has an invalid offset (%s).", operation, operation.getStreamSegmentOffset());
        Preconditions.checkArgument(length >= 0, "Operation '%s' has an invalid length (%s).", operation, operation.getLength());

        // Check that operations are contiguous.
        if (offset != this.lastAddedOffset) {
            throw new DataCorruptionException(String.format("Wrong offset for Operation '%s'. Expected: %d, actual: %d.", operation, this.lastAddedOffset, offset));
        }

        // Even though the DurableLog should take care of this, doesn't hurt to check again that we cannot add anything
        // after a StreamSegmentSealOperation.
        if (this.hasSealPending) {
            throw new DataCorruptionException(String.format("Cannot add any operation after sealing a Segment; received '%s'.", operation));
        }

        // Check that the operation does not exceed the DurableLogLength of the StreamSegment.
        if (offset + length > this.metadata.getDurableLogLength()) {
            throw new DataCorruptionException(String.format(
                    "Operation '%s' has at least one byte beyond its DurableLogLength. Offset = %d, Length = %d, DurableLogLength = %d.",
                    operation,
                    offset,
                    length,
                    this.metadata.getDurableLogLength()));
        }

        if (operation instanceof StreamSegmentSealOperation) {
            // For StreamSegmentSealOperations, we must ensure the offset of the operation is equal to the DurableLogLength for the segment.
            if (this.metadata.getDurableLogLength() != offset) {
                throw new DataCorruptionException(String.format(
                        "Wrong offset for Operation '%s'. Expected: %d (DurableLogLength), actual: %d.",
                        operation,
                        this.metadata.getDurableLogLength(),
                        offset));
            }

            // Even though not an offset, we should still verify that the metadata actually thinks this is a sealed segment.
            if (!this.metadata.isSealed()) {
                throw new DataCorruptionException(String.format("Received Operation '%s' for a non-sealed segment.", operation));
            }
        } else if (operation instanceof StreamSegmentAppendOperation || operation instanceof CachedStreamSegmentAppendOperation) {
            // Make sure that no single operation exceeds the MaxFlushSizeBytes - since we only flush in whole operations at this time.
            Preconditions.checkArgument(length <= this.config.getMaxFlushSizeBytes(), "Operation '%s' exceeds the Maximum Flush Size (%s) as specified in the configuration.", operation, config.getMaxFlushSizeBytes());
        }
    }

    /**
     * Updates the metadata and the internal state after a flush was completed.
     *
     * @param flushArgs The arguments used for flushing.
     * @return A FlushResult containing statistics about the flush operation.
     */
    private FlushResult updateStatePostFlush(FlushArgs flushArgs) {
        for (int i = 0; i < flushArgs.getCount(); i++) {
            StorageOperation op = this.operations.removeFirst();
            assert isAppendOperation(op) : "Flushed operation was not an Append.";
        }

        // Update the metadata Storage Length.
        this.metadata.setStorageLength(this.metadata.getStorageLength() + flushArgs.getTotalLength());

        // Update the outstanding length.
        this.outstandingLength -= flushArgs.getTotalLength();
        assert this.outstandingLength >= 0 : "negative outstandingLength";

        // Update the last flush checkpoint.
        this.lastFlush = this.stopwatch.elapsed();
        return new FlushResult().withFlushedBytes(flushArgs.getTotalLength());
    }

    /**
     * Updates the metadata and based on the given SegmentProperties object.
     *
     * @param segmentProperties The SegmentProperties object to update from.
     */
    private void updateMetadata(SegmentProperties segmentProperties) {
        this.metadata.setStorageLength(segmentProperties.getLength());
        if (segmentProperties.isSealed() && !this.metadata.isSealedInStorage()) {
            this.metadata.markSealed();
            this.metadata.markSealedInStorage();
        }
    }

    private void updateMetadataForBatchPostMerger(UpdateableSegmentMetadata batchMetadata) {
        // The other StreamSegment no longer exists and/or is no longer usable. Make sure it is marked as deleted.
        batchMetadata.markDeleted();
        this.dataSource.deleteStreamSegment(batchMetadata.getName()); // This may be redundant...

        // Complete the merger (in the ReadIndex and whatever other listeners we might have).
        this.dataSource.completeMerge(batchMetadata.getParentId(), batchMetadata.getId());
    }

    /**
     * Determines if the given StorageOperation is an Append Operation.
     *
     * @param op The operation to test.
     * @return True if an Append Operation (Cached or non-cached), false otherwise.
     */
    private boolean isAppendOperation(StorageOperation op) {
        return (op instanceof StreamSegmentAppendOperation) || (op instanceof CachedStreamSegmentAppendOperation);
    }

    private void ensureInitializedAndNotClosed() {
        Exceptions.checkNotClosed(this.closed, this);
        Preconditions.checkState(this.lastAddedOffset >= 0, "SegmentAggregator is not initialized. Cannot execute this operation.");
    }

    //endregion
}
