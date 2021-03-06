package org.kairosdb.core.http.rest.metrics;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.opentracing.Tracer;
import org.kairosdb.core.admin.InternalMetricsProvider;
import org.kairosdb.core.datastore.QueryMetric;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

public class DefaultQueryMeasurementProvider implements QueryMeasurementProvider, InternalMetricsProvider {
	private static final String MEASURES_PREFIX = "kairosdb.queries.";

	private final MetricRegistry metricRegistry;
	private final Histogram spanHistogramSuccess;
	private final Histogram distanceHistogramSuccess;

	private final Histogram spanHistogramError;
	private final Histogram distanceHistogramError;

	Tracer tracer;

	@Inject
	public DefaultQueryMeasurementProvider(@Nonnull final MetricRegistry metricRegistry,
										   @Nonnull final Tracer tracer) {
		checkNotNull(metricRegistry, "metricRegistry can't be null");
		this.metricRegistry = metricRegistry;
		this.tracer = tracer;

		spanHistogramSuccess = metricRegistry.histogram(MEASURES_PREFIX + "span.success");
		distanceHistogramSuccess = metricRegistry.histogram(MEASURES_PREFIX + "distance.success");

		spanHistogramError = metricRegistry.histogram(MEASURES_PREFIX + "span.error");
		distanceHistogramError = metricRegistry.histogram(MEASURES_PREFIX + "distance.error");
	}

	@Override
	public void measureSpanForMetric(final QueryMetric query) {
		if (canQueryBeReported(query)) {
			final Histogram histogram = metricRegistry.histogram(MEASURES_PREFIX + query.getName() + ".span");
			measureSpan(histogram, query);
		}
	}

	@Override
	public void measureDistanceForMetric(final QueryMetric query) {
		if (canQueryBeReported(query)) {
			final Histogram histogram = metricRegistry.histogram(MEASURES_PREFIX + query.getName() + ".distance");
			measureDistance(histogram, query);
		}
	}

	@Override
	public void measureSpanSuccess(final QueryMetric query) {
		measureSpan(spanHistogramSuccess, query);
	}

	@Override
	public void measureDistanceSuccess(final QueryMetric query) {
		measureDistance(distanceHistogramSuccess, query);
	}

	@Override
	public void measureSpanError(final QueryMetric query) {
		measureSpan(spanHistogramError, query);
	}

	@Override
	public void measureDistanceError(final QueryMetric query) {
		measureDistance(distanceHistogramError, query);
	}

	@Override
	public Map<String, Metric> getAll() {
		final Map<String, Metric> cacheMetrics = metricRegistry.getMetrics().entrySet().stream()
				.filter(e -> e.getKey() != null && e.getKey().startsWith(MEASURES_PREFIX))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		return ImmutableMap.copyOf(cacheMetrics);
	}

	@Override
	public Map<String, Metric> getForPrefix(@Nullable final String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			return getAll();
		}

		final Map<String, Metric> filteredMetrics = getAll().entrySet().stream()
				.filter(e -> e.getKey() != null && e.getKey().startsWith(MEASURES_PREFIX + "." + prefix))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		return ImmutableMap.copyOf(filteredMetrics);
	}

	private void measureSpan(final Histogram histogram, final QueryMetric query) {
		measureInternal(query.getStartTime(), query.getEndTime(), histogram, "query_span_in_days");
	}

	private void measureDistance(final Histogram histogram, final QueryMetric query) {
		measureInternal(query.getStartTime(), System.currentTimeMillis(), histogram, "query_distance_in_days");
	}

	private void measureInternal(final long startTime, final long endTime, final Histogram histogram, final String tag) {
		final long timeInMillis = endTime - startTime;
		final long timeInMinutes = timeInMillis / 1000 / 60;
		histogram.update(timeInMinutes);
		tracer.activeSpan().setTag(tag, timeInMinutes / 1440);
	}

	private boolean canQueryBeReported(final QueryMetric query) {
		return !query.getName().startsWith(MEASURES_PREFIX);
	}
}
