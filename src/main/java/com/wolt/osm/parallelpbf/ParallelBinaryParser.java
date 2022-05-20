/*
 * This file is part of parallelpbf.
 *
 *     parallelpbf is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Foobar is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Foobar.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.wolt.osm.parallelpbf;

import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import com.wolt.osm.parallelpbf.blob.BlobInformation;
import com.wolt.osm.parallelpbf.blob.BlobReader;
import com.wolt.osm.parallelpbf.entity.BoundBox;
import com.wolt.osm.parallelpbf.entity.Header;
import com.wolt.osm.parallelpbf.entity.Node;
import com.wolt.osm.parallelpbf.entity.Relation;
import com.wolt.osm.parallelpbf.entity.Way;
import com.wolt.osm.parallelpbf.io.OSMDataReader;
import com.wolt.osm.parallelpbf.io.OSMHeaderReader;
import com.wolt.osm.parallelpbf.io.OSMReader;

import lombok.extern.slf4j.Slf4j;

/**
 * Parallel OSM PBF format parser.
 *
 * See https://github.com/woltapp/parallelpbf for the details and usage example.
 */
@Slf4j
public final class ParallelBinaryParser {

    /**
     * Changeset processing callback. Must be reentrant.
     */
    private Consumer<Long> changesetsCb;

    /**
     * Relations processing callback. Must be reentrant.
     */
    private Consumer<Relation> relationsCb;

    /**
     * Nodes processing callback. Must be reentrant.
     */
    private Consumer<Node> nodesCb;

    /**
     * Ways processing callback. Must be reentrant.
     */
    private Consumer<Way> waysCb;

    /**
     * Header processing callback. Must be reentrant.
     */
    private Consumer<Header> headerCb;

    /**
     * Header processing callback. Must be reentrant.
     */
    private Consumer<BoundBox> boundBoxCb;

    /**
     * Callback that will be called, when all blocks are parsed.
     */
    private Runnable completeCb;

    /**
     * Number of threads to use.
     */
    private final int threads;

    /**
     * Two parameters below are used to support sharding and partial file reading.
     *
     * The first one, partitions, tells the reader how many partitions exists in total.
     */
    private final int partitions;

    /**
     * Second one tells the reader, on which partitions it works. The reader will count
     * seen OsmData blocks, divide them with number of partitions, get division remainder and if
     * remainder is equal to reader's shard, block will be processed. Otherwise, reader will forward the input
     * stream till next block.
     */
    private final int shard;

    /**
     * A submitted task limiter. While executor can limit number of running tasks to the number of running threads,
     * we do not want to submit too many tasks, as each task consumes some RAM for the blob data and OSM PBF can be
     * tens of gigabytes, so clearly will not fit to the RAM.
     *
     * To achieve that we set the limit of the task limiter semaphore to the number of thread and each submitted task
     * increases semaphore value. At the same time, on completion each task decreases semaphore value. As submission
     * is a synchronous process and executed in the same thread, that reads blobs from the stream,
     * it will automatically block stream until there will be place in the threads pool.
     */
    private final Semaphore tasksLimiter;
    /**
     * Blob reade helper, wrapping incoming stream with OSM PBF data.
     */
    private final BlobReader reader;

    /**
     * Executor shared between class methods.
     * It's lifecycle is managed by parse() method.
     */
    private ExecutorService executor;
    /**
     * Number of currently running tasks. Each tasks is added to that list
     * after submission and list is cleared of complete tasks during submission of the following tasks.
     *
     * Where there will be no tasks left to submit, every remaining task will be awaited to complete.
     * After that executor (see above) will be destroyed and onComplete callback will be called.
     */
    private final List<Future<?>> tasksInFlight = new LinkedList<>();

    /**
     * Data block counter for partitioning, starts with zero, so first data block (which is OsmHeader block)
     * will be always processed.
     */
    private Integer currentDataBlock = 0;

    /**
     * Used in parse time, marks that header block was seen (but may not be processed yet).
     */
    private Boolean headerSeen = false;
    /**
     * Constructs reader from the blob information.
     * @param blob OSMData blob to read
     * @param information Information describing OSMData blob above.
     * @return OSMReader instance, that knows how to work with that blob or empty if blob data is not supported.
     */
    private Optional<OSMReader> makeReaderForBlob(final byte[] blob, final BlobInformation information) {
        switch (information.getType()) {
            case BlobInformation.TYPE_OSM_DATA:
                if (!headerSeen) {
                    log.error("Got OSMData before OSMHeader");
                    return Optional.empty();
                }
                return Optional.of(new OSMDataReader(blob, tasksLimiter, nodesCb, waysCb, relationsCb, changesetsCb));
            case BlobInformation.TYPE_OSM_HEADER:
                headerSeen = true;
                return Optional.of(new OSMHeaderReader(blob, tasksLimiter, headerCb, boundBoxCb));
            default:
                return Optional.empty();
        }
    }

