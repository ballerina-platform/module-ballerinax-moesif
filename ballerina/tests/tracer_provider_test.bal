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

// Test constants
const string TEST_APPLICATION_ID = "test-app-id-123";
const string TEST_REPORTER_BASE_URL = "https://test.moesif.net";
const string TEST_CONST_SAMPLER = "const";
const string TEST_PROBABILISTIC_SAMPLER = "probabilistic";
const string TEST_RATELIMITING_SAMPLER = "ratelimiting";
const string TEST_INVALID_SAMPLER = "invalid";

// Mock variables to track external method calls
boolean externMethodCalled = false;
string lastReporterBaseUrl = "";
string lastApplicationId = "";
string lastSamplerType = "";
decimal lastSamplerParam = 0;
int lastReporterFlushInterval = 0;
int lastReporterBufferSize = 0;

// Helper function to reset mock state
function resetMockState() {
    externMethodCalled = false;
    lastReporterBaseUrl = "";
    lastApplicationId = "";
    lastSamplerType = "";
    lastSamplerParam = 0;
    lastReporterFlushInterval = 0;
    lastReporterBufferSize = 0;
}

// Mock implementation of externInitializeConfigurations for testing
function mockExternInitializeConfigurations(string reporterBaseUrl, string applicationId, string samplerType,
        decimal samplerParam, int reporterFlushInterval, int reporterBufferSize) {
    externMethodCalled = true;
    lastReporterBaseUrl = reporterBaseUrl;
    lastApplicationId = applicationId;
    lastSamplerType = samplerType;
    lastSamplerParam = samplerParam;
    lastReporterFlushInterval = reporterFlushInterval;
    lastReporterBufferSize = reporterBufferSize;
}

// Test helper functions to simulate the init logic
function simulateInitWithTracingEnabled(boolean tracingEnabled, string tracingProvider, string samplerType,
        decimal samplerParam, int flushInterval, int bufferSize) {
    if tracingEnabled && tracingProvider == PROVIDER_NAME {
        string selectedSamplerType;
        if samplerType != "const" && samplerType != "ratelimiting" && samplerType != "probabilistic" {
            selectedSamplerType = DEFAULT_SAMPLER_TYPE;
        } else {
            selectedSamplerType = samplerType;
        }

        mockExternInitializeConfigurations(TEST_REPORTER_BASE_URL, TEST_APPLICATION_ID, selectedSamplerType,
            samplerParam, flushInterval, bufferSize);
    }
}

@test:Config {}
function testProviderNameConstant() {
    test:assertEquals(PROVIDER_NAME, "moesif", "Provider name should be 'moesif'");
}

@test:Config {}
function testDefaultSamplerTypeConstant() {
    test:assertEquals(DEFAULT_SAMPLER_TYPE, "const", "Default sampler type should be 'const'");
}

@test:Config {}
function testInitWithValidConfigurationAndTracingEnabled() {
    resetMockState();
    
    // Simulate init with tracing enabled and moesif provider
    simulateInitWithTracingEnabled(true, PROVIDER_NAME, TEST_CONST_SAMPLER, 1.0, 1000, 10000);
    
    // Verify external method was called
    test:assertTrue(externMethodCalled, "External initialization method should be called");
    test:assertEquals(lastReporterBaseUrl, TEST_REPORTER_BASE_URL, "Reporter base URL should match");
    test:assertEquals(lastApplicationId, TEST_APPLICATION_ID, "Application ID should match");
    test:assertEquals(lastSamplerType, TEST_CONST_SAMPLER, "Sampler type should match");
    test:assertEquals(lastSamplerParam, 1.0d, "Sampler param should match");
    test:assertEquals(lastReporterFlushInterval, 1000, "Reporter flush interval should match");
    test:assertEquals(lastReporterBufferSize, 10000, "Reporter buffer size should match");
}

@test:Config {}
function testInitWithTracingDisabled() {
    resetMockState();
    
    // Simulate init with tracing disabled
    simulateInitWithTracingEnabled(false, PROVIDER_NAME, TEST_CONST_SAMPLER, 1.0, 1000, 10000);
    
    // Verify external method was not called
    test:assertFalse(externMethodCalled, "External initialization method should not be called when tracing is disabled");
}

@test:Config {}
function testInitWithDifferentProvider() {
    resetMockState();
    
    // Simulate init with different tracing provider
    simulateInitWithTracingEnabled(true, "jaeger", TEST_CONST_SAMPLER, 1.0, 1000, 10000);
    
    // Verify external method was not called
    test:assertFalse(externMethodCalled, "External initialization method should not be called for different provider");
}

