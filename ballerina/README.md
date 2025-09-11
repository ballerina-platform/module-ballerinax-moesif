## Package Overview

The Moesif observability extension is one of the observability extensions of the<a target="_blank" href="https://ballerina.io/"> Ballerina</a> language.

It provides an implementation for publishing traces, logs and metrics to a Moesif application.

### Getting Moesif Application ID

Follow the below steps to get the `applicationId`.
- Log into <a target="_blank" href="https://www.moesif.com/wrap/">Moesif Portal</a>.
- Select the account icon to bring up the settings menu.
- Select Installation or API Keys.
- Copy your Moesif Application ID from the Collector Application ID field.

### Enabling Moesif Extension for traces and metrics publishing

To package the Moesif extension into the Jar, follow the below steps.

1. Add the following import to your program.
```ballerina
import ballerinax/moesif as _;
```

2. Add the following to the `Ballerina.toml` when building your program.
```toml
[package]
org = "my_org"
name = "my_package"
version = "1.0.0"

[build-options]
observabilityIncluded=true
```

3. To enable the extension and publish traces to Moesif, add the following to the `Config.toml` when running your program.
```toml
[ballerina.observe]
tracingEnabled=true
tracingProvider="moesif"

[ballerinax.moesif]
applicationId = "xxxxx"     # Mandatory configuration. Get the application ID via the Moesif portal
reporterBaseUrl = "xxxxx"   # Optional Configuration. Default value is https://api.moesif.net
tracingReporterFlushInterval = xxx; # Optional Configuration. Default value is 1000
tracingReporterBufferSize = xxx; # Optional Configuration. Default value is 10000
isTraceLoggingEnabled = xxx; # Optional Configuration. Default value is false
isPayloadLoggingEnabled = xxx; # Optional Configuration. Default value is false

```

4. To enable the extension and publish metrics to Moesif, add the following to the `Config.toml` when running your program.
```toml
[ballerina.observe]
metricsEnabled=true
metricsReporter="moesif"

[ballerinax.moesif]
applicationId = "xxxxx"     # Mandatory configuration. Get the application ID via the Moesif portal
reporterBaseUrl = "xxxxx"   # Optional Configuration. Default value is https://api.moesif.net
metricsReporterFlushInterval = xxx; # Optional Configuration. Default value is 15000
metricsReporterClientTimeout = xxx; # Optional Configuration. Default value is 10000
isTraceLoggingEnabled = xxx; # Optional Configuration. Default value is false
isPayloadLoggingEnabled = xxx; # Optional Configuration. Default value is false

# Additional attributes for metrics
[ballerinax.moesif.additionalAttributes]
key1 = "value1"
key2 = "value2"

```

## Getting Moesif Application ID

Follow the below steps to get the `applicationId`.
- Log into <a target="_blank" href="https://www.moesif.com/wrap/">Moesif Portal</a>.
- Select the account icon to bring up the settings menu.
- Select Installation or API Keys.
- Copy your Moesif Application ID from the Collector Application ID field.

### Publishing logs to Moesif

This setup does not use the Moesif extension. Instead, it leverages Fluent Bit to forward logs to an OTEL Collector, which then sends the logs to Moesif’s log endpoint.

The flow is:
Ballerina → Fluent Bit → OTEL Collector → Moesif

#### Components

docker-compose.yml – Defines the containerized setup for Fluent Bit and OTEL Collector.
fluent-bit.conf – Configures Fluent Bit to read logs from the Ballerina log file and forward them.
otelcol.yaml – Configures the OTEL Collector to process and send logs to Moesif’s log endpoint.

##### docker-compose.yml

```yml
services:
  otelcol:
    image: otel/opentelemetry-collector-contrib:0.132.0
    container_name: otelcol
    command: ["--config", "/etc/otelcol.yaml"]
    environment:
      MOESIF_APP_ID: "<moesif-application-id>"
    ports:
      - "4317:4317"
      - "4318:4318"
    volumes:
      - ./otelcol.yaml:/etc/otelcol.yaml:ro
    networks:
      - otelnet

  fluent-bit:
    image: fluent/fluent-bit:3.0
    container_name: fluent-bit
    depends_on:
      - otelcol
    ports:
      - "2020:2020"
    volumes:
      - ./fluent-bit.conf:/fluent-bit/etc/fluent-bit.conf:ro
      # Mount the local log directory into the container
      - <ballerina-log-path>:/app/logs:ro
    networks:
      - otelnet

networks:
  otelnet:
    driver: bridge
```

##### fluent-bit.conf