    /**
     * Executes osm reader asynchronously. This method submits
     * supplied reader to the executor and increases throttle count.
     *
     * @param osmReader Reader to execute.
     * @return Future pointing to running reader or empty in case of error.
     */
    private Optional<? extends Future<?>> runReaderAsync(final OSMReader osmReader) {
        try {
            tasksLimiter.acquire();
        } catch (InterruptedException e) {
            log.error("Failed to acquire processing slot: {}", e.getMessage(), e);
            return Optional.empty();
        }
        try {
            return Optional.of(executor.submit(osmReader));
        } catch (RejectedExecutionException e) {
            tasksLimiter.release();
            log.error("Failed to start processing of blob: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Processes blob with osm data asynchronously.
     *
     * @param information Blob's size and type,
     * @return Processing results in form of Optional Future. Empty Optional
     * means, that processing hasn't started, while Future can be
     * awaited till end of the blob processing.
     */
    private Optional<? extends Future<?>> processDataBlob(final BlobInformation information) {
        //Check, that we have listeners for the data blocks and stop processing, if no
        if (nodesCb != null || waysCb != null || relationsCb != null || changesetsCb != null || !headerSeen) {

            int currentShard = currentDataBlock % partitions;
            log.trace("Current shard: {}, current block: {}, my shard: {}", currentShard, currentDataBlock, shard);
            ++currentDataBlock;
            if (currentShard == shard || information.getType().equals("OSMHeader")) {
                return reader.readBlob(information.getSize())
                        .flatMap(value -> makeReaderForBlob(value, information))
                        .flatMap(this::runReaderAsync);
            } else {
                var skipped = reader.skip(information.getSize());
                return skipped.map(CompletableFuture::completedFuture);
            }
        } else {
            return Optional.empty();
        }
    }

    /**
     * Sets OSM PBF file to parse and number of threads to use.
     * @param input Any inputstream pointing to the beginning of the OSM PBF data.
     * @param noThreads Number of threads to use. The best results can be achieved when this value
     *                  is set to number of available CPU cores or twice the number of available CPU cores.
     *                  Each thread will use up to 64MB of ram to keep blob data and actually may grow up to
     *                  several hundreds of megabytes.
     */
    public ParallelBinaryParser(final InputStream input, final int noThreads) {
        reader = new BlobReader(input);
        threads = noThreads;
        tasksLimiter = new Semaphore(noThreads);
        partitions = 1;
        shard = 0;

    }

    /**
     * Sets OSM PBF file to parse and number of threads to use.
     * @param input Any inputstream pointing to the beginning of the OSM PBF data.
     * @param noThreads Number of threads to use. The best results can be achieved when this value
     *                  is set to number of available CPU cores or twice the number of available CPU cores.
     *                  Each thread will use up to 64MB of ram to keep blob data and actually grow up to
     *                  hundreds of megabytes.
     * @param noPartitions Specifies how many partitions should be in the input stream.
     * @param myShard Specifies id of partition, associated with this instance of the parser.
     */
    public ParallelBinaryParser(final InputStream input, final int noThreads,
                                final int noPartitions, final int myShard) {
        reader = new BlobReader(input);
        threads = noThreads;
        tasksLimiter = new Semaphore(noThreads);
        partitions = noPartitions;
        shard = myShard;
    }

    /**
     * Sets changeset callback, that will be called for each successfully parsed Changeset.
     *
     * @param onChangesets Callback function. May be null, in that case parsing of changesets will be skipped.
     * @return ParallelBinaryParser to mimic builder interface.
     */
    public ParallelBinaryParser onChangeset(final Consumer<Long> onChangesets) {
        this.changesetsCb = onChangesets;
        return this;
    }

    /**
     * Sets relation callback, that will be called for each successfully parsed Relation.
     *
     * @param onRelations Callback function. May be null, in that case parsing of relations will be skipped.
     * @return ParallelBinaryParser to mimic builder interface.
     */
    public ParallelBinaryParser onRelation(final Consumer<Relation> onRelations) {
        this.relationsCb = onRelations;
        return this;
    }

    /**
     * Sets node callback, that will be called for each successfully parsed Node.
     *
     * @param onNodes Callback function. May be null, in that case parsing of nodes will be skipped.
     * @return ParallelBinaryParser to mimic builder interface.
     */
    public ParallelBinaryParser onNode(final Consumer<Node> onNodes) {
        this.nodesCb = onNodes;
        return this;
    }

    /**
     * Sets way callback, that will be called for each successfully parsed Way.
     *
     * @param onWays Callback function. May be null, in that case parsing of ways will be skipped.
     * @return ParallelBinaryParser to mimic builder interface.
     */
    public ParallelBinaryParser onWay(final Consumer<Way> onWays) {
        this.waysCb = onWays;
        return this;
    }

    /**
     * Sets header callback, that will be called on successful parse of the Header message.
     *
     * @param onHeader Callback function. May be null, in that case it will not be called,
     *                 but header still will be parsed.
     * @return ParallelBinaryParser to mimic builder interface.
     */
    public ParallelBinaryParser onHeader(final Consumer<Header> onHeader) {
        this.headerCb = onHeader;
        return this;
    }

    /**
     * Sets bounding box callback, that will be called on successful parse of the
     * BoundBox message.
     *
     * @param onBoundBox Callback function. May be null, in that case bound box will not be parsed at all.
     * @return ParallelBinaryParser to mimic builder interface.
     */
    public ParallelBinaryParser onBoundBox(final Consumer<BoundBox> onBoundBox) {
        this.boundBoxCb = onBoundBox;
        return this;
    }

    /**
     * Sets completion callback. This callback will be called on successful completion of parse.
     * It is guaranteed, that all the other callbacks will be finished before calling this one and they
     * will not be called after calling ths one.
     *
     * @param onComplete Callback function. May be null, in that case it will not be called.
     * @return ParallelBinaryParser to mimic builder interface.
     */
    public ParallelBinaryParser onComplete(final Runnable onComplete) {
        this.completeCb = onComplete;
        return this;
    }

    /**
     * Parses the OSM PBF file. This call will block until parsing is complete.
     *
     * During parsing procedure OSM PBF file will be read blob by blob into memory and
     * parsed in parallel using configured number of threads. During parse callbacks will be called.
     * Due to parallel nature of the parser those callback can be called simultaneously, so they must
     * be thread safe and reeenterable.
     *
     * In case of successful completion onComplete callback will be called and it is guaranteed, that
     * all previous callback call will be finished earlier and they will not be called after onComplete.
     *
     * There is no non-blocking version of that method, but you can safely run it in a separate runnable
     * for that purpose.
     *
     * @throws RuntimeException on processing error.
     */
    public void parse() {
        if (!tasksInFlight.isEmpty()) {
            throw new IllegalStateException("Previous parse call is still in progress");
        }

        executor = Executors.newFixedThreadPool(threads);
        currentDataBlock = 0;
        headerSeen = false;

        try {
            Optional<? extends Future<?>> blob;
            do {
                blob = reader.readBlobHeaderLength().flatMap(reader::readBlobHeader).flatMap(this::processDataBlob);
                blob.ifPresent(tasksInFlight::add);

                //We should remove completed tasks from time to time to not to increase our memory consumption
                Iterator<Future<?>> it = tasksInFlight.iterator();
                while (it.hasNext()) {
                    Future<?> f = it.next();
                    if (isDoneSuccessfully(f)) {
                        it.remove(); // O(1) for LinkedList
                    }
                }
            } while (blob.isPresent());

            //Wait for tasks completion
            for (Future<?> future : tasksInFlight) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("Parsing failed with: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            //In case of exception we would like to kill all the tasks immediately
            tasksInFlight.stream().filter(t -> !t.isDone()).forEach(t -> t.cancel(true));
            executor.shutdown();
            tasksInFlight.clear();
        }

        //Call completion callback.
        if (completeCb != null) {
            completeCb.run();
        }
    }

    /**
     * @param f the Future to be checked.
     * @return if the given Future executed successfully (not failed nor cancelled).
     * @throws ExecutionException if the Future failed.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    private boolean isDoneSuccessfully(final Future<?> f)  throws ExecutionException, InterruptedException {
        boolean done = f.isDone();
        if (done) {
            // In case of calculation error or cancellation this call will throw an Exception:
            f.get();
        }
        return done;
    }
}
