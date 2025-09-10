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

import ballerina/io;
import ballerina/jballerina.java;
import ballerina/observe;
import ballerina/log;

configurable string reporterBaseUrl = "https://api.moesif.net";
configurable string applicationId = ?;
configurable string samplerType = "const";
configurable decimal samplerParam = 1;
configurable int tracingReporterFlushInterval = 1000;
configurable int tracingReporterBufferSize = 10000;
configurable int metricsReporterFlushInterval = 15000;
configurable int metricsReporterClientTimeout = 10000;
configurable map<string> additionalAttributes = {};

configurable boolean isTraceLoggingEnabled = false;
configurable boolean isPayloadLoggingEnabled = false;

function init() {
    if observe:isTracingEnabled() && observe:getTracingProvider() == PROVIDER_NAME {
        string selectedSamplerType;
        if samplerType != "const" && samplerType != "ratelimiting" && samplerType != "probabilistic" {
            selectedSamplerType = DEFAULT_SAMPLER_TYPE;
            io:println("error: invalid Moesif configuration sampler type: " + samplerType
                                               + ". using default " + DEFAULT_SAMPLER_TYPE + " sampling");
        } else {
            selectedSamplerType = samplerType;
        }

        externInitializeConfigurations(reporterBaseUrl, applicationId, selectedSamplerType, samplerParam,
            tracingReporterFlushInterval, tracingReporterBufferSize);
    }
    if observe:isMetricsEnabled() && observe:getMetricsReporter() == PROVIDER_NAME {
        string[] output = externSendMetrics(reporterBaseUrl, applicationId, metricsReporterFlushInterval, metricsReporterClientTimeout,
            isTraceLoggingEnabled, isPayloadLoggingEnabled, additionalAttributes);
        foreach string outputLine in output {
            if (outputLine.startsWith("error:")) {
                log:printError(outputLine);
            } else {
                log:printInfo(outputLine);
            }
        }
    }
}

function externInitializeConfigurations(string reporterBaseUrl, string applicationId, string samplerType,
        decimal samplerParam, int reporterFlushInterval, int reporterBufferSize) = @java:Method {
    'class: "io.ballerina.observe.trace.moesif.MoesifTracerProvider",
    name: "initializeConfigurations"
} external;

isolated function externSendMetrics(string reporterBaseUrl, string applicationId, int metricReporterFlushInterval,
                                     int metricReporterClientTimeout, boolean isTraceLoggingEnabled,
                                     boolean isPayloadLoggingEnabled, map<string> additionalAttributes) returns string[] = @java:Method {
    'class: "io.ballerina.observe.metrics.moesif.MoesifMetricReporter",
    name: "sendMetrics"
} external;
