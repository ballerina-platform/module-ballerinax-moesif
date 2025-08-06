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

const PROVIDER_NAME = "moesif";
const DEFAULT_SAMPLER_TYPE = "const";

configurable string reporterBaseUrl = "https://api.moesif.net";
configurable string applicationId = ?;
configurable string samplerType = "const";
configurable decimal samplerParam = 1;
configurable int reporterFlushInterval = 1000;
configurable int reporterBufferSize = 10000;

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
            reporterFlushInterval, reporterBufferSize);
    }
}

function externInitializeConfigurations(string reporterBaseUrl, string applicationId, string samplerType,
        decimal samplerParam, int reporterFlushInterval, int reporterBufferSize) = @java:Method {
    'class: "io.ballerina.observe.trace.moesif.MoesifTracerProvider",
    name: "initializeConfigurations"
} external;
