/*
 * Copyright (c) 2025, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.ballerina.observe.metrics.moesif;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.PredefinedTypes;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.observability.metrics.Counter;
import io.ballerina.runtime.observability.metrics.DefaultMetricRegistry;
import io.ballerina.runtime.observability.metrics.Gauge;
import io.ballerina.runtime.observability.metrics.Metric;
import io.ballerina.runtime.observability.metrics.MetricConstants;
import io.ballerina.runtime.observability.metrics.PercentileValue;
import io.ballerina.runtime.observability.metrics.PolledGauge;
import io.ballerina.runtime.observability.metrics.Snapshot;
import io.ballerina.runtime.observability.metrics.Tag;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Moesif metrics reporter for publishing Ballerina metrics to Moesif platform.
 * This class handles metric collection, formatting, and batch publishing.
 */
public class MoesifMetricReporter {

    // Configuration constants
    private static final String DEFAULT_ENDPOINT_PATH = "/v1/actions/batch";
    private static final String APP_ID_HEADER = "X-Moesif-Application-Id";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final int SCHEDULE_EXECUTOR_INITIAL_DELAY = 0;
    private static final int HTTP_SUCCESS_MIN = 200;
    private static final int HTTP_SUCCESS_MAX = 299;
    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_VALUE_LENGTH = 1000;

    // Logging
    private static final Logger logger = Logger.getLogger(MoesifMetricReporter.class.getName());

    // Thread-safe components
    private static volatile ScheduledExecutorService executor;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final AtomicLong sessionCounter = new AtomicLong(0);
    private static HttpClient httpClient;
    private static Duration httpTimeout = Duration.ofSeconds(30);

    // Cache processed additional attributes to avoid repeated processing
    private static volatile Map<String, String> cachedAdditionalAttributes = Collections.emptyMap();
    private static final Object attributesCacheLock = new Object();

    // Snapshot of metric values from the previous reporting cycle
    private static final Map<String, Double> previousMetricSnapshot = new ConcurrentHashMap<>();

    // Metric names used to determine whether new request activity has occurred
    private static final String ACTIVITY_COUNTER_NAME = "requests_total";
    private static final String INPROGRESS_GAUGE_NAME = "inprogress_requests";

    /**
     * Initializes and starts the metrics reporting service.
     *
     * @param reporterBaseUrl             Base URL for the Moesif API
     * @param applicationId               Moesif application ID
     * @param metricReporterFlushInterval Interval between metric reports in
     *                                    milliseconds
     * @param metricReporterClientTimeout HTTP client timeout (currently unused but
     *                                    kept for API compatibility)
     * @param isTraceLoggingEnabled       Enable trace level logging
     * @param isPayloadLoggingEnabled     Enable payload logging
     * @param idleTimePublishingEnabled   Enable publishing metrics even when no new activity is detected
     * @param additionalAttributes        Additional metadata attributes to include
     * @return Array containing status messages
     */
    public static BArray sendMetrics(BString reporterBaseUrl, BString applicationId,
            int metricReporterFlushInterval, int metricReporterClientTimeout,
            boolean isTraceLoggingEnabled, boolean isPayloadLoggingEnabled,
            boolean idleTimePublishingEnabled,
            BMap<BString, BString> additionalAttributes) {

        validateInputs(reporterBaseUrl, applicationId, metricReporterFlushInterval);
        BArray output = ValueCreator.createArrayValue(
                TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));

        // Process and cache additional attributes once
        synchronized (attributesCacheLock) {
            cachedAdditionalAttributes = processAdditionalAttributes(additionalAttributes);
        }

        // Set HTTP timeout and client
        if (metricReporterClientTimeout > 0) {
            httpTimeout = Duration.ofMillis(metricReporterClientTimeout);
        } else {
            httpTimeout = Duration.ofSeconds(30);
        }
        httpClient = HttpClient.newBuilder()
                .connectTimeout(httpTimeout)
                .build();
        configureLogging(isTraceLoggingEnabled);