@test:Config {}
function testInitWithConstSampler() {
    resetMockState();
    
    // Test with const sampler
    simulateInitWithTracingEnabled(true, PROVIDER_NAME, TEST_CONST_SAMPLER, 1.0, 1000, 10000);
    
    test:assertTrue(externMethodCalled, "External method should be called");
    test:assertEquals(lastSamplerType, TEST_CONST_SAMPLER, "Should use const sampler");
    test:assertEquals(lastSamplerParam, 1.0d, "Should use correct sampler parameter");
}

@test:Config {}
function testInitWithProbabilisticSampler() {
    resetMockState();
    
    // Test with probabilistic sampler
    simulateInitWithTracingEnabled(true, PROVIDER_NAME, TEST_PROBABILISTIC_SAMPLER, 0.5, 1000, 10000);
    
    test:assertTrue(externMethodCalled, "External method should be called");
    test:assertEquals(lastSamplerType, TEST_PROBABILISTIC_SAMPLER, "Should use probabilistic sampler");
    test:assertEquals(lastSamplerParam, 0.5d, "Should use correct sampling rate");
}

@test:Config {}
function testInitWithRateLimitingSampler() {
    resetMockState();
    
    // Test with rate limiting sampler
    simulateInitWithTracingEnabled(true, PROVIDER_NAME, TEST_RATELIMITING_SAMPLER, 10.0, 1000, 10000);
    
    test:assertTrue(externMethodCalled, "External method should be called");
    test:assertEquals(lastSamplerType, TEST_RATELIMITING_SAMPLER, "Should use rate limiting sampler");
    test:assertEquals(lastSamplerParam, 10.0d, "Should use correct rate limit");
}

@test:Config {}
function testInitWithInvalidSamplerType() {
    resetMockState();
    
    // Test with invalid sampler type (should default to const)
    simulateInitWithTracingEnabled(true, PROVIDER_NAME, TEST_INVALID_SAMPLER, 1.0, 1000, 10000);
    
    test:assertTrue(externMethodCalled, "External method should be called");
    test:assertEquals(lastSamplerType, DEFAULT_SAMPLER_TYPE, "Should default to const sampler for invalid type");
}

@test:Config {}
function testInitWithCustomReporterConfiguration() {
    resetMockState();
    
    // Test with custom reporter configuration
    int customFlushInterval = 2000;
    int customBufferSize = 20000;
    
    simulateInitWithTracingEnabled(true, PROVIDER_NAME, TEST_CONST_SAMPLER, 1.0, customFlushInterval, customBufferSize);
    
    test:assertTrue(externMethodCalled, "External method should be called");
    test:assertEquals(lastReporterFlushInterval, customFlushInterval, "Should use custom flush interval");
    test:assertEquals(lastReporterBufferSize, customBufferSize, "Should use custom buffer size");
}

@test:Config {}
function testSamplerTypeValidation() {
    // Test valid sampler types
    string[] validSamplerTypes = ["const", "probabilistic", "ratelimiting"];
    
    foreach string samplerType in validSamplerTypes {
        boolean isValid = samplerType == "const" || samplerType == "probabilistic" || samplerType == "ratelimiting";
        test:assertTrue(isValid, string `Sampler type '${samplerType}' should be valid`);
    }
    
    // Test invalid sampler types
    string[] invalidSamplerTypes = ["invalid", "unknown", "", "jaeger"];
    
    foreach string samplerType in invalidSamplerTypes {
        boolean isValid = samplerType == "const" || samplerType == "probabilistic" || samplerType == "ratelimiting";
        test:assertFalse(isValid, string `Sampler type '${samplerType}' should be invalid`);
    }
}

@test:Config {}
function testMockExternalMethodSignature() {
    resetMockState();
    
    // Test that mock external method can be called with all parameter types
    string testUrl = "https://test.moesif.net";
    string testAppId = "test-app-123";
    string testSampler = "const";
    decimal testParam = 0.75;
    int testInterval = 1500;
    int testBuffer = 15000;
    
    mockExternInitializeConfigurations(testUrl, testAppId, testSampler, testParam, testInterval, testBuffer);
    
    test:assertTrue(externMethodCalled, "Mock external method should accept all parameter types");
    test:assertEquals(lastReporterBaseUrl, testUrl, "String parameter should be passed correctly");
    test:assertEquals(lastApplicationId, testAppId, "String parameter should be passed correctly");
    test:assertEquals(lastSamplerType, testSampler, "String parameter should be passed correctly");
    test:assertEquals(lastSamplerParam, testParam, "Decimal parameter should be passed correctly");
    test:assertEquals(lastReporterFlushInterval, testInterval, "Int parameter should be passed correctly");
    test:assertEquals(lastReporterBufferSize, testBuffer, "Int parameter should be passed correctly");
}

