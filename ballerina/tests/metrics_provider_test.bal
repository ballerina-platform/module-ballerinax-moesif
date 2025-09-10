// Copyright (c) 2025, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import ballerina/test;

const string TEST_APPLICATION_ID = "test-app-id-123";
const string TEST_REPORTER_BASE_URL = "https://test.moesif.net";
const PROVIDER_NAME = "moesif";

// Mock variables to track metrics publishing
boolean externSendMetricsCalled = false;
string metricsReporterBaseUrl = "";
string metricsApplicationId = "";
int metricsFlushInterval = 0;
int metricsClientTimeout = 0;
boolean metricsTraceLogging = false;
boolean metricsPayloadLogging = false;
map<string> metricsAdditionalAttributes = {};
string[] metricsOutput = [];

function resetMetricsMockState() {
    externSendMetricsCalled = false;
    metricsReporterBaseUrl = "";
    metricsApplicationId = "";
    metricsFlushInterval = 0;
    metricsClientTimeout = 0;
    metricsTraceLogging = false;
    metricsPayloadLogging = false;
    metricsAdditionalAttributes = {};
    metricsOutput = [];
}

function mockExternSendMetrics(string reporterBaseUrl, string applicationId, int metricReporterFlushInterval,
        int metricReporterClientTimeout, boolean isTraceLoggingEnabled, boolean isPayloadLoggingEnabled, map<string> additionalAttributes) returns string[] {
    externSendMetricsCalled = true;
    metricsReporterBaseUrl = reporterBaseUrl;
    metricsApplicationId = applicationId;
    metricsFlushInterval = metricReporterFlushInterval;
    metricsClientTimeout = metricReporterClientTimeout;
    metricsTraceLogging = isTraceLoggingEnabled;
    metricsPayloadLogging = isPayloadLoggingEnabled;
    metricsAdditionalAttributes = additionalAttributes;
    // Simulate output
    metricsOutput = ["info: metrics sent", "error: test error"];
    return metricsOutput;
}

function simulateMetricsPublishing(boolean metricsEnabled, string metricsProvider, string reporterBaseUrl, string applicationId,
        int flushInterval, int clientTimeout, boolean traceLogging, boolean payloadLogging, map<string> additionalAttributes) returns string[] {
    if metricsEnabled && metricsProvider == PROVIDER_NAME {
        return mockExternSendMetrics(reporterBaseUrl, applicationId, flushInterval, clientTimeout, traceLogging, payloadLogging, additionalAttributes);
    }
    return [];
}

@test:Config {}
function testMetricsPublishingCalledWithCorrectParams() {
    resetMetricsMockState();
    boolean metricsEnabled = true;
    string metricsProvider = PROVIDER_NAME;
    int flushInterval = 15000;
    int clientTimeout = 10000;
    boolean traceLogging = true;
    boolean payloadLogging = false;
    map<string> additionalAttributes = {"env": "test"};

    string[] output = simulateMetricsPublishing(metricsEnabled, metricsProvider, TEST_REPORTER_BASE_URL, TEST_APPLICATION_ID,
        flushInterval, clientTimeout, traceLogging, payloadLogging, additionalAttributes);

    test:assertTrue(externSendMetricsCalled, "Metrics publishing should be called");
    test:assertEquals(metricsReporterBaseUrl, TEST_REPORTER_BASE_URL, "Reporter base URL should match");
    test:assertEquals(metricsApplicationId, TEST_APPLICATION_ID, "Application ID should match");
    test:assertEquals(metricsFlushInterval, flushInterval, "Flush interval should match");
    test:assertEquals(metricsClientTimeout, clientTimeout, "Client timeout should match");
    test:assertEquals(metricsTraceLogging, traceLogging, "Trace logging flag should match");
    test:assertEquals(metricsPayloadLogging, payloadLogging, "Payload logging flag should match");
    test:assertEquals(metricsAdditionalAttributes["env"], "test", "Additional attribute should match");
    test:assertEquals(output.length(), 2, "Should return output array");
}

@test:Config {}
function testMetricsPublishingNotCalledWhenDisabled() {
    resetMetricsMockState();
    boolean metricsEnabled = false;
    string metricsProvider = PROVIDER_NAME;
    string[] output = simulateMetricsPublishing(metricsEnabled, metricsProvider, TEST_REPORTER_BASE_URL, TEST_APPLICATION_ID,
        15000, 10000, false, false, {});
    test:assertFalse(externSendMetricsCalled, "Metrics publishing should not be called when disabled");
    test:assertEquals(output.length(), 0, "Output should be empty");
}

@test:Config {}
function testMetricsPublishingNotCalledForOtherProvider() {
    resetMetricsMockState();
    boolean metricsEnabled = true;
    string metricsProvider = "other";
    string[] output = simulateMetricsPublishing(metricsEnabled, metricsProvider, TEST_REPORTER_BASE_URL, TEST_APPLICATION_ID,
        15000, 10000, false, false, {});
    test:assertFalse(externSendMetricsCalled, "Metrics publishing should not be called for other provider");
    test:assertEquals(output.length(), 0, "Output should be empty");
}

@test:Config {}
function testMetricsPublishingHandlesOutput() {
    resetMetricsMockState();
    boolean metricsEnabled = true;
    string metricsProvider = PROVIDER_NAME;
    string[] output = simulateMetricsPublishing(metricsEnabled, metricsProvider, TEST_REPORTER_BASE_URL, TEST_APPLICATION_ID,
        15000, 10000, false, false, {});
    test:assertTrue(output[0].startsWith("info:"), "First output should be info");
    test:assertTrue(output[1].startsWith("error:"), "Second output should be error");
}
