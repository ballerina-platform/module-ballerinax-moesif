## Package Overview

The Moesif Observability Extension is one of the tracing extensions of the<a target="_blank" href="https://ballerina.io/"> Ballerina</a> language.

It provides an implementation for tracing and publishing traces to a Moesif application.

## Enabling Moesif Extension

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
```

Follow the below steps to get the `applicationId`.
- Log into <a target="_blank" href="https://www.moesif.com/wrap/">Moesif Portal</a>.
- Select the account icon to bring up the settings menu.
- Select Installation or API Keys.
- Copy your Moesif Application ID from the Collector Application ID field.