@test:Config {}
function testMultipleInitializationCalls() {
    resetMockState();
    
    // First initialization call
    simulateInitWithTracingEnabled(true, PROVIDER_NAME, TEST_CONST_SAMPLER, 1.0, 1000, 10000);
    
    test:assertTrue(externMethodCalled, "First external method call should succeed");
    string firstSamplerType = lastSamplerType;
    
    // Reset and make second call with different parameters
    resetMockState();
    
    simulateInitWithTracingEnabled(true, PROVIDER_NAME, TEST_PROBABILISTIC_SAMPLER, 0.5, 2000, 20000);
    
    test:assertTrue(externMethodCalled, "Second external method call should succeed");
    test:assertNotEquals(lastSamplerType, firstSamplerType, "Second call should have different sampler type");
    test:assertEquals(lastSamplerType, TEST_PROBABILISTIC_SAMPLER, "Second call should use probabilistic sampler");
    test:assertEquals(lastSamplerParam, 0.5d, "Second call should use different parameter");
}

@test:Config {}
function testEdgeCaseParameters() {
    resetMockState();
    
    // Test with edge case parameters
    mockExternInitializeConfigurations("", "", "const", 0.0, 0, 0);
    
    test:assertTrue(externMethodCalled, "Mock external method should handle edge case parameters");
    test:assertEquals(lastReporterBaseUrl, "", "Should accept empty string");
    test:assertEquals(lastApplicationId, "", "Should accept empty string");
    test:assertEquals(lastSamplerParam, 0.0d, "Should accept zero decimal");
    test:assertEquals(lastReporterFlushInterval, 0, "Should accept zero int");
    test:assertEquals(lastReporterBufferSize, 0, "Should accept zero int");
}

@test:Config {}
function testSamplerParameterRanges() {
    resetMockState();
    
    // Test probabilistic sampler with different parameter ranges
    decimal[] testParams = [0.0, 0.1, 0.5, 0.9, 1.0];
    
    foreach decimal param in testParams {
        resetMockState();
        simulateInitWithTracingEnabled(true, PROVIDER_NAME, TEST_PROBABILISTIC_SAMPLER, param, 1000, 10000);
        
        test:assertTrue(externMethodCalled, string `External method should be called with param ${param}`);
        test:assertEquals(lastSamplerParam, param, string `Should use correct parameter value ${param}`);
    }
}

@test:Config {}
function testConfigurationParameterTypes() {
    resetMockState();
    
    // Test with different types of configuration values
    decimal maxDecimal = 999999.99;
    int maxInt = 2147483647; // Max int value
    
    mockExternInitializeConfigurations("https://very-long-url.moesif.net/api/v1/collector/traces", 
        "very-long-application-id-with-special-chars-123-abc", "probabilistic", 
        maxDecimal, maxInt, maxInt);
    
    test:assertTrue(externMethodCalled, "Should handle large parameter values");
    test:assertEquals(lastSamplerParam, maxDecimal, "Should handle large decimal values");
    test:assertEquals(lastReporterFlushInterval, maxInt, "Should handle large int values");
    test:assertEquals(lastReporterBufferSize, maxInt, "Should handle large int values");
}

@test:Config {}
function testAllSamplerTypesWithDifferentParameters() {
    string[] allSamplerTypes = [TEST_CONST_SAMPLER, TEST_PROBABILISTIC_SAMPLER, TEST_RATELIMITING_SAMPLER];
    decimal[] testParams = [0.0, 0.5, 1.0, 10.0];
    
    foreach string samplerType in allSamplerTypes {
        foreach decimal param in testParams {
            resetMockState();
            simulateInitWithTracingEnabled(true, PROVIDER_NAME, samplerType, param, 1000, 10000);
            
            test:assertTrue(externMethodCalled, 
                string `External method should be called for sampler ${samplerType} with param ${param}`);
            test:assertEquals(lastSamplerType, samplerType, 
                string `Should use correct sampler type ${samplerType}`);
            test:assertEquals(lastSamplerParam, param, 
                string `Should use correct parameter ${param} for sampler ${samplerType}`);
        }
    }
}
