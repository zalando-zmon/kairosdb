/*
 * Copyright 2013 Proofpoint Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.kairosdb.datastore.cassandra;

import com.datastax.driver.core.*;
import com.google.common.collect.*;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import org.kairosdb.core.DataPoint;
import org.kairosdb.core.DataPointSet;
import org.kairosdb.core.KairosDataPointFactory;
import org.kairosdb.core.datapoints.LegacyDataPointFactory;
import org.kairosdb.core.datapoints.LongDataPointFactory;
import org.kairosdb.core.datastore.*;
import org.kairosdb.core.exception.DatastoreException;
import org.kairosdb.core.reporting.KairosMetricReporter;
import org.kairosdb.datastore.cassandra.cache.CacheWarmingUpConfiguration;
import org.kairosdb.datastore.cassandra.cache.CacheWarmingUpLogic;
import org.kairosdb.datastore.cassandra.cache.RowKeyCache;
import org.kairosdb.datastore.cassandra.cache.StringKeyCache;
import org.kairosdb.util.KDataOutput;
import org.kairosdb.util.MemoryMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.kairosdb.datastore.cassandra.cache.DefaultMetricNameCache.METRIC_NAME_CACHE;
import static org.kairosdb.datastore.cassandra.cache.DefaultTagNameCache.TAG_NAME_CACHE;

public class CassandraDatastore implements Datastore, KairosMetricReporter {
    public static final Logger logger = LoggerFactory.getLogger(CassandraDatastore.class);

    private static final String STRING_INDEX_INSERT = "INSERT INTO string_index (key, column1, value) VALUES (?, ?, 0x00) USING TTL ?";

    private static final String ROW_TIME_KEY_INDEX_SPLIT_INSERT = "INSERT INTO row_time_key_split_index (metric_name, tag_name, tag_value, column1, time_bucket) VALUES (?, ?, ?, ?, ?) USING TTL ?";
    private static final String ROW_TIME_KEY_INDEX_INSERT = "INSERT INTO row_time_key_index (key, column1, time_bucket) VALUES (?, ?, ?) USING TTL ?";

    private static final String DATA_POINTS_INSERT = "INSERT INTO data_points (key, column1, value) VALUES (?, ?, ?) USING TTL ?";


    private static final String QUERY_STRING_INDEX = "SELECT column1 FROM string_index WHERE key = ?";

    private static final String QUERY_ROW_TIME_KEY_SPLIT_INDEX = "SELECT column1 FROM row_time_key_split_index WHERE metric_name = ? AND tag_name = ? and tag_value = ? AND time_bucket = ? LIMIT ?";
    private static final String QUERY_ROW_TIME_KEY_INDEX = "SELECT column1 FROM row_time_key_index WHERE key = ? AND time_bucket = ? LIMIT ?";

    private static final String QUERY_DATA_POINTS = "SELECT column1, value FROM data_points WHERE key = ? AND column1 >= ? and column1 < ? ORDER BY column1 ASC";

    private static final int LONG_FLAG = 0x0;
    private static final int FLOAT_FLAG = 0x1;

    private static final DataPointsRowKeySerializer DATA_POINTS_ROW_KEY_SERIALIZER = new DataPointsRowKeySerializer();

    private final long m_rowWidthRead;
    private final long m_rowWidthWrite;

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String ROW_KEY_METRIC_NAMES = "metric_names";
    private static final ByteBuffer METRIC_NAME_BYTE_BUFFER = ByteBuffer.wrap(ROW_KEY_METRIC_NAMES.getBytes(UTF_8));

    private static final String ROW_KEY_TAG_NAMES = "tag_names";
    private static final ByteBuffer ROW_KEY_TAG_NAMES_BYTE_BUFFER = ByteBuffer.wrap(ROW_KEY_TAG_NAMES.getBytes(UTF_8));

    private final CassandraClient m_cassandraClient;
    private final Session m_session;

    private final PreparedStatement m_psInsertData;
    private final PreparedStatement m_psInsertRowTimeKey;
    private final PreparedStatement m_psInsertRowTimeKeySplit;
    private final PreparedStatement m_psInsertString;
    private final PreparedStatement m_psQueryStringIndex;
    private final PreparedStatement m_psQueryRowTimeKeyIndex;
    private final PreparedStatement m_psQueryRowTimeKeySplitIndex;
    private final PreparedStatement m_psQueryDataPoints;

    private final RowKeyCache rowKeyCache;
    private final StringKeyCache metricNameCache;
    private final StringKeyCache tagNameCache;

    private final KairosDataPointFactory m_kairosDataPointFactory;
    private final LongDataPointFactory m_longDataPointFactory;

    private final CacheWarmingUpLogic m_cacheWarmingUpLogic;
    private final CacheWarmingUpConfiguration m_cacheWarmingUpConfiguration;

    private final CassandraConsistencyLevelConfiguration m_cassandraConsistencyLevelConfiguration;

    private final List<String> m_indexTagList;
    private final ListMultimap<String, String> m_metricIndexTagMap;

    private CassandraConfiguration m_cassandraConfiguration;

    private final Random random = new Random();

    private final AtomicLong m_rowKeyIndexRowsInserted = new AtomicLong();
    private final AtomicLong m_nextRowKeyIndexRowsInserted = new AtomicLong();
    private final AtomicLong m_rowKeySplitIndexRowsInserted = new AtomicLong();
    private final AtomicLong m_readRowLimitExceededCount = new AtomicLong();
    private final AtomicLong m_filteredRowLimitExceededCount = new AtomicLong();
    @javax.inject.Inject
    @Named("HOSTNAME")
    private String hostName = "localhost";

    private Tracer tracer;

    @Inject
    public CassandraDatastore(CassandraClient cassandraClient,
                              CassandraConfiguration cassandraConfiguration,
                              KairosDataPointFactory kairosDataPointFactory,
                              LongDataPointFactory longDataPointFactory,
                              CacheWarmingUpLogic cacheWarmingUpLogic,
                              CacheWarmingUpConfiguration cacheWarmingUpConfiguration,
                              CassandraConsistencyLevelConfiguration cassandraConsistencyLevelConfiguration,
                              RowKeyCache rowKeyCache,
                              @Named(METRIC_NAME_CACHE) StringKeyCache metricNameCache,
                              @Named(TAG_NAME_CACHE) StringKeyCache tagNameCache,
                              Tracer tracer
    ) {
        m_cassandraConfiguration = cassandraConfiguration;
        this.rowKeyCache = rowKeyCache;
        this.metricNameCache = metricNameCache;
        this.tagNameCache = tagNameCache;

        logger.warn("Setting tag index: {}", cassandraConfiguration.getIndexTagList());
        m_indexTagList = parseIndexTagList(cassandraConfiguration.getIndexTagList());
        m_metricIndexTagMap = parseMetricIndexTagMap(cassandraConfiguration.getMetricIndexTagList());
        m_cassandraClient = cassandraClient;
        m_kairosDataPointFactory = kairosDataPointFactory;
        m_longDataPointFactory = longDataPointFactory;
        m_cacheWarmingUpLogic = cacheWarmingUpLogic;
        m_cacheWarmingUpConfiguration = cacheWarmingUpConfiguration;
        m_cassandraConsistencyLevelConfiguration = cassandraConsistencyLevelConfiguration;

        m_session = m_cassandraClient.getKeyspaceSession();

        m_psInsertData = m_session.prepare(DATA_POINTS_INSERT).setConsistencyLevel(cassandraConfiguration.getDataWriteLevelDataPoint());
        m_psInsertRowTimeKeySplit = m_session.prepare(ROW_TIME_KEY_INDEX_SPLIT_INSERT).setConsistencyLevel(cassandraConfiguration.getDataWriteLevelMeta());
        m_psInsertRowTimeKey = m_session.prepare(ROW_TIME_KEY_INDEX_INSERT).setConsistencyLevel(cassandraConfiguration.getDataWriteLevelMeta());
        m_psInsertString = m_session.prepare(STRING_INDEX_INSERT).setConsistencyLevel(cassandraConfiguration.getDataWriteLevelMeta());
        m_psQueryStringIndex = m_session.prepare(QUERY_STRING_INDEX).setConsistencyLevel(cassandraConfiguration.getDataReadLevel());
        m_psQueryRowTimeKeyIndex = m_session.prepare(QUERY_ROW_TIME_KEY_INDEX).setConsistencyLevel(cassandraConfiguration.getDataReadLevel());
        m_psQueryRowTimeKeySplitIndex = m_session.prepare(QUERY_ROW_TIME_KEY_SPLIT_INDEX).setConsistencyLevel(cassandraConfiguration.getDataReadLevel());
        m_psQueryDataPoints = m_session.prepare(QUERY_DATA_POINTS).setConsistencyLevel(cassandraConfiguration.getDataReadLevel());

        m_rowWidthRead = cassandraConfiguration.getRowWidthRead();
        m_rowWidthWrite = cassandraConfiguration.getRowWidthWrite();

        this.tracer = tracer;
    }

    long getRowWidthRead() {
        return m_rowWidthRead;
    }

    long getRowWidthWrite() {
        return m_rowWidthWrite;
    }

    private List<String> parseIndexTagList(final String indexTagList) {
        return Arrays.stream(indexTagList.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    static ListMultimap<String, String> parseMetricIndexTagMap(final String metricIndexTagList) {
        final ImmutableListMultimap.Builder<String, String> mapBuilder = ImmutableListMultimap.builder();
        for (final String metricsSetting : metricIndexTagList.split(";")) {
            String[] kv = metricsSetting.trim().split("=");
            if (kv.length != 2 || kv[0].isEmpty() || kv[1].isEmpty()) {
                continue;
            }
            Arrays.stream(kv[1].split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).forEach(v -> mapBuilder.put(kv[0], v));
        }
        return mapBuilder.build();
    }

    @Override
    public void close() {
        m_session.close();
        m_cassandraClient.close();
    }

    @Override
    public void putDataPoint(String metricName,
                             ImmutableSortedMap<String, String> tags,
                             DataPoint dataPoint,
                             int ttl) throws DatastoreException {
        try {
            Span span = GlobalTracer.get().activeSpan();
            //time the data is written.
            if (0 == ttl) {
                ttl = m_cassandraConfiguration.getDatapointTtl();
            }

            int rowKeyTtl = 0;
            //Row key will expire after configured ReadRowWidth
            if (ttl != 0) {
                rowKeyTtl = ttl + ((int) (m_rowWidthWrite / 1000));
            }

            final long rowTime = calculateRowTimeWrite(dataPoint.getTimestamp());
            final DataPointsRowKey dataPointsRowKey = new DataPointsRowKey(metricName, rowTime, dataPoint.getDataStoreDataType(), tags);
            final ByteBuffer serializedKey = DATA_POINTS_ROW_KEY_SERIALIZER.toByteBuffer(dataPointsRowKey);

            // Write out the row key if it is not cached
            final boolean rowKeyKnown = rowKeyCache.isKnown(serializedKey);
            if (!rowKeyKnown) {
                storeRowKeyReverseLookups(metricName, rowTime, serializedKey, rowKeyTtl, tags);
                if (span != null) {
                    span.setTag("cache_miss_row_key", Boolean.TRUE);
                }

                rowKeyCache.put(serializedKey);

                //Write metric name if not in cache
                if (!metricNameCache.isKnown(metricName)) {
                    if (span != null) {
                        span.setTag("cache_miss_metric_name", Boolean.TRUE);
                    }
                    if (metricName.length() == 0) {
                        logger.warn("Attempted to add empty metric name to string index. Row looks like: {}", dataPoint);
                    }
                    storeStringIndex(metricName, m_psInsertString, METRIC_NAME_BYTE_BUFFER);
                    metricNameCache.put(metricName);
                }

                //Check tag names and values to write them out
                for (final String tagName : tags.keySet()) {
                    if (!tagNameCache.isKnown(tagName)) {
                        if (span != null) {
                            span.setTag("cache_miss_tag_name", Boolean.TRUE);
                        }
                        if (tagName.length() == 0) {
                            logger.warn("Attempted to add empty tagName to string cache for metric: {}", metricName);
                        }
                        storeStringIndex(tagName, m_psInsertString, ROW_KEY_TAG_NAMES_BYTE_BUFFER);
                        tagNameCache.put(tagName);
                    }
                }
            }

            if (m_cacheWarmingUpConfiguration.isEnabled()) {
                long now = System.currentTimeMillis();
                int interval = m_cacheWarmingUpConfiguration.getHeatingIntervalMinutes();
                int rowSize = m_cacheWarmingUpConfiguration.getRowIntervalMinutes();
                final long nextRowTime = calculateRowTimeWrite(dataPoint.getTimestamp() + m_rowWidthWrite);
                final DataPointsRowKey nextBucketRowKey = new DataPointsRowKey(metricName, nextRowTime, dataPoint.getDataStoreDataType(), tags);
                if (m_cacheWarmingUpLogic.isWarmingUpNeeded(nextBucketRowKey.hashCode(), now, nextRowTime, interval, rowSize)) {
                    final ByteBuffer serializeNextKey = DATA_POINTS_ROW_KEY_SERIALIZER.toByteBuffer(nextBucketRowKey);
                    if (!rowKeyCache.isKnown(serializeNextKey)) {
                        storeRowKeyReverseLookups(metricName, nextRowTime, serializeNextKey, rowKeyTtl, tags);
                        rowKeyCache.put(serializeNextKey);
                        m_nextRowKeyIndexRowsInserted.incrementAndGet();
                    }
                }
            }

            int columnTime = getColumnName(rowTime, dataPoint.getTimestamp());
            KDataOutput kDataOutput = new KDataOutput();
            dataPoint.writeValueToBuffer(kDataOutput);

            BoundStatement boundStatement = new BoundStatement(m_psInsertData);
            boundStatement.setBytes(0, serializedKey);
            ByteBuffer b = ByteBuffer.allocate(4);
            b.putInt(columnTime);
            b.rewind();
            boundStatement.setBytes(1, b);
            boundStatement.setBytes(2, ByteBuffer.wrap(kDataOutput.getBytes()));
            boundStatement.setInt(3, ttl);
            m_session.executeAsync(boundStatement);
        } catch (Exception e) {
            logger.error("Failed to put data point for metric={} tags={} ttl={}", metricName, tags, ttl);
            throw new DatastoreException(e);
        }
    }

    private void storeStringIndex(String value, PreparedStatement m_psInsertString, ByteBuffer key) {
        BoundStatement bs = new BoundStatement(m_psInsertString);
        bs.setBytes(0, key);
        bs.setString(1, value);
        bs.setInt(2, m_cassandraConfiguration.getDatapointTtl());
        m_session.executeAsync(bs);
    }

    private void storeRowKeyReverseLookups(final String metricName, long rowTime, final ByteBuffer serializedKey, final int rowKeyTtl,
                                           final Map<String, String> tags) {
        final BoundStatement bs = new BoundStatement(m_psInsertRowTimeKey);
        bs.setBytes(0, ByteBuffer.wrap(metricName.getBytes(UTF_8)));
        bs.setBytes(1, serializedKey);
        bs.setLong(2, rowTime);
        bs.setInt(3, rowKeyTtl);

        m_session.executeAsync(bs);
        m_rowKeyIndexRowsInserted.incrementAndGet();

        final List<String> indexTags = getIndexTags(metricName);
        for (String split : indexTags) {
            String v = tags.get(split);
            if (null == v || "".equals(v)) {
                continue;
            }
            storeRowKeySplit(metricName, rowTime, serializedKey, rowKeyTtl, split, v);
        }
    }

    private void storeRowKeySplit(final String metricName,
                                  long rowTime, final ByteBuffer serializedKey, final int rowKeyTtl,
                                  final String splitTagName, final String splitTagValue) {
        final BoundStatement bs = new BoundStatement(m_psInsertRowTimeKeySplit);
        bs.setString(0, metricName);
        bs.setString(1, splitTagName);
        bs.setString(2, splitTagValue);
        bs.setBytes(3, serializedKey);
        bs.setLong(4, rowTime);
        bs.setInt(5, rowKeyTtl);

        m_session.executeAsync(bs);
        m_rowKeySplitIndexRowsInserted.incrementAndGet();
    }

    private Iterable<String> queryStringIndex(final String key) {

        BoundStatement bs = m_psQueryStringIndex.setConsistencyLevel(m_cassandraConsistencyLevelConfiguration.getReadLevel()).bind();
        bs.setBytes(0, ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8)));

        ResultSet rs = m_session.execute(bs);

        List<String> ret = new ArrayList<>();
        for (Row r : rs) {
            ret.add(r.getString("column1"));
        }

        return ret;
    }

    @Override
    public Iterable<String> getMetricNames() {
        return queryStringIndex(ROW_KEY_METRIC_NAMES);
    }

    @Override
    public Iterable<String> getTagNames() {
        return queryStringIndex(ROW_KEY_TAG_NAMES);
    }

    @Override
    public TagSet queryMetricTags(DatastoreMetricQuery query) {
        TagSetImpl tagSet = new TagSetImpl();
        Collection<DataPointsRowKey> rowKeys =
                getKeysForQueryIterator(query, m_cassandraConfiguration.getMaxRowsForKeysQuery() + 1);

        MemoryMonitor mm = new MemoryMonitor(20);
        for (DataPointsRowKey key : rowKeys) {
            for (Map.Entry<String, String> tag : key.getTags().entrySet()) {
                tagSet.addTag(tag.getKey(), tag.getValue());
                mm.checkMemoryAndThrowException();
            }
        }

        return (tagSet);
    }

    @Override
    public void queryDatabase(DatastoreMetricQuery query, QueryCallback queryCallback) {
        queryWithRowKeys(query, queryCallback, getKeysForQueryIterator(query));
    }

    private void queryWithRowKeys(DatastoreMetricQuery query,
                                  QueryCallback queryCallback, Collection<DataPointsRowKey> rowKeys) {
        String currentType = null;

        List<CQLQueryRunner> runners = new ArrayList<>();
        List<DataPointsRowKey> queryKeys = new ArrayList<>();

        PreparedStatement queryDataPoints = m_psQueryDataPoints.setConsistencyLevel(m_cassandraConsistencyLevelConfiguration.getReadLevel());

        MemoryMonitor mm = new MemoryMonitor(20);

        List<DataPointsRowKey> sorted = new ArrayList<>(rowKeys);
        sorted.sort(Comparator.comparingLong(DataPointsRowKey::getTimestamp));
        rowKeys = sorted;

        Span span = tracer.buildSpan("query_datapoints").start();

        try (Scope scope = tracer.scopeManager().activate(span, false)) {

            span.setTag("keys", rowKeys.size());

            if (rowKeys.size() < 64) {
                queryKeys.addAll(rowKeys);
            } else {
                long currentTimeTier = 0L;

                for (DataPointsRowKey rowKey : rowKeys) {
                    if (currentTimeTier == 0L)
                        currentTimeTier = rowKey.getTimestamp();

                    if (currentType == null)
                        currentType = rowKey.getDataType();

                    if ((rowKey.getTimestamp() == currentTimeTier) &&
                            (currentType.equals(rowKey.getDataType()))) {
                        queryKeys.add(rowKey);
                    } else {
                        // logger.info("Creating new query runner: metric={} size={} ts-delta={}", queryKeys.get(0).getMetricName(), queryKeys.size(), currentTimeTier - rowKey.getTimestamp());
                        runners.add(new CQLQueryRunner(m_session, queryDataPoints, m_kairosDataPointFactory,
                                queryKeys,
                                query.getStartTime(), query.getEndTime(), m_rowWidthRead, queryCallback, query.getLimit(), query.getOrder()));

                        queryKeys = new ArrayList<>();
                        queryKeys.add(rowKey);
                        currentTimeTier = rowKey.getTimestamp();
                        currentType = rowKey.getDataType();
                    }
                    mm.checkMemoryAndThrowException();
                }

                if (random.nextInt(100) == 42) {
                    m_cassandraClient.logConnectionStats(m_session);
                }
            }

            //There may be stragglers that are not ran
            if (!queryKeys.isEmpty()) {
                // logger.info("Creating new runner for remaining keys: metric={} size={}", queryKeys.get(0).getMetricName(), queryKeys.size());
                runners.add(new CQLQueryRunner(m_session, queryDataPoints, m_kairosDataPointFactory,
                        queryKeys,
                        query.getStartTime(), query.getEndTime(), m_rowWidthRead, queryCallback, query.getLimit(), query.getOrder()));
            }

            //Changing the check rate
            mm.setCheckRate(1);
            try {
                // FIXME: Run this with multiple threads - not easily possible with how QueryCallback behaves
                for (CQLQueryRunner runner : runners) {
                    runner.runQuery();

                    mm.checkMemoryAndThrowException();
                }

                queryCallback.endDataPoints();
            } catch (IOException e) {
                Tags.ERROR.set(span, Boolean.TRUE);
                span.log(e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            Tags.ERROR.set(span, Boolean.TRUE);
            span.log(e.getMessage());
            throw e;
        } finally {
            span.finish();
        }
    }

    @Override
    @SuppressWarnings({"unused", "UnusedAssignment", "StatementWithEmptyBody"})
    public void deleteDataPoints(DatastoreMetricQuery deleteQuery) {
        checkNotNull(deleteQuery);

        boolean deleteAll = false;
        if (deleteQuery.getStartTime() == Long.MIN_VALUE && deleteQuery.getEndTime() == Long.MAX_VALUE)
            deleteAll = true;

        Iterator<DataPointsRowKey> rowKeyIterator = getKeysForQueryIterator(deleteQuery).iterator();
        List<DataPointsRowKey> partialRows = new ArrayList<>();

        while (rowKeyIterator.hasNext()) {
            DataPointsRowKey rowKey = rowKeyIterator.next();
            long rowKeyTimestamp = rowKey.getTimestamp();
            // check which width to use
            if (deleteQuery.getStartTime() <= rowKeyTimestamp && (deleteQuery.getEndTime() >= rowKeyTimestamp + m_rowWidthRead - 1)) {
                // fix me
                //m_dataPointWriteBuffer.deleteRow(rowKey, now);  // delete the whole row
                //m_rowKeyWriteBuffer.deleteColumn(rowKey.getMetricName(), rowKey, now); // Delete the index
                // m_rowKeyCache.clear();
            } else {
                partialRows.add(rowKey);
            }
        }

        queryWithRowKeys(deleteQuery, new DeletingCallback(deleteQuery.getName()), partialRows);

        // If index is gone, delete metric name from Strings column family
        //  if (deleteAll) {
            //m_rowKeyWriteBuffer.deleteRow(deleteQuery.getName(), now);
            // fix me
            //m_stringIndexWriteBuffer.deleteColumn(ROW_KEY_METRIC_NAMES, deleteQuery.getName(), now);
            // m_rowKeyCache.clear();
            // m_metricNameCache.clear();
        // }
    }

    @Override
    public List<DataPointSet> getMetrics(long now) {
        return Arrays.asList(
                getDataPointSet(now, m_rowKeyIndexRowsInserted, "kairosdb.inserted.row_key_index"),
                getDataPointSet(now, m_nextRowKeyIndexRowsInserted, "kairosdb.inserted.next_row_key_index"),
                getDataPointSet(now, m_rowKeySplitIndexRowsInserted, "kairosdb.inserted.row_key_split_index"),
                getDataPointSet(now, m_readRowLimitExceededCount, "kairosdb.limits.read_rows_exceeded"),
                getDataPointSet(now, m_filteredRowLimitExceededCount, "kairosdb.limits.filtered_rows_exceeded")
        );
    }

    @SuppressWarnings("Duplicates")
    private DataPointSet getDataPointSet(long now, AtomicLong counter, String name) {
        final long metric = counter.getAndSet(0);
        final DataPointSet dataPointSet = new DataPointSet(name);
        dataPointSet.addTag("host", hostName);
        dataPointSet.addDataPoint(m_longDataPointFactory.createDataPoint(now, metric));
        return dataPointSet;
    }

    Collection<DataPointsRowKey> getKeysForQueryIterator(DatastoreMetricQuery query) {
        return getKeysForQueryIterator(query, m_cassandraConfiguration.getMaxRowsForKeysQuery() + 1);
    }

    /**
     * Returns the row keys for the query in tiers ie grouped by row key timestamp
     *
     * @param query query
     * @return row keys for the query
     */
    private Collection<DataPointsRowKey> getKeysForQueryIterator(DatastoreMetricQuery query, int limit) {
        Collection<DataPointsRowKey> ret = null;

        List<QueryPlugin> plugins = query.getPlugins();

        //First plugin that works gets it.
        for (QueryPlugin plugin : plugins) {
            if (plugin instanceof CassandraRowKeyPlugin) {
                ret = ((CassandraRowKeyPlugin) plugin).getKeysForQueryIterator(query);
                break;
            }
        }

        //Default to old behavior if no plugin was provided
        if (ret == null) {
            ret = getMatchingRowKeys(query, limit);
        }

        return ret;
    }

    long calculateRowTimeRead(long timestamp) {
        return (timestamp - (Math.abs(timestamp) % m_rowWidthRead));
    }

    long calculateRowTimeWrite(long timestamp) {
        return (timestamp - (Math.abs(timestamp) % m_rowWidthWrite));
    }

    private List<Long> calculateReadTimeBuckets(long startTimeBucket, long endTimeBucket) {
        LinkedList<Long> buckets = new LinkedList<>();

        for (long i = startTimeBucket; i <= endTimeBucket; i += m_rowWidthRead) {
            buckets.add(i);
        }
        return buckets;
    }

    /**
     * This is just for the delete operation of old data points.
     */
    private static int getColumnName(long rowTime, long timestamp, boolean isInteger) {
        int ret = (int) (timestamp - rowTime);

        if (isInteger) {
            return ((ret << 1) | LONG_FLAG);
        } else {
            return ((ret << 1) | FLOAT_FLAG);
        }
    }

    static int getColumnName(long rowTime, long timestamp) {
        int ret = (int) (timestamp - rowTime);

		/*
            The timestamp is shifted to support legacy datapoints that
			used the extra bit to determine if the value was long or double
		 */
        return (ret << 1);
    }

    static long getColumnTimestamp(long rowTime, int columnName) {
        return (rowTime + (long) (columnName >>> 1));
    }

    static boolean isLongValue(int columnName) {
        return ((columnName & 0x1) == LONG_FLAG);
    }

    private static final Pattern GLOB_PATTERN = Pattern.compile("[?*]");

    /**
     * Convert a GLOB style pattern ("*" for any number of any char, "?" for exactly one char) to a regex pattern.
     * <p>
     * Code borrowed from Spring's AntPathMatcher.java (Apache 2 license)
     */
    static Pattern convertGlobToPattern(String pattern) {
        StringBuilder patternBuilder = new StringBuilder();
        Matcher matcher = GLOB_PATTERN.matcher(pattern);
        int end = 0;
        while (matcher.find()) {
            patternBuilder.append(quote(pattern, end, matcher.start()));
            String match = matcher.group();
            if ("?".equals(match)) {
                patternBuilder.append('.');
            } else if ("*".equals(match)) {
                patternBuilder.append(".*");
            }
            end = matcher.end();
        }
        patternBuilder.append(quote(pattern, end, pattern.length()));
        return Pattern.compile(patternBuilder.toString());
    }

    private static String quote(String s, int start, int end) {
        if (start == end) {
            return "";
        }
        return Pattern.quote(s.substring(start, end));
    }

    /**
     * Return whether the given input value matches any of the given GLOB-style patterns.
     */
    static boolean matchesAny(final String value, final Collection<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    private int filterAndAddKeys(DatastoreMetricQuery query, ResultSet rs, List<DataPointsRowKey> filteredRowKeys, String index) {
        final DataPointsRowKeySerializer keySerializer = new DataPointsRowKeySerializer();
        final SetMultimap<String, String> filterTags = query.getTags();
        final SetMultimap<String, Pattern> tagPatterns = MultimapBuilder.hashKeys(filterTags.size()).hashSetValues().build();
        for (Map.Entry<String, String> entry : filterTags.entries()) {
            tagPatterns.put(entry.getKey(), convertGlobToPattern(entry.getValue()));
        }
        int rowReadCount = 0;
        for (Row r : rs) {
            rowReadCount++;

            checkReadRowsLimit(rowReadCount, filteredRowKeys.size(), m_cassandraConfiguration.getMaxRowsForKeysQuery(), query, index);

            DataPointsRowKey key = keySerializer.fromByteBuffer(r.getBytes("column1"));
            Map<String, String> tags = key.getTags();

            boolean skipKey = false;
            for (String tag : filterTags.keySet()) {
                String value = tags.get(tag);
                if (value == null || !matchesAny(value, tagPatterns.get(tag))) {
                    skipKey = true;
                    break;
                }
            }
            if (!skipKey) {
                filteredRowKeys.add(key);
            }
        }

        return rowReadCount;
    }

    private void checkReadRowsLimit(int readCount, int filteredCount, int limit, DatastoreMetricQuery query, String index) {
        if (readCount > limit) {
            logLimitViolation(readCount, filteredCount, limit, query, index, m_readRowLimitExceededCount);
            throw new MaxRowKeysForQueryExceededException(
                    String.format("Exceeded limit: %d key rows read by KDB. Metric: %s", limit, query.getName()));
        }
    }

    private void checkFilteredRowsLimit(int readCount, int filteredCount, int limit, DatastoreMetricQuery query, String index) {
        if (filteredCount > limit) {
            logLimitViolation(readCount, filteredCount, limit, query, index, m_filteredRowLimitExceededCount);
            throw new MaxRowKeysForQueryExceededException(
                    String.format("Exceeded limit: %d data point partitions read by KDB. Metric: %s", limit, query.getName()));
        }
    }

    private static void logLimitViolation(int readCount, int filteredCount, int limit, DatastoreMetricQuery query, String index, AtomicLong counter) {
        Span span = GlobalTracer.get().activeSpan();
        if (span != null) {
            span.setTag("read_count", readCount);
            span.setTag("filtered_count", filteredCount);
            span.setTag("max_row_keys", Boolean.TRUE);
        }
        final boolean isUntilNow = System.currentTimeMillis() - query.getEndTime() <= 30_000;
        final long duration = query.getEndTime() - query.getStartTime();
        logger.warn("query_limit_violation: metric={} query={} read={} filtered={} start_time={} end_time={} " +
                        "duration={} is_until_now={} exceeded={} limit={} index={}",
                query.getName(), query.getTags(), readCount, filteredCount, query.getStartTime(), query.getEndTime(),
                duration, isUntilNow, true, limit, index);

        counter.incrementAndGet();
    }

    private List<String> getIndexTags(String metricName) {
        return m_metricIndexTagMap.containsKey(metricName) ? m_metricIndexTagMap.get(metricName) : m_indexTagList;
    }

    private List<DataPointsRowKey> getMatchingRowKeys(DatastoreMetricQuery query, int limit) {
        // determine whether to use split index or not
        String useSplitField = null;
        Set<String> useSplitSet = new HashSet<>();

        Span span = tracer.buildSpan("query_index").start();

        try (Scope scope = tracer.scopeManager().activate(span, false)) {

            final List<String> indexTags = getIndexTags(query.getName());
            SetMultimap<String, String> filterTags = query.getTags();
            for (String split : indexTags) {
                if (filterTags.containsKey(split)) {
                    Set<String> currentSet = filterTags.get(split);
                    final boolean currentSetIsSmaller = currentSet.size() < useSplitSet.size();
                    final boolean currentSetIsNotEmpty = currentSet.size() > 0 && useSplitSet.isEmpty();
                    final boolean currentSetHasNoWildcards = currentSet.stream().noneMatch(x -> x.contains("*") || x.contains("?"));
                    if ((currentSetIsSmaller || currentSetIsNotEmpty) && currentSetHasNoWildcards) {
                        useSplitSet = currentSet;
                        useSplitField = split;
                    }
                }
            }
            List<String> useSplit = new ArrayList<>(useSplitSet);

            long startTime = calculateRowTimeRead(query.getStartTime());
            // Use write width here, as END time is upper bound for query and end with produces the bigger timestamp
            long endTime = calculateRowTimeWrite(query.getEndTime());
            logger.info("calculated: s={} e={}", startTime, endTime);

            if (useSplitField != null && !"".equals(useSplitField) && useSplitSet.size() > 0) {
                span.setTag("type", "split");
                return getMatchingRowKeysFromSplitIndex(query, useSplitField, useSplit, startTime, endTime, limit);
            } else {
                span.setTag("type", "global");
                return getMatchingRowKeysFromRegularIndex(query, startTime, endTime, limit);
            }
        } catch (Exception e) {
            Tags.ERROR.set(span, Boolean.TRUE);
            span.log(e.getMessage());
            throw e;
        } finally {
            span.finish();
        }
    }

    private List<DataPointsRowKey> getMatchingRowKeysFromRegularIndex(DatastoreMetricQuery query,
                                                                      long startTime, long endTime, int limit) {
        final ByteBuffer metricName = ByteBuffer.wrap(query.getName().getBytes(StandardCharsets.UTF_8));

        final String index = "row_time_key_index";
        List<ResultSetFuture> futures = calculateReadTimeBuckets(startTime, endTime)
                .stream()
                .map(bucket -> collectFromRowTimeKeyIndexAsync(metricName, bucket, limit))
                .collect(Collectors.toList());

        return processRowKeyFutures(query, index, futures);
    }

    private List<DataPointsRowKey> processRowKeyFutures(DatastoreMetricQuery query, String index, List<ResultSetFuture> futures) {
        final List<DataPointsRowKey> rowKeys = new LinkedList<>();
        Span span = GlobalTracer.get().activeSpan();
        if (span != null) {
            span.setTag("index_name", index);
            span.setTag("buckets", futures.size());
        }

        int readCount = 0;
        for (ResultSetFuture future : futures) {
            final ResultSet rs = future.getUninterruptibly();
            readCount += filterAndAddKeys(query, rs, rowKeys, index);

            final int filteredLimit = m_cassandraConfiguration.getMaxRowKeysForQuery();
            checkFilteredRowsLimit(readCount, rowKeys.size(), filteredLimit, query, index);
        }

        final boolean isCriticalQuery = readCount > 5000 || rowKeys.size() > 100;
        query.setMeta(new DatastoreMetricQueryMetadata(
                isCriticalQuery ? "critical" : "simple",
                readCount,
                index,
                isCriticalQuery || random.nextInt(100) < m_cassandraConfiguration.getQuerySamplingPercentage()
        ));

        return rowKeys;
    }

    private List<DataPointsRowKey> getMatchingRowKeysFromSplitIndex(DatastoreMetricQuery query,
                                                                    String useSplitField,
                                                                    List<String> useSplit,
                                                                    long startTime, long endTime, int limit) {
        String index = "row_time_key_split_index:" + useSplitField;
        List<ResultSetFuture> futures = calculateReadTimeBuckets(startTime, endTime)
                .stream()
                .flatMap(bucket -> useSplit.stream().map(useSplitValue -> collectFromRowTimeKeySplitIndexAsync(query, useSplitField, useSplitValue, bucket, limit)))
                .collect(Collectors.toList());

        return processRowKeyFutures(query, index, futures);
    }

    private ResultSetFuture collectFromRowTimeKeySplitIndexAsync(final DatastoreMetricQuery query,
                                                                 final String useSplitField,
                                                                 final String useSplitValue,
                                                                 final long bucket,
                                                                 final int limit) {
        final String metricName = query.getName();

        final BoundStatement bs = m_psQueryRowTimeKeySplitIndex.setConsistencyLevel(m_cassandraConsistencyLevelConfiguration.getReadLevel()).bind();
        bs.setString(0, metricName);
        bs.setString(1, useSplitField);
        bs.setString(2, useSplitValue);
        bs.setLong(3, bucket);
        bs.setInt(4, limit);

        return m_session.executeAsync(bs);
    }

    private ResultSetFuture collectFromRowTimeKeyIndexAsync(ByteBuffer metricName, Long bucket,
                                                            final int limit) {
        final BoundStatement bs = m_psQueryRowTimeKeyIndex.setConsistencyLevel(m_cassandraConsistencyLevelConfiguration.getReadLevel()).bind();
        bs.setBytes(0, metricName);
        bs.setLong(1, bucket);
        bs.setInt(2, limit);

        return m_session.executeAsync(bs);
    }

    private class DeletingCallback implements QueryCallback {
        private SortedMap<String, String> m_currentTags;
        private DataPointsRowKey m_currentRow;
//        private long m_now = System.currentTimeMillis();
        private final String m_metric;
        private String m_currentType;

        DeletingCallback(String metric) {
            m_metric = metric;
        }

        @Override
        @SuppressWarnings({"UnusedAssignment", "unused"})
        public void addDataPoint(DataPoint datapoint) {
            long time = datapoint.getTimestamp();
            long rowTime = calculateRowTimeWrite(time);
            if (m_currentRow == null) {
                m_currentRow = new DataPointsRowKey(m_metric, rowTime, m_currentType, m_currentTags);
            }

            int columnName;
            //Handle old column name format.
            //We get the type after it has been translated from "" to kairos_legacy
            if (m_currentType.equals(LegacyDataPointFactory.DATASTORE_TYPE)) {
                columnName = getColumnName(rowTime, time, datapoint.isLong());
            } else {
                columnName = getColumnName(rowTime, time);
            }

            // fix me
            //m_dataPointWriteBuffer.deleteColumn(m_currentRow, columnName, m_now);
        }

        @Override
        public void startDataPointSet(String dataType, Map<String, String> tags) {
            m_currentType = dataType;
            m_currentTags = new TreeMap<>(tags);
            //This causes the row key to get reset with the first data point
            m_currentRow = null;
        }

        @Override
        public void endDataPoints() {
        }
    }
}