```conf
[SERVICE]
    Flush         1
    Log_Level     debug
    Daemon        off
    HTTP_Server   On
    HTTP_Listen   0.0.0.0
    HTTP_Port     2020

# Read logs from local Ballerina app
[INPUT]
    Name              tail
    Path              /app/logs/app.log
    Tag               ballerina.*
    Read_from_Head    true
    Skip_Long_Lines   On
    Skip_Empty_Lines  On
    Refresh_Interval  1

# Add metadata
[FILTER]
    Name         modify
    Match        ballerina.*
    Add          service.name ballerina-service
    Add          deployment.environment prod

# Convert to OTEL format and send to collector
[OUTPUT]
    Name          opentelemetry
    Match         ballerina.*
    Host          otelcol
    Port          4318
    Logs_uri      /v1/logs
    Log_response_payload True
    Tls           Off
    Tls.verify    Off

# Debug output to see what's being processed
[OUTPUT]
    Name          stdout
    Match         ballerina.*
    Format        json_lines
```

##### otelcol.yaml

```yml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: "0.0.0.0:4317"
      http:
        endpoint: "0.0.0.0:4318"

processors:
  resource:
    attributes:
      - key: service.name
        value: ballerina-service
        action: upsert
      - key: deployment.environment
        value: prod
        action: upsert

  transform/severity_from_message:
    log_statements:
      - context: log
        statements:
          # Set default severity to INFO for all logs first
          - set(severity_number, 9) where body != nil
          - set(severity_text, "INFO") where body != nil

          # Try to parse JSON body, but handle parsing errors gracefully
          - set(cache["is_json"], false)
          - set(cache["is_json"], true) where body != nil and IsMatch(body, "^\\s*\\{")
          
          # For JSON logs, parse and extract level
          - set(cache["parsed_body"], ParseJSON(body)) where cache["is_json"] == true
          
          # Override with specific levels based on JSON level field
          - set(severity_number, 1) where cache["is_json"] == true and cache["parsed_body"]["level"] == "TRACE"
          - set(severity_text, "TRACE") where cache["is_json"] == true and cache["parsed_body"]["level"] == "TRACE"

          - set(severity_number, 5) where cache["is_json"] == true and cache["parsed_body"]["level"] == "DEBUG"
          - set(severity_text, "DEBUG") where cache["is_json"] == true and cache["parsed_body"]["level"] == "DEBUG"

          - set(severity_number, 9) where cache["is_json"] == true and cache["parsed_body"]["level"] == "INFO"
          - set(severity_text, "INFO") where cache["is_json"] == true and cache["parsed_body"]["level"] == "INFO"

          - set(severity_number, 13) where cache["is_json"] == true and cache["parsed_body"]["level"] == "WARN"
          - set(severity_text, "WARN") where cache["is_json"] == true and cache["parsed_body"]["level"] == "WARN"

          - set(severity_number, 17) where cache["is_json"] == true and cache["parsed_body"]["level"] == "ERROR"
          - set(severity_text, "ERROR") where cache["is_json"] == true and cache["parsed_body"]["level"] == "ERROR"

          - set(severity_number, 21) where cache["is_json"] == true and cache["parsed_body"]["level"] == "FATAL"
          - set(severity_text, "FATAL") where cache["is_json"] == true and cache["parsed_body"]["level"] == "FATAL"

  batch: {}

exporters:
  # OTLP over HTTP to Moesif
  otlphttp:
    endpoint: "https://api.moesif.net"
    logs_endpoint: "https://api.moesif.net/v1/logs"
    headers:
      X-Moesif-Application-Id: "<moesif-application-id>"
    compression: none
    timeout: 10s
    sending_queue:
      enabled: true
      num_consumers: 2
      queue_size: 512
    retry_on_failure:
      enabled: true
      initial_interval: 1s
      max_interval: 10s
      max_elapsed_time: 0s

service:
  telemetry:
    logs:
      level: debug
  pipelines:
    logs:
      receivers:  [otlp]
      processors: [resource, transform/severity_from_message, batch]
      exporters:  [otlphttp]
```

#### Setup and execution

1. Publish the logs from your Ballerina application to a file in the specified log path using the following command.

    `bal run 2> <ballerina-log-path>/app.log`

2. Copy the component configurations from the previous section to a directory in your local file system.

    ```
    .
    ├── docker-compose.yml
    ├── fluent-bit.conf
    └── otelcol.yaml
    ```

3. Run the stack using the following command.

    `docker compose up`

With this setup, the Ballerina application writes its logs to a specified file, which Fluent Bit continuously tails and forwards to the OTEL Collector. The OTEL Collector then processes these logs and publishes them to Moesif.

### Visualizing the observability data

Traces, metrics, and logs are published to Moesif as events, which can be viewed in the live event log. Moesif also provides pre-built dashboards to help visualize the data, and you can create custom dashboards as needed for deeper insights.
