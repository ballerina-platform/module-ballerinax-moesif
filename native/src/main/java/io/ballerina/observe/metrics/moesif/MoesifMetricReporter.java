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
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.observability.metrics.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
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

    // Logging
    private static final Logger logger = Logger.getLogger(MoesifMetricReporter.class.getName());

    // Thread-safe components
    private static volatile ScheduledExecutorService executor;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final AtomicLong sessionCounter = new AtomicLong(0);
    private static HttpClient httpClient;
    private static Duration httpTimeout = Duration.ofSeconds(30);

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
     * @return Array containing status messages
     */
    public static BArray sendMetrics(BString reporterBaseUrl, BString applicationId,
            int metricReporterFlushInterval, int metricReporterClientTimeout,
            boolean isTraceLoggingEnabled, boolean isPayloadLoggingEnabled) {

        validateInputs(reporterBaseUrl, applicationId, metricReporterFlushInterval);
        BArray output = ValueCreator.createArrayValue(
                TypeCreator.createArrayType(PredefinedTypes.TYPE_STRING));
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
                    isPayloadLoggingEnabled);

            String successMessage = String.format(
                    "Started publishing metrics to Moesif endpoint: %s%s",
                    reporterBaseUrl.getValue(), DEFAULT_ENDPOINT_PATH);
            output.append(StringUtils.fromString(successMessage));

            logger.info(successMessage);

        } catch (Exception e) {
            String errorMessage = "Failed to start Moesif metrics reporter: " + e.getMessage();
            logger.severe(errorMessage);
            output.append(StringUtils.fromString(errorMessage));
        }

        return output;
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
            logger.info("Trace logging enabled for Moesif metrics reporter");
        }
    }

    /**
     * Starts the metric reporting scheduler.
     */
    private static void startMetricReporting(BString reporterBaseUrl, BString applicationId,
            int metricReporterFlushInterval, boolean isPayloadLoggingEnabled) {
        executor = getOrCreateExecutor();
        executor.scheduleAtFixedRate(() -> {
            try {
                Metric[] metrics = DefaultMetricRegistry.getInstance().getAllMetrics();

                if (metrics.length == 0) {
                    logger.fine("No metrics to report");
                    return;
                }

                if (isPayloadLoggingEnabled) {
                    logger.info(String.format("Reporting %d metrics", metrics.length));
                }

                publishMetricsBatch(reporterBaseUrl, applicationId, metrics, isPayloadLoggingEnabled);

            } catch (Exception e) {
                logger.severe("Error in metric reporting task: " + e.getMessage());
            }
        }, SCHEDULE_EXECUTOR_INITIAL_DELAY, metricReporterFlushInterval, TimeUnit.MILLISECONDS);
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
                logger.info("Metric payload: " + jsonPayload);
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
     * Creates metadata object for the action.
     */
    private static ObjectNode createMetadata(String metricName, String metricType,
            double value, Set<Tag> tags) {
        ObjectNode metadata = objectMapper.createObjectNode();

        metadata.put("user_id", "system");
        metadata.put("session_id", generateSessionId());
        metadata.put("metric_name", metricName);
        metadata.put("metric_type", metricType);
        metadata.put("metric_value", value);

        // Add tags if present
        if (tags != null && !tags.isEmpty()) {
            ObjectNode tagsNode = objectMapper.createObjectNode();
            for (Tag tag : tags) {
                if (tag.getKey() != null && tag.getValue() != null) {
                    tagsNode.put(tag.getKey(), tag.getValue());
                }
            }
            metadata.set("metric_tags", tagsNode);
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
            logger.info(String.format("Successfully published %d metrics to %s",
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
            logger.info("Moesif metrics reporter shutdown completed");
        }
    }

    /**
     * Helper record to hold metric value and type.
     */
    private record MetricValue(double value, String type) {
    }
}
