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

package io.ballerina.observe.trace.moesif;

import io.ballerina.observe.trace.moesif.sampler.RateLimitingSampler;
import io.ballerina.runtime.api.values.BDecimal;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.observability.tracer.spi.TracerProvider;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

import static io.opentelemetry.semconv.ResourceAttributes.SERVICE_NAME;

/**
 * This is the Moesif tracing extension class for {@link TracerProvider}.
 */
public class MoesifTracerProvider implements TracerProvider {
    private static final String TRACER_NAME = "moesif";
    private static final String APP_ID_HEADER = "X-Moesif-Application-Id";
    private static final PrintStream console = System.out;

    static SdkTracerProviderBuilder tracerProviderBuilder;

    @Override
    public String getName() {
        return TRACER_NAME;
    }

    @Override
    public void init() {    // Do Nothing
    }


    public static void initializeConfigurations(BString reporterBaseUrl, BString applicationId, BString samplerType,
                                                BDecimal samplerParam, int reporterFlushInterval,
                                                int reporterBufferSize) {
        String reporterEndpoint = reporterBaseUrl + "/v1/traces";

        // Create the OTLP HTTP exporter
        OtlpHttpSpanExporter spanExporter =
                OtlpHttpSpanExporter.builder()
                        .setEndpoint(reporterEndpoint)
                        .addHeader(APP_ID_HEADER,
                                String.valueOf(applicationId))
                        .build();


        // Build the tracer provider
        tracerProviderBuilder = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                        .setMaxExportBatchSize(reporterBufferSize)
                        .setExporterTimeout(reporterFlushInterval, TimeUnit.MILLISECONDS)
                        .build());

        // Set the sampler
        tracerProviderBuilder.setSampler(selectSampler(samplerType, samplerParam));
        console.println("ballerina: started publishing traces to Moesif HTTP endpoint at " + reporterEndpoint);
    }


    private static Sampler selectSampler(BString samplerType, BDecimal samplerParam) {
        switch (samplerType.getValue()) {
            default:
            case "const":
                if (samplerParam.value().intValue() == 0) {
                    return Sampler.alwaysOff();
                } else {
                    return Sampler.alwaysOn();
                }
            case "probabilistic":
                return Sampler.traceIdRatioBased(samplerParam.value().doubleValue());
            case RateLimitingSampler.TYPE:
                return new RateLimitingSampler(samplerParam.value().intValue());
        }
    }

    @Override
    public Tracer getTracer(String serviceName) {

        return tracerProviderBuilder.setResource(
                Resource.create(Attributes.of(SERVICE_NAME, serviceName)))
                .build().get(TRACER_NAME);
    }

    @Override
    public ContextPropagators getPropagators() {

        return ContextPropagators.create(W3CTraceContextPropagator.getInstance());
    }
}
