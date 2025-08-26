/*
 * Copyright (c) 2025, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.ballerina.observe.trace.moesif.sampler;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class for {@link RateLimitingSampler}.
 */
class RateLimitingSamplerTest {

    private RateLimitingSampler sampler;
    private Context parentContext;
    private String traceId;
    private String spanName;
    private SpanKind spanKind;
    private Attributes attributes;
    private List<LinkData> parentLinks;

    @BeforeEach
    void setUp() {
        sampler = new RateLimitingSampler(10); // 10 traces per second
        
        // Set up test parameters for shouldSample method
        parentContext = Context.root();
        traceId = "test-trace-id";
        spanName = "test-span";
        spanKind = SpanKind.SERVER;
        attributes = Attributes.empty();
        parentLinks = Collections.emptyList();
    }

    @Test
    @DisplayName("Should create sampler with positive rate limit")
    void shouldCreateSamplerWithPositiveRateLimit() {
        RateLimitingSampler sampler1 = new RateLimitingSampler(1);
        RateLimitingSampler sampler5 = new RateLimitingSampler(5);
        RateLimitingSampler sampler100 = new RateLimitingSampler(100);
        
        assertNotNull(sampler1, "Sampler should be created with rate 1");
        assertNotNull(sampler5, "Sampler should be created with rate 5");
        assertNotNull(sampler100, "Sampler should be created with rate 100");
    }

    @Test
    @DisplayName("Should handle zero rate limit")
    void shouldHandleZeroRateLimit() {
        assertDoesNotThrow(() -> {
            RateLimitingSampler zeroSampler = new RateLimitingSampler(0);
            assertNotNull(zeroSampler, "Sampler should be created with rate 0");
        }, "Should handle zero rate limit without throwing exception");
    }

    @Test
    @DisplayName("Should have correct sampler type constant")
    void shouldHaveCorrectSamplerTypeConstant() {
        assertEquals("ratelimiting", RateLimitingSampler.TYPE, 
                    "TYPE constant should be 'ratelimiting'");
    }

    @Test
    @DisplayName("Should return non-null description")
    void shouldReturnNonNullDescription() {
        String description = sampler.getDescription();
        
        assertNotNull(description, "Description should not be null");
        assertFalse(description.isEmpty(), "Description should not be empty");
        assertTrue(description.contains("RateLimitingSampler"), 
                  "Description should contain sampler name");
    }

    @Test
    @DisplayName("Should have toString consistent with getDescription")
    void shouldHaveToStringConsistentWithGetDescription() {
        String description = sampler.getDescription();
        String toStringResult = sampler.toString();
        
        assertEquals(description, toStringResult, 
                    "toString() should return the same as getDescription()");
    }

    @Test
    @DisplayName("Should return valid sampling result")
    void shouldReturnValidSamplingResult() {
        SamplingResult result = sampler.shouldSample(
            parentContext, traceId, spanName, spanKind, attributes, parentLinks
        );
        
        assertNotNull(result, "Sampling result should not be null");
        assertNotNull(result.getDecision(), "Sampling decision should not be null");
        assertNotNull(result.getAttributes(), "Result attributes should not be null");
    }

    @Test
    @DisplayName("Should handle multiple sampling calls")
    void shouldHandleMultipleSamplingCalls() {
        // Call shouldSample multiple times to ensure it doesn't throw exceptions
        for (int i = 0; i < 5; i++) {
            SamplingResult result = sampler.shouldSample(
                parentContext, traceId + "-" + i, spanName, spanKind, attributes, parentLinks
            );
            assertNotNull(result, "Result should not be null for call " + i);
        }
    }

    @Test
    @DisplayName("Should handle different span kinds")
    void shouldHandleDifferentSpanKinds() {
        SpanKind[] spanKinds = {SpanKind.SERVER, SpanKind.CLIENT, SpanKind.INTERNAL, 
                               SpanKind.PRODUCER, SpanKind.CONSUMER};
        
        for (SpanKind kind : spanKinds) {
            assertDoesNotThrow(() -> {
                SamplingResult result = sampler.shouldSample(
                    parentContext, traceId, spanName, kind, attributes, parentLinks
                );
                assertNotNull(result, "Result should not be null for span kind " + kind);
            }, "Should handle span kind " + kind);
        }
    }

    @Test
    @DisplayName("Should handle empty and null parameters gracefully")
    void shouldHandleEmptyAndNullParametersGracefully() {
        // Test with empty span name
        assertDoesNotThrow(() -> {
            SamplingResult result = sampler.shouldSample(
                parentContext, traceId, "", spanKind, attributes, parentLinks
            );
            assertNotNull(result, "Should handle empty span name");
        });

        // Note: Removed null span name test as SpotBugs reports it as passing null to non-null parameter
        // This is correct behavior since the OpenTelemetry API expects non-null parameters
    }

    @Test
    @DisplayName("Should create different samplers with different rates")
    void shouldCreateDifferentSamplersWithDifferentRates() {
        RateLimitingSampler sampler1 = new RateLimitingSampler(1);
        RateLimitingSampler sampler10 = new RateLimitingSampler(10);
        RateLimitingSampler sampler100 = new RateLimitingSampler(100);
        
        // Descriptions should be different as they contain the rate
        String desc1 = sampler1.getDescription();
        String desc10 = sampler10.getDescription();
        String desc100 = sampler100.getDescription();
        
        assertNotEquals(desc1, desc10, "Samplers with different rates should have different descriptions");
        assertNotEquals(desc10, desc100, "Samplers with different rates should have different descriptions");
        assertNotEquals(desc1, desc100, "Samplers with different rates should have different descriptions");
        
        // All should contain the sampler name
        assertTrue(desc1.contains("RateLimitingSampler"), "Description should contain sampler name");
        assertTrue(desc10.contains("RateLimitingSampler"), "Description should contain sampler name");
        assertTrue(desc100.contains("RateLimitingSampler"), "Description should contain sampler name");
    }
}