        try {
            startMetricReporting(reporterBaseUrl, applicationId, metricReporterFlushInterval,
                    isPayloadLoggingEnabled, idleTimePublishingEnabled);

            String successMessage = String.format(
                    "Started publishing metrics to Moesif endpoint: %s%s with %d additional attributes",
                    reporterBaseUrl.getValue(), DEFAULT_ENDPOINT_PATH, cachedAdditionalAttributes.size());
            output.append(StringUtils.fromString(successMessage));

            logger.fine(successMessage);

        } catch (Exception e) {
            String errorMessage = "Failed to start Moesif metrics reporter: " + e.getMessage();
            logger.severe(errorMessage);
            output.append(StringUtils.fromString(errorMessage));
        }

        return output;
    }

    /**
     * Process and validate additional attributes once at startup.
     */
    private static Map<String, String> processAdditionalAttributes(BMap<BString, BString> additionalAttributes) {
        if (additionalAttributes == null || additionalAttributes.isEmpty()) {
            logger.fine("No additional attributes provided");
            return Collections.emptyMap();
        }

        Map<String, String> processed = new HashMap<>();
        int processedCount = 0;
        int skippedCount = 0;

        try {
            for (BString key : additionalAttributes.getKeys()) {
                if (key != null && key.getValue() != null) {
                    BString value = additionalAttributes.get(key);
                    if (value != null && value.getValue() != null) {
                        // Sanitize key and value
                        String sanitizedKey = sanitizeMetadataKey(key.getValue());
                        String sanitizedValue = sanitizeMetadataValue(value.getValue());

                        if (!sanitizedKey.isEmpty() && !sanitizedValue.isEmpty()) {
                            processed.put(sanitizedKey, sanitizedValue);
                            processedCount++;
                        } else {
                            skippedCount++;
                            logger.warning(String.format("Skipped invalid additional attribute: key='%s', value='%s'",
                                key.getValue(), value.getValue()));
                        }
                    } else {
                        skippedCount++;
                        logger.warning(String.format("Skipped additional attribute with null value: key='%s'",
                            key.getValue()));
                    }
                } else {
                    skippedCount++;
                    logger.warning("Skipped additional attribute with null key");
                }
            }

            logger.fine(String.format("Processed additional attributes: %d valid, %d skipped",
                processedCount, skippedCount));

        } catch (Exception e) {
            logger.warning("Error processing additional attributes: " + e.getMessage());
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(processed);
    }

    /**
     * Validates input parameters.
     */
    private static void validateInputs(BString reporterBaseUrl, BString applicationId,
            int metricReporterFlushInterval) {
        if (reporterBaseUrl == null || reporterBaseUrl.getValue().trim().isEmpty()) {
            throw new IllegalArgumentException("Reporter base URL cannot be null or empty");
        }
        if (applicationId == null || applicationId.getValue().trim().isEmpty()) {
            throw new IllegalArgumentException("Application ID cannot be null or empty");
        }
        if (metricReporterFlushInterval <= 0) {
            throw new IllegalArgumentException("Metric reporter flush interval must be positive");
        }
    }

    /**
     * Configures logging based on trace settings.
     */
    private static void configureLogging(boolean isTraceLoggingEnabled) {
        if (isTraceLoggingEnabled) {
            logger.setLevel(Level.FINE);
            logger.fine("Trace logging enabled for Moesif metrics reporter");
        }
    }

    /**
     * Starts the metric reporting scheduler.
     */
    private static void startMetricReporting(BString reporterBaseUrl, BString applicationId,
            int metricReporterFlushInterval, boolean isPayloadLoggingEnabled, boolean idleTimePublishingEnabled) {
        executor = getOrCreateExecutor();
        executor.scheduleAtFixedRate(() -> {
            try {
                Metric[] metrics = DefaultMetricRegistry.getInstance().getAllMetrics();

                if (metrics.length == 0) {
                    logger.fine("No metrics to report");
                    return;
                }

                // Capture whether activity was detected.
                boolean hasActivity = hasNewActivity(metrics);
                if (!idleTimePublishingEnabled && !hasActivity) {
                    if (isPayloadLoggingEnabled) {
                        logger.fine("Skipping metric publish: no new counter activity since last cycle");
                    }
                    return;
                }

                if (isPayloadLoggingEnabled) {
                    logger.fine(String.format("Reporting %d metrics", metrics.length));
                }

                publishMetricsBatch(reporterBaseUrl, applicationId, metrics, isPayloadLoggingEnabled);

            } catch (Exception e) {
                logger.severe("Error in metric reporting task: " + e.getMessage());
            }
        }, SCHEDULE_EXECUTOR_INITIAL_DELAY, metricReporterFlushInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * Compares requests_total and inprogress_requests metrics against the
     * previous cycle's snapshot to determine whether new request activity has occurred.
     *
     * @param metrics all metrics from the registry
     * @return {@code true} if any tracked metric changed value, or no tracked metrics exist
     */
    private static boolean hasNewActivity(Metric[] metrics) {
        boolean hasTrackedMetrics = false;
        boolean activityDetected = false;
        Map<String, Double> currentSnapshot = new HashMap<>();

        for (Metric metric : metrics) {
            String name = metric.getId().getName();

            if (metric instanceof Counter counter && name.endsWith(ACTIVITY_COUNTER_NAME)) {
                hasTrackedMetrics = true;
                String key = "counter:" + name + counter.getId().getTags().toString();
                double currentValue = convertToDouble(counter.getValue());
                currentSnapshot.put(key, currentValue);

                Double previousValue = previousMetricSnapshot.get(key);
                if (previousValue == null || Double.compare(previousValue, currentValue) != 0) {
                    activityDetected = true;
                }

            } else if (metric instanceof Gauge gauge && name.endsWith(INPROGRESS_GAUGE_NAME)) {
                hasTrackedMetrics = true;
                String key = "gauge:" + name + gauge.getId().getTags().toString();
                double currentValue = convertToDouble(gauge.getValue());
                currentSnapshot.put(key, currentValue);

                Double previousValue = previousMetricSnapshot.get(key);
                if (previousValue == null || Double.compare(previousValue, currentValue) != 0) {
                    activityDetected = true;
                }
            }
        }

        // Update snapshot for the next cycle
        previousMetricSnapshot.clear();
        previousMetricSnapshot.putAll(currentSnapshot);

        // If neither tracked metric is registered, publish safely — cannot determine activity
        return !hasTrackedMetrics || activityDetected;
    }

    /**
     * Gets or creates the executor service in a thread-safe manner.
     */
    private static ScheduledExecutorService getOrCreateExecutor() {
        if (executor == null || executor.isShutdown()) {
            synchronized (MoesifMetricReporter.class) {
                if (executor == null || executor.isShutdown()) {
                    executor = Executors.newScheduledThreadPool(1, r -> {
                        Thread t = new Thread(r, "moesif-metrics-reporter");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return executor;
    }

    /**
     * Publishes a batch of metrics to Moesif.
     */
    static void publishMetricsBatch(BString reporterBaseUrl, BString applicationId,
            Metric[] metrics, boolean isPayloadLoggingEnabled) {
        try {
            ArrayNode actionsArray = createActionsFromMetrics(metrics);

            if (actionsArray.isEmpty()) {
                logger.fine("No actions generated from metrics");
                return;
            }

            String jsonPayload = objectMapper.writeValueAsString(actionsArray);

            if (isPayloadLoggingEnabled) {
                logger.fine("Metric payload: " + jsonPayload);
            }

            sendHttpRequest(reporterBaseUrl, applicationId, jsonPayload, metrics.length);

        } catch (Exception e) {
            logger.severe("Failed to publish metrics batch: " + e.getMessage());
        }
    }

    /**
     * Creates Moesif actions from metrics array.
     */
    private static ArrayNode createActionsFromMetrics(Metric[] metrics) {
        ArrayNode actionsArray = objectMapper.createArrayNode();

        for (Metric metric : metrics) {
            try {
                addMetricActions(actionsArray, metric);
            } catch (Exception e) {
                logger.warning(String.format("Failed to process metric %s: %s",
                        getMetricName(metric), e.getMessage()));
            }
        }

        return actionsArray;
    }

    /**
     * Adds actions for a single metric to the actions array.
     */
    private static void addMetricActions(ArrayNode actionsArray, Metric metric) {
        if (metric instanceof Gauge gauge) {
            addGaugeActions(actionsArray, gauge);
        } else {
            actionsArray.add(createActionFromMetric(metric));
        }
    }

    /**
     * Adds actions for gauge metrics including snapshots.
     */
    private static void addGaugeActions(ArrayNode actionsArray, Gauge gauge) {
        // Add main gauge action
        actionsArray.add(createActionFromMetric(gauge));

        // Add snapshot-based actions
        Snapshot[] snapshots = gauge.getSnapshots();
        if (snapshots != null) {
            for (Snapshot snapshot : snapshots) {
                addSnapshotActions(actionsArray, gauge, snapshot);
            }
        }
    }

    /**
     * Adds actions for snapshot statistics.
     */
    private static void addSnapshotActions(ArrayNode actionsArray, Gauge gauge, Snapshot snapshot) {
        String baseName = gauge.getId().getName();
        Set<Tag> tags = gauge.getId().getTags();

        // Add statistical measures
        actionsArray.add(createStatAction(baseName, "min", snapshot.getMin(), tags));
        actionsArray.add(createStatAction(baseName, "max", snapshot.getMax(), tags));
        actionsArray.add(createStatAction(baseName, "mean", snapshot.getMean(), tags));
        actionsArray.add(createStatAction(baseName, "stdDev", snapshot.getStdDev(), tags));

        // Add percentiles
        for (PercentileValue percentileValue : snapshot.getPercentileValues()) {
            String percentileName = "p" + (int) percentileValue.getPercentile();
            actionsArray.add(createStatAction(baseName, percentileName,
                    percentileValue.getValue(), tags));
        }
    }

    /**
     * Creates an action for statistical measures.
     */
    private static ObjectNode createStatAction(String baseName, String statType,
            double value, Set<Tag> tags) {
        String metricName = baseName + "_" + statType;
        return createAction(metricName, MetricConstants.GAUGE, value, tags);
    }

    /**
     * Creates a Moesif action from a metric.
     */
    private static ObjectNode createActionFromMetric(Metric metric) {
        MetricValue metricValue = extractMetricValue(metric);
        return createAction(metric.getId().getName(), metricValue.type,
                metricValue.value, metric.getId().getTags());
    }

    /**
     * Creates a Moesif action with the specified parameters.
     */
    private static ObjectNode createAction(String metricName, String metricType,
            double value, Set<Tag> tags) {
        ObjectNode action = objectMapper.createObjectNode();

        // Set action name
        action.put("action_name", sanitizeActionName(metricName));

        // Create request object
        ObjectNode request = objectMapper.createObjectNode();
        request.put("time", Instant.now().toString());
        action.set("request", request);

        // Create metadata
        ObjectNode metadata = createMetadata(metricName, metricType, value, tags);
        action.set("metadata", metadata);

        return action;
    }

    /**
     * Creates metadata object for the action with improved additional attributes handling.
     */
    private static ObjectNode createMetadata(String metricName, String metricType,
            double value, Set<Tag> tags) {
        ObjectNode metadata = objectMapper.createObjectNode();

        // Add cached additional attributes efficiently
        if (!cachedAdditionalAttributes.isEmpty()) {
            cachedAdditionalAttributes.forEach(metadata::put);
        }

        // Add standard metadata
        metadata.put("host", getHostName());
        metadata.put("language", "ballerina");
        metadata.put("session_id", generateSessionId());
        metadata.put("metric_name", metricName);
        metadata.put("metric_type", metricType);
        metadata.put("metric_value", value);
        metadata.put("timestamp", Instant.now().toString());

        // Add tags if present
        if (tags != null && !tags.isEmpty()) {
            ObjectNode tagsNode = objectMapper.createObjectNode();
            for (Tag tag : tags) {
                if (isValidTag(tag)) {
                    tagsNode.put(sanitizeMetadataKey(tag.getKey()),
                               sanitizeMetadataValue(tag.getValue()));
                }
            }
            if (tagsNode.size() > 0) {
                metadata.set("metric_tags", tagsNode);
            }
        }

        return metadata;
    }

    /**
     * Sends HTTP request to Moesif API.
     */
    private static void sendHttpRequest(BString reporterBaseUrl, BString applicationId,
            String jsonPayload, int metricCount) throws Exception {
        String endpoint = reporterBaseUrl.getValue() + DEFAULT_ENDPOINT_PATH;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header(APP_ID_HEADER, applicationId.getValue())
                .header(CONTENT_TYPE_HEADER, APPLICATION_JSON)
                .timeout(httpTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        handleHttpResponse(response, metricCount, endpoint);
    }

    /**
     * Handles HTTP response from Moesif API.
     */
    private static void handleHttpResponse(HttpResponse<String> response, int metricCount,
            String endpoint) {
        int statusCode = response.statusCode();

        if (statusCode >= HTTP_SUCCESS_MIN && statusCode <= HTTP_SUCCESS_MAX) {
            logger.fine(String.format("Successfully published %d metrics to %s",
                    metricCount, endpoint));
        } else {
            String errorMsg = String.format(
                    "Failed to publish metrics. Status: %d, Response: %s",
                    statusCode, response.body());
            logger.severe(errorMsg);
        }
    }

    /**
     * Extracts metric value and type from a metric object.
     */
    private static MetricValue extractMetricValue(Metric metric) {
        if (metric instanceof Counter counter) {
            return new MetricValue(convertToDouble(counter.getValue()), MetricConstants.COUNTER);
        } else if (metric instanceof Gauge gauge) {
            return new MetricValue(convertToDouble(gauge.getValue()), MetricConstants.GAUGE);
        } else if (metric instanceof PolledGauge polledGauge) {
            return new MetricValue(convertToDouble(polledGauge.getValue()), MetricConstants.GAUGE);
        }

        return new MetricValue(0.0, "UNKNOWN");
    }

    /**
     * Converts various numeric types to double.
     */
    private static double convertToDouble(Object value) {
        if (value == null)
            return 0.0;

        return switch (value) {
            case Long l -> l.doubleValue();
            case Integer i -> i.doubleValue();
            case Double d -> d;
            case Float f -> f.doubleValue();
            case Number n -> n.doubleValue();
            default -> 0.0;
        };
    }

    /**
     * Sanitizes action name for Moesif compatibility.
     */
    private static String sanitizeActionName(String metricName) {
        if (metricName == null || metricName.trim().isEmpty()) {
            return "metric_report";
        }
        return "metric_" + metricName.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Sanitizes metadata keys for consistency and safety.
     */
    private static String sanitizeMetadataKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return "";
        }

        // Remove invalid characters and ensure reasonable length
        String sanitized = key.trim()
                             .replaceAll("[^a-zA-Z0-9_.-]", "_")
                             .toLowerCase();

        return sanitized.length() > MAX_KEY_LENGTH ? sanitized.substring(0, MAX_KEY_LENGTH) : sanitized;
}

    /**
     * Sanitizes metadata values for safety and size control.
     */
    private static String sanitizeMetadataValue(String value) {
        if (value == null) {
            return "";
        }

        // Truncate long values and escape special characters
        String sanitized = value.trim();
        if (sanitized.length() > MAX_VALUE_LENGTH) {
            sanitized = sanitized.substring(0, MAX_VALUE_LENGTH) + "...";
        }

        // Basic escaping for JSON safety
        return sanitized.replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r");
    }

    /**
     * Validates that a tag has valid key and value.
     */
    private static boolean isValidTag(Tag tag) {
        return tag != null &&
               tag.getKey() != null && !tag.getKey().trim().isEmpty() &&
               tag.getValue() != null && !tag.getValue().trim().isEmpty();
    }

    /**
     * Gets hostname with proper error handling.
     */
    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            logger.fine("Could not determine hostname: " + e.getMessage());
            return "unknown_host";
        }
    }

    /**
     * Gets a safe metric name for logging.
     */
    private static String getMetricName(Metric metric) {
        return metric != null && metric.getId() != null ? metric.getId().getName() : "unknown_metric";
    }

    /**
     * Generates a unique session ID.
     */
    private static String generateSessionId() {
        return "session_" + System.currentTimeMillis() + "_" + sessionCounter.incrementAndGet();
    }

    /**
     * Gracefully shuts down the metrics reporter.
     */
    public static void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.fine("Moesif metrics reporter shutdown completed");
        }

        // Clear cached attributes and previous cycle snapshot
        synchronized (attributesCacheLock) {
            cachedAdditionalAttributes = Collections.emptyMap();
        }
        previousMetricSnapshot.clear();
    }

    /**
     * Helper record to hold metric value and type.
     */
    private record MetricValue(double value, String type) {
    }

}
