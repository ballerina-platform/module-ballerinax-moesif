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

module io.ballerina.observe.trace.extension.moesif {
    requires io.ballerina.runtime;
    requires io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires io.opentelemetry.sdk.trace;
    requires io.opentelemetry.sdk.common;
    requires io.opentelemetry.extension.trace.propagation;
    requires io.opentelemetry.semconv;
    requires io.opentelemetry.exporter.otlp.http.trace;

    provides io.ballerina.runtime.observability.tracer.spi.TracerProvider
            with io.ballerina.observe.trace.moesif.MoesifTracerProvider;
}
