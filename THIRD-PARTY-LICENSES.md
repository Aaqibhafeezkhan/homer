# Third-party licenses

Homer redistributes third-party code in binary form: the `homer-all`,
`agentdebug` and `homer-runner` shadow JARs and the backend and runner Docker
images bundle the Gradle `runtimeClasspath` of `:app`, `:agentdebug` and
`:homer-runner`, and the console image ships both the Next.js
`.next/standalone` server bundle and the `.next/static` client bundles. This
file inventories every package in those artifacts, and [`NOTICE`](NOTICE)
carries the attributions that the backend jars' own NOTICE files require us to
reproduce (no package in the console graph ships one).

The inventory is generated - do not hand-edit the regions below. Regenerate the
backend region with `./gradlew :app:generateThirdPartyNotices` (from `app/`) and
the console region with `npm run licenses:generate` (from `console/`); CI fails
if either drifts from the classpath it describes.

Form: each package is listed with the copyright lines it ships, and the full
text of each license type appears once under [License texts](#license-texts).
Reproducing every package's own license file verbatim is the alternative if
legal prefers it. A dual-licensed dependency is used under its permissive or
weak-copyleft option per
[ADR-0056](docs/adr/0056-dependency-license-policy.md) and is listed once under
its full declared expression, so both options stay visible.

A package is listed without copyright-holder lines when its artifact ships no
license or notice file, when the file it ships carries no copyright line of its
own, or - for the platform-restricted `@next/swc-*` binaries - because the entry
is read from the lock alone so the inventory stays platform-independent; that is
about 20 console entries and most backend jars today, whose license is still
recorded above. A few build-time-only packages (sharp's remaining transitive
dependencies) are attributed although the image may not ship them, which is the
safe direction.

## Homer backend

The Gradle `runtimeClasspath` of `:app`, `:agentdebug` and `:homer-runner`, as
bundled into the shadow JARs and the backend and runner Docker images.

<!-- BEGIN GENERATED: backend (./gradlew :app:generateThirdPartyNotices) -->

#### Apache License, Version 2.0

- **at.favre.lib:bcrypt** 0.10.2 — no project URL
- **at.favre.lib:bytes** 1.5.0 — https://github.com/patrickfav/bytes-java
- **co.touchlab:stately-concurrency-jvm** 2.1.0 — https://github.com/touchlab/Stately
- **co.touchlab:stately-concurrency** 2.1.0 — https://github.com/touchlab/Stately
- **co.touchlab:stately-concurrent-collections-jvm** 2.1.0 — https://github.com/touchlab/Stately
- **co.touchlab:stately-concurrent-collections** 2.1.0 — https://github.com/touchlab/Stately
- **co.touchlab:stately-strict-jvm** 2.1.0 — https://github.com/touchlab/Stately
- **co.touchlab:stately-strict** 2.1.0 — https://github.com/touchlab/Stately
- **com.charleskorn.kaml:kaml-jvm** 0.104.0 — https://github.com/charleskorn/kaml
- **com.charleskorn.kaml:kaml** 0.104.0 — https://github.com/charleskorn/kaml
- **com.cronutils:cron-utils** 9.2.1 — http://cron-parser.com/
- **com.ethlo.time:itu** 1.14.0 — https://github.com/ethlo/itu
- **com.fasterxml.jackson.core:jackson-annotations** 2.22 — https://github.com/FasterXML/jackson
  - Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)
- **com.fasterxml.jackson.core:jackson-core** 2.22.3 — https://github.com/FasterXML/jackson-core
  - Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)
  - Copyright © 2023 Werner Randelshofer, Switzerland. MIT License.
  - Copyright 2018-2020 Raffaello Giulietti
- **com.fasterxml.jackson.core:jackson-databind** 2.22.3 — https://github.com/FasterXML/jackson
  - Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)
- **com.fasterxml.jackson.datatype:jackson-datatype-jdk8** 2.22.3 — https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jdk8
- **com.fasterxml.jackson.datatype:jackson-datatype-jsr310** 2.22.3 — https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jsr310
- **com.fasterxml.jackson:jackson-bom** 2.22.3 — https://github.com/FasterXML/jackson-bom
- **com.github.ajalt.clikt:clikt-core-jvm** 5.1.0 — https://github.com/ajalt/clikt/
- **com.github.ajalt.clikt:clikt-core** 5.1.0 — https://github.com/ajalt/clikt/
- **com.github.ajalt.clikt:clikt-jvm** 5.1.0 — https://github.com/ajalt/clikt/
- **com.github.ajalt.clikt:clikt** 5.1.0 — https://github.com/ajalt/clikt/
- **com.github.ajalt.mordant:mordant-core-jvm** 3.0.2 — https://github.com/ajalt/mordant/
- **com.github.ajalt.mordant:mordant-core** 3.0.2 — https://github.com/ajalt/mordant/
- **com.github.ajalt.mordant:mordant-jvm-ffm-jvm** 3.0.2 — https://github.com/ajalt/mordant/
- **com.github.ajalt.mordant:mordant-jvm-ffm** 3.0.2 — https://github.com/ajalt/mordant/
- **com.github.ajalt.mordant:mordant-jvm-graal-ffi-jvm** 3.0.2 — https://github.com/ajalt/mordant/
- **com.github.ajalt.mordant:mordant-jvm-graal-ffi** 3.0.2 — https://github.com/ajalt/mordant/
- **com.github.ajalt.mordant:mordant-jvm-jna-jvm** 3.0.2 — https://github.com/ajalt/mordant/
- **com.github.ajalt.mordant:mordant-jvm-jna** 3.0.2 — https://github.com/ajalt/mordant/
- **com.github.ajalt.mordant:mordant-jvm** 3.0.2 — https://github.com/ajalt/mordant/
- **com.github.ajalt.mordant:mordant** 3.0.2 — https://github.com/ajalt/mordant/
- **com.google.auto.value:auto-value-annotations** 1.11.0 — https://github.com/google/auto/tree/main/value
- **com.google.code.findbugs:jsr305** 3.0.2 — http://findbugs.sourceforge.net/
- **com.google.code.gson:gson** 2.14.0 — https://github.com/google/gson
- **com.google.errorprone:error_prone_annotations** 2.48.0 — https://errorprone.info/error_prone_annotations
- **com.google.guava:failureaccess** 1.0.3 — https://github.com/google/guava/
- **com.google.guava:guava** 33.5.0-jre — https://github.com/google/guava
- **com.google.guava:listenablefuture** 9999.0-empty-to-avoid-conflict-with-guava — no project URL
- **com.google.http-client:google-http-client-gson** 2.1.0 — no project URL
- **com.google.http-client:google-http-client** 2.1.0 — https://www.google.com/
- **com.google.j2objc:j2objc-annotations** 3.1 — https://github.com/google/j2objc/
- **com.jayway.jsonpath:json-path** 3.0.0 — https://github.com/jayway/JsonPath
- **com.networknt:json-schema-validator** 3.0.8 — https://github.com/networknt/json-schema-validator
- **com.squareup.okhttp3:logging-interceptor** 5.4.0 — https://square.github.io/okhttp/
- **com.squareup.okhttp3:okhttp-jvm** 5.4.0 — https://square.github.io/okhttp/
- **com.squareup.okhttp3:okhttp** 5.4.0 — https://square.github.io/okhttp/
- **com.squareup.okio:okio-jvm** 3.17.0 — https://github.com/square/okio/
- **com.squareup.okio:okio** 3.17.0 — https://github.com/square/okio/
- **com.typesafe:config** 1.4.8 — https://github.com/lightbend/config
- **commons-codec:commons-codec** 1.22.0 — https://commons.apache.org/proper/commons-codec/
  - Copyright 2002-2026 The Apache Software Foundation
- **commons-io:commons-io** 2.22.0 — https://commons.apache.org/proper/commons-io/
  - Copyright 2002-2026 The Apache Software Foundation
- **io.github.oshai:kotlin-logging-jvm** 8.0.4 — https://github.com/oshai/kotlin-logging
  - Copyright [2018] [Ohad Shai]
- **io.github.oshai:kotlin-logging** 8.0.4 — https://github.com/oshai/kotlin-logging
- **io.grpc:grpc-api** 1.70.0 — https://github.com/grpc/grpc-java
- **io.grpc:grpc-context** 1.70.0 — https://github.com/grpc/grpc-java
- **io.gsonfire:gson-fire** 1.9.0 — http://gsonfire.io
- **io.insert-koin:koin-core-jvm** 4.2.2 — https://insert-koin.io/
- **io.insert-koin:koin-core** 4.2.2 — https://insert-koin.io/
- **io.insert-koin:koin-ktor-jvm** 4.2.2 — https://insert-koin.io/
- **io.insert-koin:koin-ktor** 4.2.2 — https://insert-koin.io/
- **io.ktor:ktor-client-auth-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-client-auth** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-client-cio-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-client-cio** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-client-content-negotiation-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-client-content-negotiation** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-client-core-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-client-core** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-events-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-events** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-http-cio-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-http-cio** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-http-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-http** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-io-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-io** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-network-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-network-tls-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-network-tls** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-network** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-serialization-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-serialization-kotlinx-json-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-serialization-kotlinx-json** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-serialization-kotlinx-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-serialization-kotlinx** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-serialization** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-auth-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-auth** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-body-limit-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-body-limit** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-call-logging-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-call-logging** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-content-negotiation-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-content-negotiation** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-core-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-core** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-cors-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-cors** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-di-jvm** 3.4.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-di** 3.4.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-double-receive-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-double-receive** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-netty-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-netty** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-rate-limit-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-rate-limit** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-sessions-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-sessions** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-sse-jvm** 3.4.3 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-sse** 3.4.3 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-status-pages-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-status-pages** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-websockets-jvm** 3.4.3 — https://github.com/ktorio/ktor
- **io.ktor:ktor-server-websockets** 3.4.3 — https://github.com/ktorio/ktor
- **io.ktor:ktor-sse-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-sse** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-utils-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-utils** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-websocket-serialization-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-websocket-serialization** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-websockets-jvm** 3.5.0 — https://github.com/ktorio/ktor
- **io.ktor:ktor-websockets** 3.5.0 — https://github.com/ktorio/ktor
- **io.kubernetes:client-java-api** 27.0.0 — https://github.com/kubernetes-client/java
- **io.kubernetes:client-java-proto** 27.0.0 — https://github.com/kubernetes-client/java
- **io.kubernetes:client-java** 27.0.0 — https://github.com/kubernetes-client/java
- **io.netty:netty-bom** 4.2.18.Final — https://netty.io/
- **io.netty:netty-buffer** 4.2.18.Final — https://netty.io/
- **io.netty:netty-codec-base** 4.2.18.Final — https://netty.io/
- **io.netty:netty-codec-compression** 4.2.18.Final — https://netty.io/
- **io.netty:netty-codec-http2** 4.2.18.Final — https://netty.io/
- **io.netty:netty-codec-http** 4.2.18.Final — https://netty.io/
- **io.netty:netty-codec-marshalling** 4.2.18.Final — https://netty.io/
- **io.netty:netty-codec-protobuf** 4.2.18.Final — https://netty.io/
- **io.netty:netty-codec** 4.2.18.Final — https://netty.io/
- **io.netty:netty-common** 4.2.18.Final — https://netty.io/
- **io.netty:netty-handler** 4.2.18.Final — https://netty.io/
- **io.netty:netty-resolver** 4.2.18.Final — https://netty.io/
- **io.netty:netty-transport-classes-epoll** 4.2.18.Final — https://netty.io/
- **io.netty:netty-transport-classes-kqueue** 4.2.18.Final — https://netty.io/
- **io.netty:netty-transport-native-epoll** 4.2.18.Final — https://netty.io/
- **io.netty:netty-transport-native-kqueue** 4.2.18.Final — https://netty.io/
- **io.netty:netty-transport-native-unix-common** 4.2.18.Final — https://netty.io/
- **io.netty:netty-transport** 4.2.18.Final — https://netty.io/
- **io.opencensus:opencensus-api** 0.31.1 — https://github.com/census-instrumentation/opencensus-java
- **io.opencensus:opencensus-contrib-http-util** 0.31.1 — https://github.com/census-instrumentation/opencensus-java
- **io.opentelemetry.semconv:opentelemetry-semconv** 1.44.0 — https://github.com/open-telemetry/semantic-conventions-java
- **io.opentelemetry:opentelemetry-api** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-common** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-context** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-exporter-common** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-exporter-logging** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-exporter-otlp-common** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-exporter-otlp** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-exporter-sender-okhttp** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-sdk-common** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-sdk-logs** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-sdk-metrics** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-sdk-trace** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.opentelemetry:opentelemetry-sdk** 1.66.0 — https://github.com/open-telemetry/opentelemetry-java
- **io.prometheus:simpleclient** 0.16.0 — no project URL
- **io.prometheus:simpleclient_common** 0.16.0 — no project URL
- **io.prometheus:simpleclient_httpserver** 0.16.0 — no project URL
- **io.prometheus:simpleclient_tracer_common** 0.16.0 — no project URL
- **io.prometheus:simpleclient_tracer_otel** 0.16.0 — no project URL
- **io.prometheus:simpleclient_tracer_otel_agent** 0.16.0 — no project URL
- **io.swagger:swagger-annotations** 1.6.16 — https://github.com/swagger-api/swagger-core/modules/swagger-annotations
- **it.krzeminski:snakeyaml-engine-kmp-jvm** 4.0.1 — https://github.com/krzema12/snakeyaml-engine-kmp/
- **it.krzeminski:snakeyaml-engine-kmp** 4.0.1 — https://github.com/krzema12/snakeyaml-engine-kmp/
- **net.minidev:accessors-smart** 2.6.0 — https://urielch.github.io/
- **net.minidev:json-smart** 2.6.0 — https://urielch.github.io/
- **net.thauvin.erik.urlencoder:urlencoder-lib-jvm** 1.6.0 — https://github.com/ethauvin/urlencoder
- **net.thauvin.erik.urlencoder:urlencoder-lib** 1.6.0 — https://github.com/ethauvin/urlencoder
- **org.apache.commons:commons-collections4** 4.5.0 — https://commons.apache.org/proper/commons-collections/
  - Copyright 2001-2025 The Apache Software Foundation
- **org.apache.commons:commons-compress** 1.28.0 — https://commons.apache.org/proper/commons-compress/
  - Copyright 2002-2025 The Apache Software Foundation
- **org.apache.commons:commons-lang3** 3.20.0 — https://commons.apache.org/proper/commons-lang/
  - Copyright 2001-2025 The Apache Software Foundation
- **org.apache.httpcomponents.client5:httpclient5** 5.6.4 — no project URL
  - Copyright 1999-2021 The Apache Software Foundation
- **org.apache.httpcomponents.core5:httpcore5-h2** 5.4.4 — no project URL
  - Copyright 2005-2021 The Apache Software Foundation
- **org.apache.httpcomponents.core5:httpcore5** 5.4.4 — no project URL
  - Copyright 2005-2021 The Apache Software Foundation
- **org.apache.httpcomponents:httpclient** 4.5.14 — http://hc.apache.org/httpcomponents-client-ga
  - Copyright 1999-2022 The Apache Software Foundation
- **org.apache.httpcomponents:httpcore** 4.4.16 — http://hc.apache.org/httpcomponents-core-ga
  - Copyright 2005-2022 The Apache Software Foundation
- **org.apache.sshd:sshd-common** 2.20.0 — no project URL
  - Copyright 2018-2026 The Apache Software Foundation
- **org.apache.sshd:sshd-core** 2.20.0 — no project URL
  - Copyright 2008-2026 The Apache Software Foundation
- **org.apache.sshd:sshd-sftp** 2.20.0 — https://www.apache.org/
  - Copyright 2018-2026 The Apache Software Foundation
- **org.bitbucket.b_c:jose4j** 0.9.6 — https://bitbucket.org/b_c/jose4j/
- **org.fusesource.jansi:jansi** 2.4.3 — http://fusesource.com/
- **org.jetbrains.exposed:exposed-core** 1.5.0 — https://github.com/JetBrains/Exposed
- **org.jetbrains.exposed:exposed-jdbc** 1.5.0 — https://github.com/JetBrains/Exposed
- **org.jetbrains.kotlin:kotlin-reflect** 2.4.21 — https://kotlinlang.org/
- **org.jetbrains.kotlin:kotlin-stdlib** 2.4.21 — https://kotlinlang.org/
- **org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm** 0.5.0 — https://github.com/Kotlin/kotlinx.collections.immutable
- **org.jetbrains.kotlinx:kotlinx-collections-immutable** 0.5.0 — https://github.com/Kotlin/kotlinx.collections.immutable
- **org.jetbrains.kotlinx:kotlinx-coroutines-bom** 1.11.0 — https://github.com/Kotlin/kotlinx.coroutines
- **org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm** 1.11.0 — https://github.com/Kotlin/kotlinx.coroutines
- **org.jetbrains.kotlinx:kotlinx-coroutines-core** 1.11.0 — https://github.com/Kotlin/kotlinx.coroutines
- **org.jetbrains.kotlinx:kotlinx-coroutines-slf4j** 1.11.0 — https://github.com/Kotlin/kotlinx.coroutines
- **org.jetbrains.kotlinx:kotlinx-datetime-jvm** 0.7.1-0.6.x-compat — https://github.com/Kotlin/kotlinx-datetime
- **org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm** 0.9.1 — https://github.com/Kotlin/kotlinx-io
- **org.jetbrains.kotlinx:kotlinx-io-bytestring** 0.9.1 — https://github.com/Kotlin/kotlinx-io
- **org.jetbrains.kotlinx:kotlinx-io-core-jvm** 0.9.1 — https://github.com/Kotlin/kotlinx-io
- **org.jetbrains.kotlinx:kotlinx-io-core** 0.9.1 — https://github.com/Kotlin/kotlinx-io
- **org.jetbrains.kotlinx:kotlinx-serialization-bom** 1.11.0 — https://github.com/Kotlin/kotlinx.serialization
- **org.jetbrains.kotlinx:kotlinx-serialization-core-jvm** 1.11.0 — https://github.com/Kotlin/kotlinx.serialization
- **org.jetbrains.kotlinx:kotlinx-serialization-core** 1.11.0 — https://github.com/Kotlin/kotlinx.serialization
- **org.jetbrains.kotlinx:kotlinx-serialization-json-io-jvm** 1.11.0 — https://github.com/Kotlin/kotlinx.serialization
- **org.jetbrains.kotlinx:kotlinx-serialization-json-io** 1.11.0 — https://github.com/Kotlin/kotlinx.serialization
- **org.jetbrains.kotlinx:kotlinx-serialization-json-jvm** 1.11.0 — https://github.com/Kotlin/kotlinx.serialization
- **org.jetbrains.kotlinx:kotlinx-serialization-json** 1.11.0 — https://github.com/Kotlin/kotlinx.serialization
- **org.jetbrains:annotations** 23.0.0 — https://github.com/JetBrains/java-annotations
- **org.jspecify:jspecify** 1.0.0 — http://jspecify.org/
- **org.snakeyaml:snakeyaml-engine** 3.0.1 — https://bitbucket.org/snakeyaml/snakeyaml-engine
- **org.xerial:sqlite-jdbc** 3.53.4.0 — https://github.com/xerial/sqlite-jdbc
  - Copyright (c) 2006, David Crawshaw.  All rights reserved.
- **org.yaml:snakeyaml** 2.6 — https://bitbucket.org/snakeyaml/snakeyaml
- **software.amazon.awssdk:annotations** 2.46.18 — no project URL
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:apache5-client** 2.46.18 — no project URL
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:auth** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:aws-core** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:aws-query-protocol** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:checksums-spi** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:checksums** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:endpoints-spi** 2.46.18 — no project URL
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:http-auth-aws-eventstream** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:http-auth-aws** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:http-auth-spi** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:http-auth** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:http-client-spi** 2.46.18 — no project URL
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:identity-spi** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:json-utils** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:metrics-spi** 2.46.18 — no project URL
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:netty-nio-client** 2.46.18 — no project URL
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:profiles** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:protocol-core** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:regions** 2.46.18 — no project URL
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:retries-spi** 2.46.18 — no project URL
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:retries** 2.46.18 — no project URL
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:sdk-core** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:sts** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:third-party-jackson-core** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)
  - Copyright © 2023 Werner Randelshofer, Switzerland. MIT License.
  - Copyright 2018-2020 Raffaello Giulietti
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:utils-lite** 2.46.18 — https://aws.amazon.com/sdkforjava
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.awssdk:utils** 2.46.18 — no project URL
  - Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  - Copyright 2001-2020 The Apache Software Foundation
- **software.amazon.eventstream:eventstream** 1.0.1 — https://github.com/awslabs/aws-eventstream-java
- **tools.jackson.core:jackson-core** 3.2.3 — https://github.com/FasterXML/jackson-core
  - Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)
  - Copyright © 2023 Werner Randelshofer, Switzerland. MIT License.
  - Copyright 2018-2020 Raffaello Giulietti
- **tools.jackson.core:jackson-databind** 3.2.3 — https://github.com/FasterXML/jackson
  - Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)
- **tools.jackson.dataformat:jackson-dataformat-yaml** 3.2.3 — https://github.com/FasterXML/jackson-dataformats-text
  - Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)
- **tools.jackson:jackson-bom** 3.2.3 — https://github.com/FasterXML/jackson-bom

#### Apache License, Version 2.0 OR Eclipse Public License - v 1.0

- **org.eclipse.jetty.alpn:alpn-api** 1.1.3.v20160715 — http://www.eclipse.org/jetty/documentation/current/alpn-chapter.html

#### Apache License, Version 2.0 OR GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1

- **net.java.dev.jna:jna** 5.14.0 — https://github.com/java-native-access/jna

#### Apache License, Version 2.0 OR MIT License

- **org.slf4j:jcl-over-slf4j** 1.7.36 — http://www.slf4j.org

#### Apache License, Version 2.0 OR The 3-Clause BSD License

- **com.google.api:api-common** 2.64.0 — no project URL
- **com.google.auth:google-auth-library-credentials** 1.48.0 — no project URL
- **com.google.auth:google-auth-library-oauth2-http** 1.48.0 — no project URL
- **org.ow2.asm:asm** 9.7.1 — http://asm.ow2.io/

#### CDDL + GPLv2 with classpath exception OR COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0

- **javax.annotation:javax.annotation-api** 1.3.2 — http://jcp.org/en/jsr/detail?id=250

#### Eclipse Public License - v 2.0 OR GNU GENERAL PUBLIC LICENSE, Version 2 OR GNU GENERAL PUBLIC LICENSE, Version 2 + Classpath Exception

- **jakarta.annotation:jakarta.annotation-api** 3.0.0 — https://projects.eclipse.org/projects/ee4j.ca

#### Eclipse Public License - v 2.0 OR GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1

- **ch.qos.logback:logback-classic** 1.6.5 — http://www.qos.ch
- **ch.qos.logback:logback-core** 1.6.5 — http://www.qos.ch

#### MIT License

- **com.github.ajalt.colormath:colormath-jvm** 3.6.0 — https://github.com/ajalt/colormath/
- **com.github.ajalt.colormath:colormath** 3.6.0 — https://github.com/ajalt/colormath/
- **com.langfuse:langfuse-java** 0.3.0 — https://langfuse.com
- **io.modelcontextprotocol:kotlin-sdk-core-jvm** 0.14.0 — https://github.com/modelcontextprotocol/kotlin-sdk
- **io.modelcontextprotocol:kotlin-sdk-core** 0.14.0 — https://github.com/modelcontextprotocol/kotlin-sdk
- **io.modelcontextprotocol:kotlin-sdk-server-jvm** 0.14.0 — https://github.com/modelcontextprotocol/kotlin-sdk
- **io.modelcontextprotocol:kotlin-sdk-server** 0.14.0 — https://github.com/modelcontextprotocol/kotlin-sdk
- **org.bouncycastle:bcpkix-jdk18on** 1.86 — https://www.bouncycastle.org/download/bouncy-castle-java/
  - Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org).
- **org.bouncycastle:bcprov-jdk18on** 1.86 — https://www.bouncycastle.org/download/bouncy-castle-java/
  - Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org).
- **org.bouncycastle:bcutil-jdk18on** 1.86 — https://www.bouncycastle.org/download/bouncy-castle-java/
  - Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org).
- **org.slf4j:slf4j-api** 2.0.19 — http://www.slf4j.org
  - Copyright (c) 2004-2023 QOS.ch
- **org.slf4j:slf4j-api** 2.0.20 — http://www.slf4j.org
  - Copyright (c) 2004-2023 QOS.ch
- **org.slf4j:slf4j-simple** 2.0.20 — http://www.slf4j.org
  - Copyright (c) 2004-2023 QOS.ch

#### MIT-0

- **org.reactivestreams:reactive-streams** 1.0.4 — http://reactive-streams.org

#### The 3-Clause BSD License

- **com.google.protobuf:protobuf-java** 4.35.1 — https://developers.google.com/protocol-buffers/

<!-- END GENERATED: backend -->

## Homer console

The console's production npm dependency graph, taken from `package-lock.json`:
the superset of what the server bundle and the client bundles can redistribute
between them. The lock rather than a built tree because it is
platform-independent - the inventory cannot drift between a darwin and a linux
build's swc binaries - and because it needs no build to check. `sharp` and
`@img/*` are excluded, together with everything nested under them:
`console/Dockerfile` deletes those directories before the runner copies the
standalone tree, which is what keeps Homer from conveying their LGPL-3.0
binaries. Packages the lock restricts by `os` or `cpu` are listed from
the lock alone, so they carry no attributions.

<!-- BEGIN GENERATED: console (npm run licenses:generate) -->

#### 0BSD

- **tslib** 2.8.1 — https://www.typescriptlang.org/
  - Copyright (c) Microsoft Corporation.

#### Apache License, Version 2.0

- **@swc/helpers** 0.5.23 — https://swc.rs
  - Copyright 2024 SWC contributors.
- **baseline-browser-mapping** 2.11.28 — https://github.com/web-platform-dx/baseline-browser-mapping
- **class-variance-authority** 0.7.1 — https://github.com/joe-bell/cva#readme
  - Copyright 2022 Joe Bell
- **detect-libc** 2.1.2 — https://github.com/lovell/detect-libc

#### CC-BY-4.0

- **caniuse-lite** 1.0.30001815 — https://github.com/browserslist/caniuse-lite

#### ISC

- **@ungap/structured-clone** 1.3.1 — https://github.com/ungap/structured-clone#readme
  - Copyright (c) 2021, Andrea Giammarchi, @WebReflection
- **lucide-react** 1.53.0 — https://lucide.dev
  - Copyright (c) 2026 Lucide Icons and Contributors
  - Copyright (c) 2013-present Cole Bemis
- **picocolors** 1.1.1 — https://github.com/alexeyraspopov/picocolors
  - Copyright (c) 2021-2024 Oleksii Raspopov, Kostiantyn Denysov, Anton Verinov
- **semver** 7.8.5 — https://github.com/npm/node-semver
  - Copyright (c) Isaac Z. Schlueter and Contributors
- **yaml** 2.9.1 — https://eemeli.org/yaml/
  - Copyright Eemeli Aro <eemeli@gmail.com>

#### MIT License

- **@dagrejs/dagre** 3.1.1 — https://github.com/dagrejs/dagre
  - Copyright (c) 2012-2014 Chris Pettitt
- **@dagrejs/graphlib** 4.0.5 — https://github.com/dagrejs/graphlib
  - Copyright (c) 2012-2014 Chris Pettitt
- **@floating-ui/core** 1.8.0 — https://floating-ui.com
  - Copyright (c) 2021-present Floating UI contributors
- **@floating-ui/dom** 1.8.0 — https://floating-ui.com
  - Copyright (c) 2021-present Floating UI contributors
- **@floating-ui/react-dom** 2.1.9 — https://floating-ui.com/docs/react-dom
  - Copyright (c) 2021-present Floating UI contributors
- **@floating-ui/utils** 0.2.12 — https://floating-ui.com
  - Copyright (c) 2021-present Floating UI contributors
- **@microsoft/fetch-event-source** 2.0.1 — https://github.com/Azure/fetch-event-source#readme
  - Copyright (c) Microsoft Corporation.
- **@monaco-editor/loader** 1.7.0 — https://github.com/suren-atoyan/monaco-loader
  - Copyright (c) 2021 Suren Atoyan
- **@monaco-editor/react** 4.7.0 — https://github.com/suren-atoyan/monaco-react#readme
  - Copyright (c) 2018 Suren Atoyan
- **@next/env** 16.4.0 — https://github.com/vercel/next.js
- **@next/swc-darwin-arm64** 16.4.0 — no project URL
- **@next/swc-darwin-x64** 16.4.0 — no project URL
- **@next/swc-linux-arm64-gnu** 16.4.0 — no project URL
- **@next/swc-linux-arm64-musl** 16.4.0 — no project URL
- **@next/swc-linux-x64-gnu** 16.4.0 — no project URL
- **@next/swc-linux-x64-musl** 16.4.0 — no project URL
- **@next/swc-win32-arm64-msvc** 16.4.0 — no project URL
- **@next/swc-win32-x64-msvc** 16.4.0 — no project URL
- **@radix-ui/number** 1.1.3 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/primitive** 1.1.7 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-arrow** 1.1.16 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-collection** 1.1.16 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-compose-refs** 1.1.5 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-context** 1.2.2 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-dialog** 1.2.0 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-direction** 1.1.5 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-dismissable-layer** 1.1.20 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-dropdown-menu** 2.1.25 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-focus-guards** 1.1.6 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-focus-scope** 1.2.0 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-id** 1.1.4 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-label** 2.1.16 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-menu** 2.1.25 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-popover** 1.2.0 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-popper** 1.3.8 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-portal** 1.1.18 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-presence** 1.1.11 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-primitive** 2.1.11 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-roving-focus** 1.1.20 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-scroll-area** 1.3.0 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-slot** 1.4.0 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-tabs** 1.1.22 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-use-callback-ref** 1.1.4 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-use-controllable-state** 1.2.6 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-use-effect-event** 0.0.5 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-use-is-hydrated** 0.1.3 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-use-layout-effect** 1.1.4 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-use-rect** 1.1.4 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/react-use-size** 1.1.5 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@radix-ui/rect** 1.1.3 — https://radix-ui.com/primitives
  - Copyright (c) 2022 WorkOS
- **@tanstack/query-core** 5.104.1 — https://tanstack.com/query
  - Copyright (c) 2021-present Tanner Linsley
- **@tanstack/react-query** 5.104.1 — https://tanstack.com/query
  - Copyright (c) 2021-present Tanner Linsley
- **@types/debug** 4.1.13 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/debug
  - Copyright (c) Microsoft Corporation.
- **@types/estree-jsx** 1.0.5 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/estree-jsx
  - Copyright (c) Microsoft Corporation.
- **@types/estree** 1.0.8 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/estree
  - Copyright (c) Microsoft Corporation.
- **@types/hast** 3.0.4 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/hast
  - Copyright (c) Microsoft Corporation.
- **@types/mdast** 4.0.4 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/mdast
  - Copyright (c) Microsoft Corporation.
- **@types/ms** 2.1.0 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/ms
  - Copyright (c) Microsoft Corporation.
- **@types/react-dom** 19.3.0 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/react-dom
  - Copyright (c) Microsoft Corporation.
- **@types/react** 19.3.0 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/react
  - Copyright (c) Microsoft Corporation.
- **@types/trusted-types** 2.0.7 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/trusted-types
  - Copyright (c) Microsoft Corporation.
- **@types/unist** 2.0.11 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/unist
  - Copyright (c) Microsoft Corporation.
- **@types/unist** 3.0.3 — https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/unist
  - Copyright (c) Microsoft Corporation.
- **@vscode/l10n** 0.0.18 — https://github.com/Microsoft/vscode-l10n
- **aria-hidden** 1.2.6 — https://github.com/theKashey/aria-hidden#readme
  - Copyright (c) 2017 Anton Korzunov
- **bail** 2.0.2 — https://github.com/wooorm/bail
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **ccount** 2.0.1 — https://github.com/wooorm/ccount
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **character-entities-html4** 2.1.0 — https://github.com/wooorm/character-entities-html4
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **character-entities-legacy** 3.0.0 — https://github.com/wooorm/character-entities-legacy
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **character-entities** 2.0.2 — https://github.com/wooorm/character-entities
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **character-reference-invalid** 2.0.1 — https://github.com/wooorm/character-reference-invalid
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **client-only** 0.0.1 — https://reactjs.org/
- **clsx** 2.1.1 — https://github.com/lukeed/clsx
  - Copyright (c) Luke Edwards <luke.edwards05@gmail.com> (lukeed.com)
- **cmdk** 1.1.1 — https://github.com/pacocoursey/cmdk#readme
  - Copyright (c) 2022 Paco Coursey
- **comma-separated-tokens** 2.0.3 — https://github.com/wooorm/comma-separated-tokens
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **csstype** 3.2.3 — https://github.com/frenic/csstype
  - Copyright (c) 2017-2018 Fredrik Nicol
- **debug** 4.4.3 — https://github.com/debug-js/debug
  - Copyright (c) 2014-2017 TJ Holowaychuk <tj@vision-media.ca>
  - Copyright (c) 2018-2021 Josh Junon
- **decode-named-character-reference** 1.3.0 — https://github.com/wooorm/decode-named-character-reference
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **dequal** 2.0.3 — https://github.com/lukeed/dequal
  - Copyright (c) Luke Edwards <luke.edwards05@gmail.com> (lukeed.com)
- **detect-node-es** 1.1.0 — https://github.com/thekashey/detect-node
  - Copyright (c) 2017 Ilya Kantor
- **devlop** 1.1.0 — https://github.com/wooorm/devlop
  - Copyright (c) 2023 Titus Wormer <tituswormer@gmail.com>
- **estree-util-is-identifier-name** 3.0.0 — https://github.com/syntax-tree/estree-util-is-identifier-name
  - Copyright (c) 2020 Titus Wormer <tituswormer@gmail.com>
- **extend** 3.0.2 — https://github.com/justmoon/node-extend
  - Copyright (c) 2014 Stefan Thomas
- **get-nonce** 1.0.1 — https://github.com/theKashey/get-nonce
  - Copyright (c) 2020 Anton Korzunov
- **hast-util-to-jsx-runtime** 2.3.6 — https://github.com/syntax-tree/hast-util-to-jsx-runtime
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **hast-util-whitespace** 3.0.0 — https://github.com/syntax-tree/hast-util-whitespace
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **html-url-attributes** 3.0.1 — https://github.com/rehypejs/rehype-minify/tree/main/packages/html-url-attributes
  - Copyright (c) Titus Wormer
- **inline-style-parser** 0.2.7 — https://github.com/remarkablemark/inline-style-parser
  - Copyright (c) 2012 TJ Holowaychuk <tj@vision-media.ca>
- **is-alphabetical** 2.0.1 — https://github.com/wooorm/is-alphabetical
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **is-alphanumerical** 2.0.1 — https://github.com/wooorm/is-alphanumerical
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **is-decimal** 2.0.1 — https://github.com/wooorm/is-decimal
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **is-hexadecimal** 2.0.1 — https://github.com/wooorm/is-hexadecimal
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **is-plain-obj** 4.1.0 — https://github.com/sindresorhus/is-plain-obj
  - Copyright (c) Sindre Sorhus <sindresorhus@gmail.com> (https://sindresorhus.com)
- **jsonc-parser** 3.3.1 — https://github.com/microsoft/node-jsonc-parser
  - Copyright (c) Microsoft
- **longest-streak** 3.1.0 — https://github.com/wooorm/longest-streak
  - Copyright (c) 2015 Titus Wormer <mailto:tituswormer@gmail.com>
- **marked** 14.0.0 — https://marked.js.org
  - Copyright (c) 2018+, MarkedJS (https://github.com/markedjs/)
  - Copyright (c) 2011-2018, Christopher Jeffrey (https://github.com/chjj/)
  - Copyright © 2004, John Gruber
- **mdast-util-from-markdown** 2.0.3 — https://github.com/syntax-tree/mdast-util-from-markdown
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **mdast-util-mdx-expression** 2.0.1 — https://github.com/syntax-tree/mdast-util-mdx-expression
  - Copyright (c) 2020 Titus Wormer <tituswormer@gmail.com>
- **mdast-util-mdx-jsx** 3.2.0 — https://github.com/syntax-tree/mdast-util-mdx-jsx
  - Copyright (c) 2020 Titus Wormer <tituswormer@gmail.com>
- **mdast-util-mdxjs-esm** 2.0.1 — https://github.com/syntax-tree/mdast-util-mdxjs-esm
  - Copyright (c) 2020 Titus Wormer <tituswormer@gmail.com>
- **mdast-util-phrasing** 4.1.0 — https://github.com/syntax-tree/mdast-util-phrasing
  - Copyright (c) 2017 Titus Wormer <tituswormer@gmail.com>
  - Copyright (c) 2017 Victor Felder <victor@draft.li>
- **mdast-util-to-hast** 13.2.1 — https://github.com/syntax-tree/mdast-util-to-hast
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **mdast-util-to-markdown** 2.1.2 — https://github.com/syntax-tree/mdast-util-to-markdown
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **mdast-util-to-string** 4.0.0 — https://github.com/syntax-tree/mdast-util-to-string
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **micromark-core-commonmark** 2.0.3 — https://github.com/micromark/micromark/tree/main/packages/micromark-core-commonmark
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-factory-destination** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-factory-destination
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-factory-label** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-factory-label
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-factory-space** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-factory-space
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-factory-title** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-factory-title
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-factory-whitespace** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-factory-whitespace
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-character** 2.1.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-character
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-chunked** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-chunked
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-classify-character** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-classify-character
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-combine-extensions** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-combine-extensions
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-decode-numeric-character-reference** 2.0.2 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-decode-numeric-character-reference
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-decode-string** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-decode-string
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-encode** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-encode
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-html-tag-name** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-html-tag-name
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-normalize-identifier** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-normalize-identifier
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-resolve-all** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-resolve-all
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-sanitize-uri** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-sanitize-uri
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-subtokenize** 2.1.0 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-subtokenize
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-symbol** 2.0.1 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-symbol
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark-util-types** 2.0.2 — https://github.com/micromark/micromark/tree/main/packages/micromark-util-types
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **micromark** 4.0.2 — https://github.com/micromark/micromark/tree/main/packages/micromark
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **monaco-editor** 0.55.1 — https://github.com/microsoft/monaco-editor
  - Copyright (c) 2016 - present Microsoft Corporation
- **monaco-languageserver-types** 0.4.0 — https://github.com/remcohaszing/monaco-languageserver-types
  - Copyright © 2023 Remco Haszing
- **monaco-marker-data-provider** 1.2.5 — https://github.com/remcohaszing/monaco-marker-data-provider#readme
  - Copyright © 2022 Remco Haszing
- **monaco-types** 0.1.2 — https://github.com/remcohaszing/monaco-types#readme
  - Copyright © 2021 Remco Haszing
- **monaco-worker-manager** 2.0.1 — https://github.com/remcohaszing/monaco-worker-manager#readme
  - Copyright © 2022 Remco Haszing
- **monaco-yaml** 5.5.1 — https://monaco-yaml.js.org
  - Copyright (c) Microsoft Corporation
- **ms** 2.1.3 — https://github.com/vercel/ms
  - Copyright (c) 2020 Vercel, Inc.
- **nanoid** 3.3.20 — https://github.com/ai/nanoid
  - Copyright 2017 Andrey Sitnik <andrey@sitnik.ru>
- **next-themes** 0.4.6 — https://github.com/pacocoursey/next-themes
  - Copyright (c) 2022 Paco Coursey
- **next** 16.4.0 — https://nextjs.org
  - Copyright (c) 2025 Vercel, Inc.
- **parse-entities** 4.0.2 — https://github.com/wooorm/parse-entities
  - Copyright (c) Titus Wormer <mailto:tituswormer@gmail.com>
- **path-browserify** 1.0.1 — https://github.com/browserify/path-browserify
  - Copyright (c) 2013 James Halliday
- **postcss** 8.5.29 — https://postcss.org/
  - Copyright 2013 Andrey Sitnik <andrey@sitnik.es>
- **prettier** 3.8.3 — https://prettier.io
  - Copyright © James Long and contributors
- **property-information** 7.2.0 — https://github.com/wooorm/property-information
  - Copyright (c) Titus Wormer <mailto:tituswormer@gmail.com>
- **proxy-disposable** 1.0.0 — https://github.com/remcohaszing/proxy-disposable#readme
  - Copyright © 2026 Remco Haszing
- **react-dom** 19.3.0 — https://react.dev/
  - Copyright (c) Meta Platforms, Inc. and affiliates.
- **react-json-view-lite** 2.5.0 — https://github.com/AnyRoad/react-json-view-lite
  - Copyright (c) 2024 AnyRoad
- **react-markdown** 10.1.0 — https://github.com/remarkjs/react-markdown
  - Copyright (c) Espen Hovlandsdal
- **react-remove-scroll-bar** 2.3.8 — https://github.com/theKashey/react-remove-scroll-bar
- **react-remove-scroll** 2.7.2 — https://github.com/theKashey/react-remove-scroll
  - Copyright (c) 2017 Anton Korzunov
- **react-style-singleton** 2.2.3 — https://github.com/theKashey/react-style-singleton#readme
  - Copyright (c) 2017 Anton Korzunov
- **react** 19.3.0 — https://react.dev/
  - Copyright (c) Meta Platforms, Inc. and affiliates.
- **remark-parse** 11.0.0 — https://remark.js.org
  - Copyright (c) 2014 Titus Wormer <tituswormer@gmail.com>
- **remark-rehype** 11.1.2 — https://github.com/remarkjs/remark-rehype
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **scheduler** 0.28.0 — https://react.dev/
  - Copyright (c) Meta Platforms, Inc. and affiliates.
- **space-separated-tokens** 2.0.2 — https://github.com/wooorm/space-separated-tokens
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **state-local** 1.0.7 — https://github.com/suren-atoyan/state-local#readme
  - Copyright (c) 2020 Suren Atoyan
- **stringify-entities** 4.0.4 — https://github.com/wooorm/stringify-entities
  - Copyright (c) 2015 Titus Wormer <mailto:tituswormer@gmail.com>
- **style-to-js** 1.1.21 — https://github.com/remarkablemark/style-to-js
  - Copyright (c) 2020 Menglin "Mark" Xu <mark@remarkablemark.org>
- **style-to-object** 1.0.14 — https://github.com/remarkablemark/style-to-object
  - Copyright (c) 2017 Menglin "Mark" Xu <mark@remarkablemark.org>
- **styled-jsx** 5.1.6 — https://github.com/vercel/styled-jsx
  - Copyright (c) 2016-present Vercel, Inc.
- **tailwind-merge** 3.7.0 — https://github.com/dcastil/tailwind-merge
  - Copyright (c) 2021 Dany Castillo
- **trim-lines** 3.0.1 — https://github.com/wooorm/trim-lines
  - Copyright (c) 2015 Titus Wormer <mailto:tituswormer@gmail.com>
- **trough** 2.2.0 — https://github.com/wooorm/trough
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **unified** 11.0.5 — https://unifiedjs.com
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **unist-util-is** 6.0.1 — https://github.com/syntax-tree/unist-util-is
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **unist-util-position** 5.0.0 — https://github.com/syntax-tree/unist-util-position
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **unist-util-stringify-position** 4.0.0 — https://github.com/syntax-tree/unist-util-stringify-position
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **unist-util-visit-parents** 6.0.2 — https://github.com/syntax-tree/unist-util-visit-parents
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>
- **unist-util-visit** 5.1.0 — https://github.com/syntax-tree/unist-util-visit
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **use-callback-ref** 1.3.3 — https://github.com/theKashey/use-callback-ref/
  - Copyright (c) 2017 Anton Korzunov
- **use-sidecar** 1.1.3 — https://github.com/theKashey/use-sidecar
  - Copyright (c) 2017 Anton Korzunov
- **vfile-message** 4.0.3 — https://github.com/vfile/vfile-message
  - Copyright (c) Titus Wormer <tituswormer@gmail.com>
- **vfile** 6.0.3 — https://github.com/vfile/vfile
  - Copyright (c) 2015 Titus Wormer <tituswormer@gmail.com>
- **vscode-jsonrpc** 8.2.0 — https://github.com/Microsoft/vscode-languageserver-node
  - Copyright (c) Microsoft Corporation
- **vscode-languageserver-protocol** 3.17.5 — https://github.com/Microsoft/vscode-languageserver-node
  - Copyright (c) Microsoft Corporation
- **vscode-languageserver-textdocument** 1.0.12 — https://github.com/Microsoft/vscode-languageserver-node
  - Copyright (c) Microsoft Corporation
- **vscode-languageserver-types** 3.17.5 — https://github.com/Microsoft/vscode-languageserver-node
  - Copyright (c) Microsoft Corporation
- **vscode-uri** 3.1.0 — https://github.com/microsoft/vscode-uri#readme
  - Copyright (c) Microsoft
- **zwitch** 2.0.4 — https://github.com/wooorm/zwitch
  - Copyright (c) 2016 Titus Wormer <tituswormer@gmail.com>

#### MPL-2.0 OR Apache License, Version 2.0

- **dompurify** 3.4.16 — https://github.com/cure53/DOMPurify

#### The 3-Clause BSD License

- **source-map-js** 1.2.2 — https://github.com/7rulnik/source-map-js
  - Copyright (c) 2009-2011, Mozilla Foundation and contributors

<!-- END GENERATED: console -->

## Base images and the Python client

These are redistributed as-is inside the Docker images, or not redistributed at
all, so they are not part of the generated inventories above:

- **Eclipse Temurin** (backend image base) - OpenJDK under GPL-2.0 with the
  Classpath Exception. See <https://adoptium.net/about/> and the
  `/legal` directory inside the image.
- **Node.js** (console image base) - MIT. See
  <https://github.com/nodejs/node/blob/main/LICENSE>.
- **Alpine Linux** (image base) - licensed per package. See
  `/usr/share/licenses` inside the image, or the Alpine package index at
  <https://pkgs.alpinelinux.org/packages>.
- **The Python client** under [`clients/python`](clients/python) installs
  `langgraph` and `langgraph-checkpoint-sqlite` via pip at deploy time rather
  than bundling them, so Homer does not redistribute them.

## License texts

One canonical text per license type appearing in either inventory above. Each
text is reproduced verbatim from the source named in the comment under its
heading; the placeholder forms (`<year>`, `<owner>`) are the license stewards'
own templates - the actual copyright holders are listed with each package.

### 0BSD

<!-- Source: https://raw.githubusercontent.com/spdx/license-list-data/main/text/0BSD.txt -->

```text
Copyright (C) YEAR by AUTHOR EMAIL

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

### Apache License, Version 2.0

<!-- Source: the repository's own LICENSE file -->

```text
Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

### CC-BY-4.0

<!-- Source: bundled LICENSE of caniuse-lite 1.0.30001805 -->

```text
Attribution 4.0 International

=======================================================================

Creative Commons Corporation ("Creative Commons") is not a law firm and
does not provide legal services or legal advice. Distribution of
Creative Commons public licenses does not create a lawyer-client or
other relationship. Creative Commons makes its licenses and related
information available on an "as-is" basis. Creative Commons gives no
warranties regarding its licenses, any material licensed under their
terms and conditions, or any related information. Creative Commons
disclaims all liability for damages resulting from their use to the
fullest extent possible.

Using Creative Commons Public Licenses

Creative Commons public licenses provide a standard set of terms and
conditions that creators and other rights holders may use to share
original works of authorship and other material subject to copyright
and certain other rights specified in the public license below. The
following considerations are for informational purposes only, are not
exhaustive, and do not form part of our licenses.

     Considerations for licensors: Our public licenses are
     intended for use by those authorized to give the public
     permission to use material in ways otherwise restricted by
     copyright and certain other rights. Our licenses are
     irrevocable. Licensors should read and understand the terms
     and conditions of the license they choose before applying it.
     Licensors should also secure all rights necessary before
     applying our licenses so that the public can reuse the
     material as expected. Licensors should clearly mark any
     material not subject to the license. This includes other CC-
     licensed material, or material used under an exception or
     limitation to copyright. More considerations for licensors:
	wiki.creativecommons.org/Considerations_for_licensors

     Considerations for the public: By using one of our public
     licenses, a licensor grants the public permission to use the
     licensed material under specified terms and conditions. If
     the licensor's permission is not necessary for any reason--for
     example, because of any applicable exception or limitation to
     copyright--then that use is not regulated by the license. Our
     licenses grant only permissions under copyright and certain
     other rights that a licensor has authority to grant. Use of
     the licensed material may still be restricted for other
     reasons, including because others have copyright or other
     rights in the material. A licensor may make special requests,
     such as asking that all changes be marked or described.
     Although not required by our licenses, you are encouraged to
     respect those requests where reasonable. More_considerations
     for the public: 
	wiki.creativecommons.org/Considerations_for_licensees

=======================================================================

Creative Commons Attribution 4.0 International Public License

By exercising the Licensed Rights (defined below), You accept and agree
to be bound by the terms and conditions of this Creative Commons
Attribution 4.0 International Public License ("Public License"). To the
extent this Public License may be interpreted as a contract, You are
granted the Licensed Rights in consideration of Your acceptance of
these terms and conditions, and the Licensor grants You such rights in
consideration of benefits the Licensor receives from making the
Licensed Material available under these terms and conditions.


Section 1 -- Definitions.

  a. Adapted Material means material subject to Copyright and Similar
     Rights that is derived from or based upon the Licensed Material
     and in which the Licensed Material is translated, altered,
     arranged, transformed, or otherwise modified in a manner requiring
     permission under the Copyright and Similar Rights held by the
     Licensor. For purposes of this Public License, where the Licensed
     Material is a musical work, performance, or sound recording,
     Adapted Material is always produced where the Licensed Material is
     synched in timed relation with a moving image.

  b. Adapter's License means the license You apply to Your Copyright
     and Similar Rights in Your contributions to Adapted Material in
     accordance with the terms and conditions of this Public License.

  c. Copyright and Similar Rights means copyright and/or similar rights
     closely related to copyright including, without limitation,
     performance, broadcast, sound recording, and Sui Generis Database
     Rights, without regard to how the rights are labeled or
     categorized. For purposes of this Public License, the rights
     specified in Section 2(b)(1)-(2) are not Copyright and Similar
     Rights.

  d. Effective Technological Measures means those measures that, in the
     absence of proper authority, may not be circumvented under laws
     fulfilling obligations under Article 11 of the WIPO Copyright
     Treaty adopted on December 20, 1996, and/or similar international
     agreements.

  e. Exceptions and Limitations means fair use, fair dealing, and/or
     any other exception or limitation to Copyright and Similar Rights
     that applies to Your use of the Licensed Material.

  f. Licensed Material means the artistic or literary work, database,
     or other material to which the Licensor applied this Public
     License.

  g. Licensed Rights means the rights granted to You subject to the
     terms and conditions of this Public License, which are limited to
     all Copyright and Similar Rights that apply to Your use of the
     Licensed Material and that the Licensor has authority to license.

  h. Licensor means the individual(s) or entity(ies) granting rights
     under this Public License.

  i. Share means to provide material to the public by any means or
     process that requires permission under the Licensed Rights, such
     as reproduction, public display, public performance, distribution,
     dissemination, communication, or importation, and to make material
     available to the public including in ways that members of the
     public may access the material from a place and at a time
     individually chosen by them.

  j. Sui Generis Database Rights means rights other than copyright
     resulting from Directive 96/9/EC of the European Parliament and of
     the Council of 11 March 1996 on the legal protection of databases,
     as amended and/or succeeded, as well as other essentially
     equivalent rights anywhere in the world.

  k. You means the individual or entity exercising the Licensed Rights
     under this Public License. Your has a corresponding meaning.


Section 2 -- Scope.

  a. License grant.

       1. Subject to the terms and conditions of this Public License,
          the Licensor hereby grants You a worldwide, royalty-free,
          non-sublicensable, non-exclusive, irrevocable license to
          exercise the Licensed Rights in the Licensed Material to:

            a. reproduce and Share the Licensed Material, in whole or
               in part; and

            b. produce, reproduce, and Share Adapted Material.

       2. Exceptions and Limitations. For the avoidance of doubt, where
          Exceptions and Limitations apply to Your use, this Public
          License does not apply, and You do not need to comply with
          its terms and conditions.

       3. Term. The term of this Public License is specified in Section
          6(a).

       4. Media and formats; technical modifications allowed. The
          Licensor authorizes You to exercise the Licensed Rights in
          all media and formats whether now known or hereafter created,
          and to make technical modifications necessary to do so. The
          Licensor waives and/or agrees not to assert any right or
          authority to forbid You from making technical modifications
          necessary to exercise the Licensed Rights, including
          technical modifications necessary to circumvent Effective
          Technological Measures. For purposes of this Public License,
          simply making modifications authorized by this Section 2(a)
          (4) never produces Adapted Material.

       5. Downstream recipients.

            a. Offer from the Licensor -- Licensed Material. Every
               recipient of the Licensed Material automatically
               receives an offer from the Licensor to exercise the
               Licensed Rights under the terms and conditions of this
               Public License.

            b. No downstream restrictions. You may not offer or impose
               any additional or different terms or conditions on, or
               apply any Effective Technological Measures to, the
               Licensed Material if doing so restricts exercise of the
               Licensed Rights by any recipient of the Licensed
               Material.

       6. No endorsement. Nothing in this Public License constitutes or
          may be construed as permission to assert or imply that You
          are, or that Your use of the Licensed Material is, connected
          with, or sponsored, endorsed, or granted official status by,
          the Licensor or others designated to receive attribution as
          provided in Section 3(a)(1)(A)(i).

  b. Other rights.

       1. Moral rights, such as the right of integrity, are not
          licensed under this Public License, nor are publicity,
          privacy, and/or other similar personality rights; however, to
          the extent possible, the Licensor waives and/or agrees not to
          assert any such rights held by the Licensor to the limited
          extent necessary to allow You to exercise the Licensed
          Rights, but not otherwise.

       2. Patent and trademark rights are not licensed under this
          Public License.

       3. To the extent possible, the Licensor waives any right to
          collect royalties from You for the exercise of the Licensed
          Rights, whether directly or through a collecting society
          under any voluntary or waivable statutory or compulsory
          licensing scheme. In all other cases the Licensor expressly
          reserves any right to collect such royalties.


Section 3 -- License Conditions.

Your exercise of the Licensed Rights is expressly made subject to the
following conditions.

  a. Attribution.

       1. If You Share the Licensed Material (including in modified
          form), You must:

            a. retain the following if it is supplied by the Licensor
               with the Licensed Material:

                 i. identification of the creator(s) of the Licensed
                    Material and any others designated to receive
                    attribution, in any reasonable manner requested by
                    the Licensor (including by pseudonym if
                    designated);

                ii. a copyright notice;

               iii. a notice that refers to this Public License;

                iv. a notice that refers to the disclaimer of
                    warranties;

                 v. a URI or hyperlink to the Licensed Material to the
                    extent reasonably practicable;

            b. indicate if You modified the Licensed Material and
               retain an indication of any previous modifications; and

            c. indicate the Licensed Material is licensed under this
               Public License, and include the text of, or the URI or
               hyperlink to, this Public License.

       2. You may satisfy the conditions in Section 3(a)(1) in any
          reasonable manner based on the medium, means, and context in
          which You Share the Licensed Material. For example, it may be
          reasonable to satisfy the conditions by providing a URI or
          hyperlink to a resource that includes the required
          information.

       3. If requested by the Licensor, You must remove any of the
          information required by Section 3(a)(1)(A) to the extent
          reasonably practicable.

       4. If You Share Adapted Material You produce, the Adapter's
          License You apply must not prevent recipients of the Adapted
          Material from complying with this Public License.


Section 4 -- Sui Generis Database Rights.

Where the Licensed Rights include Sui Generis Database Rights that
apply to Your use of the Licensed Material:

  a. for the avoidance of doubt, Section 2(a)(1) grants You the right
     to extract, reuse, reproduce, and Share all or a substantial
     portion of the contents of the database;

  b. if You include all or a substantial portion of the database
     contents in a database in which You have Sui Generis Database
     Rights, then the database in which You have Sui Generis Database
     Rights (but not its individual contents) is Adapted Material; and

  c. You must comply with the conditions in Section 3(a) if You Share
     all or a substantial portion of the contents of the database.

For the avoidance of doubt, this Section 4 supplements and does not
replace Your obligations under this Public License where the Licensed
Rights include other Copyright and Similar Rights.


Section 5 -- Disclaimer of Warranties and Limitation of Liability.

  a. UNLESS OTHERWISE SEPARATELY UNDERTAKEN BY THE LICENSOR, TO THE
     EXTENT POSSIBLE, THE LICENSOR OFFERS THE LICENSED MATERIAL AS-IS
     AND AS-AVAILABLE, AND MAKES NO REPRESENTATIONS OR WARRANTIES OF
     ANY KIND CONCERNING THE LICENSED MATERIAL, WHETHER EXPRESS,
     IMPLIED, STATUTORY, OR OTHER. THIS INCLUDES, WITHOUT LIMITATION,
     WARRANTIES OF TITLE, MERCHANTABILITY, FITNESS FOR A PARTICULAR
     PURPOSE, NON-INFRINGEMENT, ABSENCE OF LATENT OR OTHER DEFECTS,
     ACCURACY, OR THE PRESENCE OR ABSENCE OF ERRORS, WHETHER OR NOT
     KNOWN OR DISCOVERABLE. WHERE DISCLAIMERS OF WARRANTIES ARE NOT
     ALLOWED IN FULL OR IN PART, THIS DISCLAIMER MAY NOT APPLY TO YOU.

  b. TO THE EXTENT POSSIBLE, IN NO EVENT WILL THE LICENSOR BE LIABLE
     TO YOU ON ANY LEGAL THEORY (INCLUDING, WITHOUT LIMITATION,
     NEGLIGENCE) OR OTHERWISE FOR ANY DIRECT, SPECIAL, INDIRECT,
     INCIDENTAL, CONSEQUENTIAL, PUNITIVE, EXEMPLARY, OR OTHER LOSSES,
     COSTS, EXPENSES, OR DAMAGES ARISING OUT OF THIS PUBLIC LICENSE OR
     USE OF THE LICENSED MATERIAL, EVEN IF THE LICENSOR HAS BEEN
     ADVISED OF THE POSSIBILITY OF SUCH LOSSES, COSTS, EXPENSES, OR
     DAMAGES. WHERE A LIMITATION OF LIABILITY IS NOT ALLOWED IN FULL OR
     IN PART, THIS LIMITATION MAY NOT APPLY TO YOU.

  c. The disclaimer of warranties and limitation of liability provided
     above shall be interpreted in a manner that, to the extent
     possible, most closely approximates an absolute disclaimer and
     waiver of all liability.


Section 6 -- Term and Termination.

  a. This Public License applies for the term of the Copyright and
     Similar Rights licensed here. However, if You fail to comply with
     this Public License, then Your rights under this Public License
     terminate automatically.

  b. Where Your right to use the Licensed Material has terminated under
     Section 6(a), it reinstates:

       1. automatically as of the date the violation is cured, provided
          it is cured within 30 days of Your discovery of the
          violation; or

       2. upon express reinstatement by the Licensor.

     For the avoidance of doubt, this Section 6(b) does not affect any
     right the Licensor may have to seek remedies for Your violations
     of this Public License.

  c. For the avoidance of doubt, the Licensor may also offer the
     Licensed Material under separate terms or conditions or stop
     distributing the Licensed Material at any time; however, doing so
     will not terminate this Public License.

  d. Sections 1, 5, 6, 7, and 8 survive termination of this Public
     License.


Section 7 -- Other Terms and Conditions.

  a. The Licensor shall not be bound by any additional or different
     terms or conditions communicated by You unless expressly agreed.

  b. Any arrangements, understandings, or agreements regarding the
     Licensed Material not stated herein are separate from and
     independent of the terms and conditions of this Public License.


Section 8 -- Interpretation.

  a. For the avoidance of doubt, this Public License does not, and
     shall not be interpreted to, reduce, limit, restrict, or impose
     conditions on any use of the Licensed Material that could lawfully
     be made without permission under this Public License.

  b. To the extent possible, if any provision of this Public License is
     deemed unenforceable, it shall be automatically reformed to the
     minimum extent necessary to make it enforceable. If the provision
     cannot be reformed, it shall be severed from this Public License
     without affecting the enforceability of the remaining terms and
     conditions.

  c. No term or condition of this Public License will be waived and no
     failure to comply consented to unless expressly agreed to by the
     Licensor.

  d. Nothing in this Public License constitutes or may be interpreted
     as a limitation upon, or waiver of, any privileges and immunities
     that apply to the Licensor or You, including from the legal
     processes of any jurisdiction or authority.


=======================================================================

Creative Commons is not a party to its public
licenses. Notwithstanding, Creative Commons may elect to apply one of
its public licenses to material it publishes and in those instances
will be considered the “Licensor.” The text of the Creative Commons
public licenses is dedicated to the public domain under the CC0 Public
Domain Dedication. Except for the limited purpose of indicating that
material is shared under a Creative Commons public license or as
otherwise permitted by the Creative Commons policies published at
creativecommons.org/policies, Creative Commons does not authorize the
use of the trademark "Creative Commons" or any other trademark or logo
of Creative Commons without its prior written consent including,
without limitation, in connection with any unauthorized modifications
to any of its public licenses or any other arrangements,
understandings, or agreements concerning use of licensed material. For
the avoidance of doubt, this paragraph does not form part of the
public licenses.

Creative Commons may be contacted at creativecommons.org.
```

### CDDL + GPLv2 with classpath exception

<!-- Source: bundled META-INF/LICENSE.txt of javax.annotation-api 1.3.2 -->

```text
COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0

1. Definitions.

   1.1. Contributor. means each individual or entity that creates or contributes to the creation of Modifications.

   1.2. Contributor Version. means the combination of the Original Software, prior Modifications used by a Contributor (if any), and the Modifications made by that particular Contributor.

   1.3. Covered Software. means (a) the Original Software, or (b) Modifications, or (c) the combination of files containing Original Software with files containing Modifications, in each case including portions thereof.

   1.4. Executable. means the Covered Software in any form other than Source Code.

   1.5. Initial Developer. means the individual or entity that first makes Original Software available under this License.

   1.6. Larger Work. means a work which combines Covered Software or portions thereof with code not governed by the terms of this License.

   1.7. License. means this document.

   1.8. Licensable. means having the right to grant, to the maximum extent possible, whether at the time of the initial grant or subsequently acquired, any and all of the rights conveyed herein.

   1.9. Modifications. means the Source Code and Executable form of any of the following:

        A. Any file that results from an addition to, deletion from or modification of the contents of a file containing Original Software or previous Modifications;

        B. Any new file that contains any part of the Original Software or previous Modification; or

        C. Any new file that is contributed or otherwise made available under the terms of this License.

   1.10. Original Software. means the Source Code and Executable form of computer software code that is originally released under this License.

   1.11. Patent Claims. means any patent claim(s), now owned or hereafter acquired, including without limitation, method, process, and apparatus claims, in any patent Licensable by grantor.

   1.12. Source Code. means (a) the common form of computer software code in which modifications are made and (b) associated documentation included in or with such code.

   1.13. You. (or .Your.) means an individual or a legal entity exercising rights under, and complying with all of the terms of, this License. For legal entities, .You. includes any entity which controls, is controlled by, or is under common control with You. For purposes of this definition, .control. means (a) the power, direct or indirect, to cause the direction or management of such entity, whether by contract or otherwise, or (b) ownership of more than fifty percent (50%) of the outstanding shares or beneficial ownership of such entity.

2. License Grants.

      2.1. The Initial Developer Grant.

      Conditioned upon Your compliance with Section 3.1 below and subject to third party intellectual property claims, the Initial Developer hereby grants You a world-wide, royalty-free, non-exclusive license:

         (a) under intellectual property rights (other than patent or trademark) Licensable by Initial Developer, to use, reproduce, modify, display, perform, sublicense and distribute the Original Software (or portions thereof), with or without Modifications, and/or as part of a Larger Work; and

         (b) under Patent Claims infringed by the making, using or selling of Original Software, to make, have made, use, practice, sell, and offer for sale, and/or otherwise dispose of the Original Software (or portions thereof).

        (c) The licenses granted in Sections 2.1(a) and (b) are effective on the date Initial Developer first distributes or otherwise makes the Original Software available to a third party under the terms of this License.

        (d) Notwithstanding Section 2.1(b) above, no patent license is granted: (1) for code that You delete from the Original Software, or (2) for infringements caused by: (i) the modification of the Original Software, or (ii) the combination of the Original Software with other software or devices.

    2.2. Contributor Grant.

    Conditioned upon Your compliance with Section 3.1 below and subject to third party intellectual property claims, each Contributor hereby grants You a world-wide, royalty-free, non-exclusive license:

        (a) under intellectual property rights (other than patent or trademark) Licensable by Contributor to use, reproduce, modify, display, perform, sublicense and distribute the Modifications created by such Contributor (or portions thereof), either on an unmodified basis, with other Modifications, as Covered Software and/or as part of a Larger Work; and

        (b) under Patent Claims infringed by the making, using, or selling of Modifications made by that Contributor either alone and/or in combination with its Contributor Version (or portions of such combination), to make, use, sell, offer for sale, have made, and/or otherwise dispose of: (1) Modifications made by that Contributor (or portions thereof); and (2) the combination of Modifications made by that Contributor with its Contributor Version (or portions of such combination).

        (c) The licenses granted in Sections 2.2(a) and 2.2(b) are effective on the date Contributor first distributes or otherwise makes the Modifications available to a third party.

        (d) Notwithstanding Section 2.2(b) above, no patent license is granted: (1) for any code that Contributor has deleted from the Contributor Version; (2) for infringements caused by: (i) third party modifications of Contributor Version, or (ii) the combination of Modifications made by that Contributor with other software (except as part of the Contributor Version) or other devices; or (3) under Patent Claims infringed by Covered Software in the absence of Modifications made by that Contributor.

3. Distribution Obligations.

      3.1. Availability of Source Code.
      Any Covered Software that You distribute or otherwise make available in Executable form must also be made available in Source Code form and that Source Code form must be distributed only under the terms of this License. You must include a copy of this License with every copy of the Source Code form of the Covered Software You distribute or otherwise make available. You must inform recipients of any such Covered Software in Executable form as to how they can obtain such Covered Software in Source Code form in a reasonable manner on or through a medium customarily used for software exchange.

      3.2. Modifications.
      The Modifications that You create or to which You contribute are governed by the terms of this License. You represent that You believe Your Modifications are Your original creation(s) and/or You have sufficient rights to grant the rights conveyed by this License.

      3.3. Required Notices.
      You must include a notice in each of Your Modifications that identifies You as the Contributor of the Modification. You may not remove or alter any copyright, patent or trademark notices contained within the Covered Software, or any notices of licensing or any descriptive text giving attribution to any Contributor or the Initial Developer.

      3.4. Application of Additional Terms.
      You may not offer or impose any terms on any Covered Software in Source Code form that alters or restricts the applicable version of this License or the recipients. rights hereunder. You may choose to offer, and to charge a fee for, warranty, support, indemnity or liability obligations to one or more recipients of Covered Software. However, you may do so only on Your own behalf, and not on behalf of the Initial Developer or any Contributor. You must make it absolutely clear that any such warranty, support, indemnity or liability obligation is offered by You alone, and You hereby agree to indemnify the Initial Developer and every Contributor for any liability incurred by the Initial Developer or such Contributor as a result of warranty, support, indemnity or liability terms You offer.

      3.5. Distribution of Executable Versions.
      You may distribute the Executable form of the Covered Software under the terms of this License or under the terms of a license of Your choice, which may contain terms different from this License, provided that You are in compliance with the terms of this License and that the license for the Executable form does not attempt to limit or alter the recipient.s rights in the Source Code form from the rights set forth in this License. If You distribute the Covered Software in Executable form under a different license, You must make it absolutely clear that any terms which differ from this License are offered by You alone, not by the Initial Developer or Contributor. You hereby agree to indemnify the Initial Developer and every Contributor for any liability incurred by the Initial Developer or such Contributor as a result of any such terms You offer.

      3.6. Larger Works.
      You may create a Larger Work by combining Covered Software with other code not governed by the terms of this License and distribute the Larger Work as a single product. In such a case, You must make sure the requirements of this License are fulfilled for the Covered Software.

4. Versions of the License.

      4.1. New Versions.
      Sun Microsystems, Inc. is the initial license steward and may publish revised and/or new versions of this License from time to time. Each version will be given a distinguishing version number. Except as provided in Section 4.3, no one other than the license steward has the right to modify this License.

      4.2. Effect of New Versions.
      You may always continue to use, distribute or otherwise make the Covered Software available under the terms of the version of the License under which You originally received the Covered Software. If the Initial Developer includes a notice in the Original Software prohibiting it from being distributed or otherwise made available under any subsequent version of the License, You must distribute and make the Covered Software available under the terms of the version of the License under which You originally received the Covered Software. Otherwise, You may also choose to use, distribute or otherwise make the Covered Software available under the terms of any subsequent version of the License published by the license steward.

      4.3. Modified Versions.
      When You are an Initial Developer and You want to create a new license for Your Original Software, You may create and use a modified version of this License if You: (a) rename the license and remove any references to the name of the license steward (except to note that the license differs from this License); and (b) otherwise make it clear that the license contains terms which differ from this License.

5. DISCLAIMER OF WARRANTY.

   COVERED SOFTWARE IS PROVIDED UNDER THIS LICENSE ON AN .AS IS. BASIS, WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, WITHOUT LIMITATION, WARRANTIES THAT THE COVERED SOFTWARE IS FREE OF DEFECTS, MERCHANTABLE, FIT FOR A PARTICULAR PURPOSE OR NON-INFRINGING. THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE COVERED SOFTWARE IS WITH YOU. SHOULD ANY COVERED SOFTWARE PROVE DEFECTIVE IN ANY RESPECT, YOU (NOT THE INITIAL DEVELOPER OR ANY OTHER CONTRIBUTOR) ASSUME THE COST OF ANY NECESSARY SERVICING, REPAIR OR CORRECTION. THIS DISCLAIMER OF WARRANTY CONSTITUTES AN ESSENTIAL PART OF THIS LICENSE. NO USE OF ANY COVERED SOFTWARE IS AUTHORIZED HEREUNDER EXCEPT UNDER THIS DISCLAIMER.

6. TERMINATION.

      6.1. This License and the rights granted hereunder will terminate automatically if You fail to comply with terms herein and fail to cure such breach within 30 days of becoming aware of the breach. Provisions which, by their nature, must remain in effect beyond the termination of this License shall survive.

      6.2. If You assert a patent infringement claim (excluding declaratory judgment actions) against Initial Developer or a Contributor (the Initial Developer or Contributor against whom You assert such claim is referred to as .Participant.) alleging that the Participant Software (meaning the Contributor Version where the Participant is a Contributor or the Original Software where the Participant is the Initial Developer) directly or indirectly infringes any patent, then any and all rights granted directly or indirectly to You by such Participant, the Initial Developer (if the Initial Developer is not the Participant) and all Contributors under Sections 2.1 and/or 2.2 of this License shall, upon 60 days notice from Participant terminate prospectively and automatically at the expiration of such 60 day notice period, unless if within such 60 day period You withdraw Your claim with respect to the Participant Software against such Participant either unilaterally or pursuant to a written agreement with Participant.

      6.3. In the event of termination under Sections 6.1 or 6.2 above, all end user licenses that have been validly granted by You or any distributor hereunder prior to termination (excluding licenses granted to You by any distributor) shall survive termination.

7. LIMITATION OF LIABILITY.

   UNDER NO CIRCUMSTANCES AND UNDER NO LEGAL THEORY, WHETHER TORT (INCLUDING NEGLIGENCE), CONTRACT, OR OTHERWISE, SHALL YOU, THE INITIAL DEVELOPER, ANY OTHER CONTRIBUTOR, OR ANY DISTRIBUTOR OF COVERED SOFTWARE, OR ANY SUPPLIER OF ANY OF SUCH PARTIES, BE LIABLE TO ANY PERSON FOR ANY INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES OF ANY CHARACTER INCLUDING, WITHOUT LIMITATION, DAMAGES FOR LOST PROFITS, LOSS OF GOODWILL, WORK STOPPAGE, COMPUTER FAILURE OR MALFUNCTION, OR ANY AND ALL OTHER COMMERCIAL DAMAGES OR LOSSES, EVEN IF SUCH PARTY SHALL HAVE BEEN INFORMED OF THE POSSIBILITY OF SUCH DAMAGES. THIS LIMITATION OF LIABILITY SHALL NOT APPLY TO LIABILITY FOR DEATH OR PERSONAL INJURY RESULTING FROM SUCH PARTY.S NEGLIGENCE TO THE EXTENT APPLICABLE LAW PROHIBITS SUCH LIMITATION. SOME JURISDICTIONS DO NOT ALLOW THE EXCLUSION OR LIMITATION OF INCIDENTAL OR CONSEQUENTIAL DAMAGES, SO THIS EXCLUSION AND LIMITATION MAY NOT APPLY TO YOU.

8. U.S. GOVERNMENT END USERS.

   The Covered Software is a .commercial item,. as that term is defined in 48 C.F.R. 2.101 (Oct. 1995), consisting of .commercial computer software. (as that term is defined at 48 C.F.R. ? 252.227-7014(a)(1)) and .commercial computer software documentation. as such terms are used in 48 C.F.R. 12.212 (Sept. 1995). Consistent with 48 C.F.R. 12.212 and 48 C.F.R. 227.7202-1 through 227.7202-4 (June 1995), all U.S. Government End Users acquire Covered Software with only those rights set forth herein. This U.S. Government Rights clause is in lieu of, and supersedes, any other FAR, DFAR, or other clause or provision that addresses Government rights in computer software under this License.

9. MISCELLANEOUS.

   This License represents the complete agreement concerning subject matter hereof. If any provision of this License is held to be unenforceable, such provision shall be reformed only to the extent necessary to make it enforceable. This License shall be governed by the law of the jurisdiction specified in a notice contained within the Original Software (except to the extent applicable law, if any, provides otherwise), excluding such jurisdiction.s conflict-of-law provisions. Any litigation relating to this License shall be subject to the jurisdiction of the courts located in the jurisdiction and venue specified in a notice contained within the Original Software, with the losing party responsible for costs, including, without limitation, court costs and reasonable attorneys. fees and expenses. The application of the United Nations Convention on Contracts for the International Sale of Goods is expressly excluded. Any law or regulation which provides that the language of a contract shall be construed against the drafter shall not apply to this License. You agree that You alone are responsible for compliance with the United States export administration regulations (and the export control laws and regulation of any other countries) when You use, distribute or otherwise make available any Covered Software.

10. RESPONSIBILITY FOR CLAIMS.

   As between Initial Developer and the Contributors, each party is responsible for claims and damages arising, directly or indirectly, out of its utilization of rights under this License and You agree to work with Initial Developer and Contributors to distribute such responsibility on an equitable basis. Nothing herein is intended or shall be deemed to constitute any admission of liability.

   NOTICE PURSUANT TO SECTION 9 OF THE COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL)

   The code released under the CDDL shall be governed by the laws of the State of California (excluding conflict-of-law provisions). Any litigation relating to this License shall be subject to the jurisdiction of the Federal Courts of the Northern District of California and the state courts of the State of California, with venue lying in Santa Clara County, California.


The GNU General Public License (GPL) Version 2, June 1991


Copyright (C) 1989, 1991 Free Software Foundation, Inc. 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA

Everyone is permitted to copy and distribute verbatim copies of this license document, but changing it is not allowed.

Preamble

The licenses for most software are designed to take away your freedom to share and change it. By contrast, the GNU General Public License is intended to guarantee your freedom to share and change free software--to make sure the software is free for all its users. This General Public License applies to most of the Free Software Foundation's software and to any other program whose authors commit to using it. (Some other Free Software Foundation software is covered by the GNU Library General Public License instead.) You can apply it to your programs, too.

When we speak of free software, we are referring to freedom, not price. Our General Public Licenses are designed to make sure that you have the freedom to distribute copies of free software (and charge for this service if you wish), that you receive source code or can get it if you want it, that you can change the software or use pieces of it in new free programs; and that you know you can do these things.

To protect your rights, we need to make restrictions that forbid anyone to deny you these rights or to ask you to surrender the rights. These restrictions translate to certain responsibilities for you if you distribute copies of the software, or if you modify it.

For example, if you distribute copies of such a program, whether gratis or for a fee, you must give the recipients all the rights that you have. You must make sure that they, too, receive or can get the source code. And you must show them these terms so they know their rights.

We protect your rights with two steps: (1) copyright the software, and (2) offer you this license which gives you legal permission to copy, distribute and/or modify the software.

Also, for each author's protection and ours, we want to make certain that everyone understands that there is no warranty for this free software. If the software is modified by someone else and passed on, we want its recipients to know that what they have is not the original, so that any problems introduced by others will not reflect on the original authors' reputations.

Finally, any free program is threatened constantly by software patents. We wish to avoid the danger that redistributors of a free program will individually obtain patent licenses, in effect making the program proprietary. To prevent this, we have made it clear that any patent must be licensed for everyone's free use or not licensed at all.

The precise terms and conditions for copying, distribution and modification follow.


TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

0. This License applies to any program or other work which contains a notice placed by the copyright holder saying it may be distributed under the terms of this General Public License. The "Program", below, refers to any such program or work, and a "work based on the Program" means either the Program or any derivative work under copyright law: that is to say, a work containing the Program or a portion of it, either verbatim or with modifications and/or translated into another language. (Hereinafter, translation is included without limitation in the term "modification".) Each licensee is addressed as "you".

Activities other than copying, distribution and modification are not covered by this License; they are outside its scope. The act of running the Program is not restricted, and the output from the Program is covered only if its contents constitute a work based on the Program (independent of having been made by running the Program). Whether that is true depends on what the Program does.

1. You may copy and distribute verbatim copies of the Program's source code as you receive it, in any medium, provided that you conspicuously and appropriately publish on each copy an appropriate copyright notice and disclaimer of warranty; keep intact all the notices that refer to this License and to the absence of any warranty; and give any other recipients of the Program a copy of this License along with the Program.

You may charge a fee for the physical act of transferring a copy, and you may at your option offer warranty protection in exchange for a fee.

2. You may modify your copy or copies of the Program or any portion of it, thus forming a work based on the Program, and copy and distribute such modifications or work under the terms of Section 1 above, provided that you also meet all of these conditions:

   a) You must cause the modified files to carry prominent notices stating that you changed the files and the date of any change.

   b) You must cause any work that you distribute or publish, that in whole or in part contains or is derived from the Program or any part thereof, to be licensed as a whole at no charge to all third parties under the terms of this License.

   c) If the modified program normally reads commands interactively when run, you must cause it, when started running for such interactive use in the most ordinary way, to print or display an announcement including an appropriate copyright notice and a notice that there is no warranty (or else, saying that you provide a warranty) and that users may redistribute the program under these conditions, and telling the user how to view a copy of this License. (Exception: if the Program itself is interactive but does not normally print such an announcement, your work based on the Program is not required to print an announcement.)

These requirements apply to the modified work as a whole. If identifiable sections of that work are not derived from the Program, and can be reasonably considered independent and separate works in themselves, then this License, and its terms, do not apply to those sections when you distribute them as separate works. But when you distribute the same sections as part of a whole which is a work based on the Program, the distribution of the whole must be on the terms of this License, whose permissions for other licensees extend to the entire whole, and thus to each and every part regardless of who wrote it.

Thus, it is not the intent of this section to claim rights or contest your rights to work written entirely by you; rather, the intent is to exercise the right to control the distribution of derivative or collective works based on the Program.

In addition, mere aggregation of another work not based on the Program with the Program (or with a work based on the Program) on a volume of a storage or distribution medium does not bring the other work under the scope of this License.

3. You may copy and distribute the Program (or a work based on it, under Section 2) in object code or executable form under the terms of Sections 1 and 2 above provided that you also do one of the following:

   a) Accompany it with the complete corresponding machine-readable source code, which must be distributed under the terms of Sections 1 and 2 above on a medium customarily used for software interchange; or,

   b) Accompany it with a written offer, valid for at least three years, to give any third party, for a charge no more than your cost of physically performing source distribution, a complete machine-readable copy of the corresponding source code, to be distributed under the terms of Sections 1 and 2 above on a medium customarily used for software interchange; or,

   c) Accompany it with the information you received as to the offer to distribute corresponding source code. (This alternative is allowed only for noncommercial distribution and only if you received the program in object code or executable form with such an offer, in accord with Subsection b above.)

The source code for a work means the preferred form of the work for making modifications to it. For an executable work, complete source code means all the source code for all modules it contains, plus any associated interface definition files, plus the scripts used to control compilation and installation of the executable. However, as a special exception, the source code distributed need not include anything that is normally distributed (in either source or binary form) with the major components (compiler, kernel, and so on) of the operating system on which the executable runs, unless that component itself accompanies the executable.

If distribution of executable or object code is made by offering access to copy from a designated place, then offering equivalent access to copy the source code from the same place counts as distribution of the source code, even though third parties are not compelled to copy the source along with the object code.

4. You may not copy, modify, sublicense, or distribute the Program except as expressly provided under this License. Any attempt otherwise to copy, modify, sublicense or distribute the Program is void, and will automatically terminate your rights under this License. However, parties who have received copies, or rights, from you under this License will not have their licenses terminated so long as such parties remain in full compliance.

5. You are not required to accept this License, since you have not signed it. However, nothing else grants you permission to modify or distribute the Program or its derivative works. These actions are prohibited by law if you do not accept this License. Therefore, by modifying or distributing the Program (or any work based on the Program), you indicate your acceptance of this License to do so, and all its terms and conditions for copying, distributing or modifying the Program or works based on it.

6. Each time you redistribute the Program (or any work based on the Program), the recipient automatically receives a license from the original licensor to copy, distribute or modify the Program subject to these terms and conditions. You may not impose any further restrictions on the recipients' exercise of the rights granted herein. You are not responsible for enforcing compliance by third parties to this License.

7. If, as a consequence of a court judgment or allegation of patent infringement or for any other reason (not limited to patent issues), conditions are imposed on you (whether by court order, agreement or otherwise) that contradict the conditions of this License, they do not excuse you from the conditions of this License. If you cannot distribute so as to satisfy simultaneously your obligations under this License and any other pertinent obligations, then as a consequence you may not distribute the Program at all. For example, if a patent license would not permit royalty-free redistribution of the Program by all those who receive copies directly or indirectly through you, then the only way you could satisfy both it and this License would be to refrain entirely from distribution of the Program.

If any portion of this section is held invalid or unenforceable under any particular circumstance, the balance of the section is intended to apply and the section as a whole is intended to apply in other circumstances.

It is not the purpose of this section to induce you to infringe any patents or other property right claims or to contest validity of any such claims; this section has the sole purpose of protecting the integrity of the free software distribution system, which is implemented by public license practices. Many people have made generous contributions to the wide range of software distributed through that system in reliance on consistent application of that system; it is up to the author/donor to decide if he or she is willing to distribute software through any other system and a licensee cannot impose that choice.

This section is intended to make thoroughly clear what is believed to be a consequence of the rest of this License.

8. If the distribution and/or use of the Program is restricted in certain countries either by patents or by copyrighted interfaces, the original copyright holder who places the Program under this License may add an explicit geographical distribution limitation excluding those countries, so that distribution is permitted only in or among countries not thus excluded. In such case, this License incorporates the limitation as if written in the body of this License.

9. The Free Software Foundation may publish revised and/or new versions of the General Public License from time to time. Such new versions will be similar in spirit to the present version, but may differ in detail to address new problems or concerns.

Each version is given a distinguishing version number. If the Program specifies a version number of this License which applies to it and "any later version", you have the option of following the terms and conditions either of that version or of any later version published by the Free Software Foundation. If the Program does not specify a version number of this License, you may choose any version ever published by the Free Software Foundation.

10. If you wish to incorporate parts of the Program into other free programs whose distribution conditions are different, write to the author to ask for permission. For software which is copyrighted by the Free Software Foundation, write to the Free Software Foundation; we sometimes make exceptions for this. Our decision will be guided by the two goals of preserving the free status of all derivatives of our free software and of promoting the sharing and reuse of software generally.

NO WARRANTY

11. BECAUSE THE PROGRAM IS LICENSED FREE OF CHARGE, THERE IS NO WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW. EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR OTHER PARTIES PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH YOU. SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

12. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY AND/OR REDISTRIBUTE THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS), EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

END OF TERMS AND CONDITIONS


How to Apply These Terms to Your New Programs

If you develop a new program, and you want it to be of the greatest possible use to the public, the best way to achieve this is to make it free software which everyone can redistribute and change under these terms.

To do so, attach the following notices to the program. It is safest to attach them to the start of each source file to most effectively convey the exclusion of warranty; and each file should have at least the "copyright" line and a pointer to where the full notice is found.

   One line to give the program's name and a brief idea of what it does.

   Copyright (C)

   This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation; either version 2 of the License, or (at your option) any later version.

   This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

   You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA

Also add information on how to contact you by electronic and paper mail.

If the program is interactive, make it output a short notice like this when it starts in an interactive mode:

   Gnomovision version 69, Copyright (C) year name of author
   Gnomovision comes with ABSOLUTELY NO WARRANTY; for details type `show w'. This is free software, and you are welcome to redistribute it under certain conditions; type `show c' for details.

The hypothetical commands `show w' and `show c' should show the appropriate parts of the General Public License. Of course, the commands you use may be called something other than `show w' and `show c'; they could even be mouse-clicks or menu items--whatever suits your program.

You should also get your employer (if you work as a programmer) or your school, if any, to sign a "copyright disclaimer" for the program, if necessary. Here is a sample; alter the names:

   Yoyodyne, Inc., hereby disclaims all copyright interest in the program `Gnomovision' (which makes passes at compilers) written by James Hacker.

   signature of Ty Coon, 1 April 1989
   Ty Coon, President of Vice

This General Public License does not permit incorporating your program into proprietary programs. If your program is a subroutine library, you may consider it more useful to permit linking proprietary applications with the library. If this is what you want to do, use the GNU Library General Public License instead of this License.


"CLASSPATH" EXCEPTION TO THE GPL VERSION 2

Certain source files distributed by Sun Microsystems, Inc. are subject to the following clarification and special exception to the GPL Version 2, but only where Sun has expressly included in the particular source file's header the words

"Sun designates this particular file as subject to the "Classpath" exception as provided by Sun in the License file that accompanied this code."

Linking this library statically or dynamically with other modules is making a combined work based on this library. Thus, the terms and conditions of the GNU General Public License Version 2 cover the whole combination.

As a special exception, the copyright holders of this library give you permission to link this library with independent modules to produce an executable, regardless of the license terms of these independent modules, and to copy and distribute the resulting executable under terms of your choice, provided that you also meet, for each linked independent module, the terms and conditions of the license of that module.? An independent module is a module which is not derived from or based on this library.? If you modify this library, you may extend this exception to your version of the library, but you are not obligated to do so.? If you do not wish to do so, delete this exception statement from your version.
```

### COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0

<!-- Source: bundled META-INF/LICENSE.txt of javax.annotation-api 1.3.2 (CDDL section) -->

```text
COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0

1. Definitions.

   1.1. Contributor. means each individual or entity that creates or contributes to the creation of Modifications.

   1.2. Contributor Version. means the combination of the Original Software, prior Modifications used by a Contributor (if any), and the Modifications made by that particular Contributor.

   1.3. Covered Software. means (a) the Original Software, or (b) Modifications, or (c) the combination of files containing Original Software with files containing Modifications, in each case including portions thereof.

   1.4. Executable. means the Covered Software in any form other than Source Code.

   1.5. Initial Developer. means the individual or entity that first makes Original Software available under this License.

   1.6. Larger Work. means a work which combines Covered Software or portions thereof with code not governed by the terms of this License.

   1.7. License. means this document.

   1.8. Licensable. means having the right to grant, to the maximum extent possible, whether at the time of the initial grant or subsequently acquired, any and all of the rights conveyed herein.

   1.9. Modifications. means the Source Code and Executable form of any of the following:

        A. Any file that results from an addition to, deletion from or modification of the contents of a file containing Original Software or previous Modifications;

        B. Any new file that contains any part of the Original Software or previous Modification; or

        C. Any new file that is contributed or otherwise made available under the terms of this License.

   1.10. Original Software. means the Source Code and Executable form of computer software code that is originally released under this License.

   1.11. Patent Claims. means any patent claim(s), now owned or hereafter acquired, including without limitation, method, process, and apparatus claims, in any patent Licensable by grantor.

   1.12. Source Code. means (a) the common form of computer software code in which modifications are made and (b) associated documentation included in or with such code.

   1.13. You. (or .Your.) means an individual or a legal entity exercising rights under, and complying with all of the terms of, this License. For legal entities, .You. includes any entity which controls, is controlled by, or is under common control with You. For purposes of this definition, .control. means (a) the power, direct or indirect, to cause the direction or management of such entity, whether by contract or otherwise, or (b) ownership of more than fifty percent (50%) of the outstanding shares or beneficial ownership of such entity.

2. License Grants.

      2.1. The Initial Developer Grant.

      Conditioned upon Your compliance with Section 3.1 below and subject to third party intellectual property claims, the Initial Developer hereby grants You a world-wide, royalty-free, non-exclusive license:

         (a) under intellectual property rights (other than patent or trademark) Licensable by Initial Developer, to use, reproduce, modify, display, perform, sublicense and distribute the Original Software (or portions thereof), with or without Modifications, and/or as part of a Larger Work; and

         (b) under Patent Claims infringed by the making, using or selling of Original Software, to make, have made, use, practice, sell, and offer for sale, and/or otherwise dispose of the Original Software (or portions thereof).

        (c) The licenses granted in Sections 2.1(a) and (b) are effective on the date Initial Developer first distributes or otherwise makes the Original Software available to a third party under the terms of this License.

        (d) Notwithstanding Section 2.1(b) above, no patent license is granted: (1) for code that You delete from the Original Software, or (2) for infringements caused by: (i) the modification of the Original Software, or (ii) the combination of the Original Software with other software or devices.

    2.2. Contributor Grant.

    Conditioned upon Your compliance with Section 3.1 below and subject to third party intellectual property claims, each Contributor hereby grants You a world-wide, royalty-free, non-exclusive license:

        (a) under intellectual property rights (other than patent or trademark) Licensable by Contributor to use, reproduce, modify, display, perform, sublicense and distribute the Modifications created by such Contributor (or portions thereof), either on an unmodified basis, with other Modifications, as Covered Software and/or as part of a Larger Work; and

        (b) under Patent Claims infringed by the making, using, or selling of Modifications made by that Contributor either alone and/or in combination with its Contributor Version (or portions of such combination), to make, use, sell, offer for sale, have made, and/or otherwise dispose of: (1) Modifications made by that Contributor (or portions thereof); and (2) the combination of Modifications made by that Contributor with its Contributor Version (or portions of such combination).

        (c) The licenses granted in Sections 2.2(a) and 2.2(b) are effective on the date Contributor first distributes or otherwise makes the Modifications available to a third party.

        (d) Notwithstanding Section 2.2(b) above, no patent license is granted: (1) for any code that Contributor has deleted from the Contributor Version; (2) for infringements caused by: (i) third party modifications of Contributor Version, or (ii) the combination of Modifications made by that Contributor with other software (except as part of the Contributor Version) or other devices; or (3) under Patent Claims infringed by Covered Software in the absence of Modifications made by that Contributor.

3. Distribution Obligations.

      3.1. Availability of Source Code.
      Any Covered Software that You distribute or otherwise make available in Executable form must also be made available in Source Code form and that Source Code form must be distributed only under the terms of this License. You must include a copy of this License with every copy of the Source Code form of the Covered Software You distribute or otherwise make available. You must inform recipients of any such Covered Software in Executable form as to how they can obtain such Covered Software in Source Code form in a reasonable manner on or through a medium customarily used for software exchange.

      3.2. Modifications.
      The Modifications that You create or to which You contribute are governed by the terms of this License. You represent that You believe Your Modifications are Your original creation(s) and/or You have sufficient rights to grant the rights conveyed by this License.

      3.3. Required Notices.
      You must include a notice in each of Your Modifications that identifies You as the Contributor of the Modification. You may not remove or alter any copyright, patent or trademark notices contained within the Covered Software, or any notices of licensing or any descriptive text giving attribution to any Contributor or the Initial Developer.

      3.4. Application of Additional Terms.
      You may not offer or impose any terms on any Covered Software in Source Code form that alters or restricts the applicable version of this License or the recipients. rights hereunder. You may choose to offer, and to charge a fee for, warranty, support, indemnity or liability obligations to one or more recipients of Covered Software. However, you may do so only on Your own behalf, and not on behalf of the Initial Developer or any Contributor. You must make it absolutely clear that any such warranty, support, indemnity or liability obligation is offered by You alone, and You hereby agree to indemnify the Initial Developer and every Contributor for any liability incurred by the Initial Developer or such Contributor as a result of warranty, support, indemnity or liability terms You offer.

      3.5. Distribution of Executable Versions.
      You may distribute the Executable form of the Covered Software under the terms of this License or under the terms of a license of Your choice, which may contain terms different from this License, provided that You are in compliance with the terms of this License and that the license for the Executable form does not attempt to limit or alter the recipient.s rights in the Source Code form from the rights set forth in this License. If You distribute the Covered Software in Executable form under a different license, You must make it absolutely clear that any terms which differ from this License are offered by You alone, not by the Initial Developer or Contributor. You hereby agree to indemnify the Initial Developer and every Contributor for any liability incurred by the Initial Developer or such Contributor as a result of any such terms You offer.

      3.6. Larger Works.
      You may create a Larger Work by combining Covered Software with other code not governed by the terms of this License and distribute the Larger Work as a single product. In such a case, You must make sure the requirements of this License are fulfilled for the Covered Software.

4. Versions of the License.

      4.1. New Versions.
      Sun Microsystems, Inc. is the initial license steward and may publish revised and/or new versions of this License from time to time. Each version will be given a distinguishing version number. Except as provided in Section 4.3, no one other than the license steward has the right to modify this License.

      4.2. Effect of New Versions.
      You may always continue to use, distribute or otherwise make the Covered Software available under the terms of the version of the License under which You originally received the Covered Software. If the Initial Developer includes a notice in the Original Software prohibiting it from being distributed or otherwise made available under any subsequent version of the License, You must distribute and make the Covered Software available under the terms of the version of the License under which You originally received the Covered Software. Otherwise, You may also choose to use, distribute or otherwise make the Covered Software available under the terms of any subsequent version of the License published by the license steward.

      4.3. Modified Versions.
      When You are an Initial Developer and You want to create a new license for Your Original Software, You may create and use a modified version of this License if You: (a) rename the license and remove any references to the name of the license steward (except to note that the license differs from this License); and (b) otherwise make it clear that the license contains terms which differ from this License.

5. DISCLAIMER OF WARRANTY.

   COVERED SOFTWARE IS PROVIDED UNDER THIS LICENSE ON AN .AS IS. BASIS, WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, WITHOUT LIMITATION, WARRANTIES THAT THE COVERED SOFTWARE IS FREE OF DEFECTS, MERCHANTABLE, FIT FOR A PARTICULAR PURPOSE OR NON-INFRINGING. THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE COVERED SOFTWARE IS WITH YOU. SHOULD ANY COVERED SOFTWARE PROVE DEFECTIVE IN ANY RESPECT, YOU (NOT THE INITIAL DEVELOPER OR ANY OTHER CONTRIBUTOR) ASSUME THE COST OF ANY NECESSARY SERVICING, REPAIR OR CORRECTION. THIS DISCLAIMER OF WARRANTY CONSTITUTES AN ESSENTIAL PART OF THIS LICENSE. NO USE OF ANY COVERED SOFTWARE IS AUTHORIZED HEREUNDER EXCEPT UNDER THIS DISCLAIMER.

6. TERMINATION.

      6.1. This License and the rights granted hereunder will terminate automatically if You fail to comply with terms herein and fail to cure such breach within 30 days of becoming aware of the breach. Provisions which, by their nature, must remain in effect beyond the termination of this License shall survive.

      6.2. If You assert a patent infringement claim (excluding declaratory judgment actions) against Initial Developer or a Contributor (the Initial Developer or Contributor against whom You assert such claim is referred to as .Participant.) alleging that the Participant Software (meaning the Contributor Version where the Participant is a Contributor or the Original Software where the Participant is the Initial Developer) directly or indirectly infringes any patent, then any and all rights granted directly or indirectly to You by such Participant, the Initial Developer (if the Initial Developer is not the Participant) and all Contributors under Sections 2.1 and/or 2.2 of this License shall, upon 60 days notice from Participant terminate prospectively and automatically at the expiration of such 60 day notice period, unless if within such 60 day period You withdraw Your claim with respect to the Participant Software against such Participant either unilaterally or pursuant to a written agreement with Participant.

      6.3. In the event of termination under Sections 6.1 or 6.2 above, all end user licenses that have been validly granted by You or any distributor hereunder prior to termination (excluding licenses granted to You by any distributor) shall survive termination.

7. LIMITATION OF LIABILITY.

   UNDER NO CIRCUMSTANCES AND UNDER NO LEGAL THEORY, WHETHER TORT (INCLUDING NEGLIGENCE), CONTRACT, OR OTHERWISE, SHALL YOU, THE INITIAL DEVELOPER, ANY OTHER CONTRIBUTOR, OR ANY DISTRIBUTOR OF COVERED SOFTWARE, OR ANY SUPPLIER OF ANY OF SUCH PARTIES, BE LIABLE TO ANY PERSON FOR ANY INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES OF ANY CHARACTER INCLUDING, WITHOUT LIMITATION, DAMAGES FOR LOST PROFITS, LOSS OF GOODWILL, WORK STOPPAGE, COMPUTER FAILURE OR MALFUNCTION, OR ANY AND ALL OTHER COMMERCIAL DAMAGES OR LOSSES, EVEN IF SUCH PARTY SHALL HAVE BEEN INFORMED OF THE POSSIBILITY OF SUCH DAMAGES. THIS LIMITATION OF LIABILITY SHALL NOT APPLY TO LIABILITY FOR DEATH OR PERSONAL INJURY RESULTING FROM SUCH PARTY.S NEGLIGENCE TO THE EXTENT APPLICABLE LAW PROHIBITS SUCH LIMITATION. SOME JURISDICTIONS DO NOT ALLOW THE EXCLUSION OR LIMITATION OF INCIDENTAL OR CONSEQUENTIAL DAMAGES, SO THIS EXCLUSION AND LIMITATION MAY NOT APPLY TO YOU.

8. U.S. GOVERNMENT END USERS.

   The Covered Software is a .commercial item,. as that term is defined in 48 C.F.R. 2.101 (Oct. 1995), consisting of .commercial computer software. (as that term is defined at 48 C.F.R. ? 252.227-7014(a)(1)) and .commercial computer software documentation. as such terms are used in 48 C.F.R. 12.212 (Sept. 1995). Consistent with 48 C.F.R. 12.212 and 48 C.F.R. 227.7202-1 through 227.7202-4 (June 1995), all U.S. Government End Users acquire Covered Software with only those rights set forth herein. This U.S. Government Rights clause is in lieu of, and supersedes, any other FAR, DFAR, or other clause or provision that addresses Government rights in computer software under this License.

9. MISCELLANEOUS.

   This License represents the complete agreement concerning subject matter hereof. If any provision of this License is held to be unenforceable, such provision shall be reformed only to the extent necessary to make it enforceable. This License shall be governed by the law of the jurisdiction specified in a notice contained within the Original Software (except to the extent applicable law, if any, provides otherwise), excluding such jurisdiction.s conflict-of-law provisions. Any litigation relating to this License shall be subject to the jurisdiction of the courts located in the jurisdiction and venue specified in a notice contained within the Original Software, with the losing party responsible for costs, including, without limitation, court costs and reasonable attorneys. fees and expenses. The application of the United Nations Convention on Contracts for the International Sale of Goods is expressly excluded. Any law or regulation which provides that the language of a contract shall be construed against the drafter shall not apply to this License. You agree that You alone are responsible for compliance with the United States export administration regulations (and the export control laws and regulation of any other countries) when You use, distribute or otherwise make available any Covered Software.

10. RESPONSIBILITY FOR CLAIMS.

   As between Initial Developer and the Contributors, each party is responsible for claims and damages arising, directly or indirectly, out of its utilization of rights under this License and You agree to work with Initial Developer and Contributors to distribute such responsibility on an equitable basis. Nothing herein is intended or shall be deemed to constitute any admission of liability.

   NOTICE PURSUANT TO SECTION 9 OF THE COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL)

   The code released under the CDDL shall be governed by the laws of the State of California (excluding conflict-of-law provisions). Any litigation relating to this License shall be subject to the jurisdiction of the Federal Courts of the Northern District of California and the state courts of the State of California, with venue lying in Santa Clara County, California.
```

### Eclipse Public License - v 1.0

<!-- Source: https://raw.githubusercontent.com/spdx/license-list-data/main/text/EPL-1.0.txt -->

```text
Eclipse Public License - v 1.0

THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.

1. DEFINITIONS

"Contribution" means:
     a) in the case of the initial Contributor, the initial code and documentation distributed under this Agreement, and
     b) in the case of each subsequent Contributor:
          i) changes to the Program, and
          ii) additions to the Program;

where such changes and/or additions to the Program originate from and are distributed by that particular Contributor. A Contribution 'originates' from a Contributor if it was added to the Program by such Contributor itself or anyone acting on such Contributor's behalf. Contributions do not include additions to the Program which: (i) are separate modules of software distributed in conjunction with the Program under their own license agreement, and (ii) are not derivative works of the Program.
"Contributor" means any person or entity that distributes the Program.

"Licensed Patents" mean patent claims licensable by a Contributor which are necessarily infringed by the use or sale of its Contribution alone or when combined with the Program.

"Program" means the Contributions distributed in accordance with this Agreement.

"Recipient" means anyone who receives the Program under this Agreement, including all Contributors.

2. GRANT OF RIGHTS

     a) Subject to the terms of this Agreement, each Contributor hereby grants Recipient a non-exclusive, worldwide, royalty-free copyright license to reproduce, prepare derivative works of, publicly display, publicly perform, distribute and sublicense the Contribution of such Contributor, if any, and such derivative works, in source code and object code form.

     b) Subject to the terms of this Agreement, each Contributor hereby grants Recipient a non-exclusive, worldwide, royalty-free patent license under Licensed Patents to make, use, sell, offer to sell, import and otherwise transfer the Contribution of such Contributor, if any, in source code and object code form. This patent license shall apply to the combination of the Contribution and the Program if, at the time the Contribution is added by the Contributor, such addition of the Contribution causes such combination to be covered by the Licensed Patents. The patent license shall not apply to any other combinations which include the Contribution. No hardware per se is licensed hereunder.

     c) Recipient understands that although each Contributor grants the licenses to its Contributions set forth herein, no assurances are provided by any Contributor that the Program does not infringe the patent or other intellectual property rights of any other entity. Each Contributor disclaims any liability to Recipient for claims brought by any other entity based on infringement of intellectual property rights or otherwise. As a condition to exercising the rights and licenses granted hereunder, each Recipient hereby assumes sole responsibility to secure any other intellectual property rights needed, if any. For example, if a third party patent license is required to allow Recipient to distribute the Program, it is Recipient's responsibility to acquire that license before distributing the Program.

     d) Each Contributor represents that to its knowledge it has sufficient copyright rights in its Contribution, if any, to grant the copyright license set forth in this Agreement.

3. REQUIREMENTS
A Contributor may choose to distribute the Program in object code form under its own license agreement, provided that:

     a) it complies with the terms and conditions of this Agreement; and

     b) its license agreement:
          i) effectively disclaims on behalf of all Contributors all warranties and conditions, express and implied, including warranties or conditions of title and non-infringement, and implied warranties or conditions of merchantability and fitness for a particular purpose;
          ii) effectively excludes on behalf of all Contributors all liability for damages, including direct, indirect, special, incidental and consequential damages, such as lost profits;
          iii) states that any provisions which differ from this Agreement are offered by that Contributor alone and not by any other party; and
          iv) states that source code for the Program is available from such Contributor, and informs licensees how to obtain it in a reasonable manner on or through a medium customarily used for software exchange.

When the Program is made available in source code form:

     a) it must be made available under this Agreement; and

     b) a copy of this Agreement must be included with each copy of the Program.
Contributors may not remove or alter any copyright notices contained within the Program.

Each Contributor must identify itself as the originator of its Contribution, if any, in a manner that reasonably allows subsequent Recipients to identify the originator of the Contribution.

4. COMMERCIAL DISTRIBUTION
Commercial distributors of software may accept certain responsibilities with respect to end users, business partners and the like. While this license is intended to facilitate the commercial use of the Program, the Contributor who includes the Program in a commercial product offering should do so in a manner which does not create potential liability for other Contributors. Therefore, if a Contributor includes the Program in a commercial product offering, such Contributor ("Commercial Contributor") hereby agrees to defend and indemnify every other Contributor ("Indemnified Contributor") against any losses, damages and costs (collectively "Losses") arising from claims, lawsuits and other legal actions brought by a third party against the Indemnified Contributor to the extent caused by the acts or omissions of such Commercial Contributor in connection with its distribution of the Program in a commercial product offering. The obligations in this section do not apply to any claims or Losses relating to any actual or alleged intellectual property infringement. In order to qualify, an Indemnified Contributor must: a) promptly notify the Commercial Contributor in writing of such claim, and b) allow the Commercial Contributor to control, and cooperate with the Commercial Contributor in, the defense and any related settlement negotiations. The Indemnified Contributor may participate in any such claim at its own expense.

For example, a Contributor might include the Program in a commercial product offering, Product X. That Contributor is then a Commercial Contributor. If that Commercial Contributor then makes performance claims, or offers warranties related to Product X, those performance claims and warranties are such Commercial Contributor's responsibility alone. Under this section, the Commercial Contributor would have to defend claims against the other Contributors related to those performance claims and warranties, and if a court requires any other Contributor to pay any damages as a result, the Commercial Contributor must pay those damages.

5. NO WARRANTY
EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, THE PROGRAM IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Each Recipient is solely responsible for determining the appropriateness of using and distributing the Program and assumes all risks associated with its exercise of rights under this Agreement , including but not limited to the risks and costs of program errors, compliance with applicable laws, damage to or loss of data, programs or equipment, and unavailability or interruption of operations.

6. DISCLAIMER OF LIABILITY
EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, NEITHER RECIPIENT NOR ANY CONTRIBUTORS SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING WITHOUT LIMITATION LOST PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OR DISTRIBUTION OF THE PROGRAM OR THE EXERCISE OF ANY RIGHTS GRANTED HEREUNDER, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

7. GENERAL

If any provision of this Agreement is invalid or unenforceable under applicable law, it shall not affect the validity or enforceability of the remainder of the terms of this Agreement, and without further action by the parties hereto, such provision shall be reformed to the minimum extent necessary to make such provision valid and enforceable.

If Recipient institutes patent litigation against any entity (including a cross-claim or counterclaim in a lawsuit) alleging that the Program itself (excluding combinations of the Program with other software or hardware) infringes such Recipient's patent(s), then such Recipient's rights granted under Section 2(b) shall terminate as of the date such litigation is filed.

All Recipient's rights under this Agreement shall terminate if it fails to comply with any of the material terms or conditions of this Agreement and does not cure such failure in a reasonable period of time after becoming aware of such noncompliance. If all Recipient's rights under this Agreement terminate, Recipient agrees to cease use and distribution of the Program as soon as reasonably practicable. However, Recipient's obligations under this Agreement and any licenses granted by Recipient relating to the Program shall continue and survive.

Everyone is permitted to copy and distribute copies of this Agreement, but in order to avoid inconsistency the Agreement is copyrighted and may only be modified in the following manner. The Agreement Steward reserves the right to publish new versions (including revisions) of this Agreement from time to time. No one other than the Agreement Steward has the right to modify this Agreement. The Eclipse Foundation is the initial Agreement Steward. The Eclipse Foundation may assign the responsibility to serve as the Agreement Steward to a suitable separate entity. Each new version of the Agreement will be given a distinguishing version number. The Program (including Contributions) may always be distributed subject to the version of the Agreement under which it was received. In addition, after a new version of the Agreement is published, Contributor may elect to distribute the Program (including its Contributions) under the new version. Except as expressly stated in Sections 2(a) and 2(b) above, Recipient receives no rights or licenses to the intellectual property of any Contributor under this Agreement, whether expressly, by implication, estoppel or otherwise. All rights in the Program not expressly granted under this Agreement are reserved.

This Agreement is governed by the laws of the State of New York and the intellectual property laws of the United States of America. No party to this Agreement will bring a legal action under this Agreement more than one year after the cause of action arose. Each party waives its rights to a jury trial in any resulting litigation.
```

### Eclipse Public License - v 2.0

<!-- Source: bundled META-INF/LICENSE.md of jakarta.annotation-api 3.0.0 (EPL-2.0 section) -->

```text
# Eclipse Public License - v 2.0

        THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
        PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
        OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.

    1. DEFINITIONS

    "Contribution" means:

      a) in the case of the initial Contributor, the initial content
         Distributed under this Agreement, and

      b) in the case of each subsequent Contributor: 
         i) changes to the Program, and 
         ii) additions to the Program;
      where such changes and/or additions to the Program originate from
      and are Distributed by that particular Contributor. A Contribution
      "originates" from a Contributor if it was added to the Program by
      such Contributor itself or anyone acting on such Contributor's behalf.
      Contributions do not include changes or additions to the Program that
      are not Modified Works.

    "Contributor" means any person or entity that Distributes the Program.

    "Licensed Patents" mean patent claims licensable by a Contributor which
    are necessarily infringed by the use or sale of its Contribution alone
    or when combined with the Program.

    "Program" means the Contributions Distributed in accordance with this
    Agreement.

    "Recipient" means anyone who receives the Program under this Agreement
    or any Secondary License (as applicable), including Contributors.

    "Derivative Works" shall mean any work, whether in Source Code or other
    form, that is based on (or derived from) the Program and for which the
    editorial revisions, annotations, elaborations, or other modifications
    represent, as a whole, an original work of authorship.

    "Modified Works" shall mean any work in Source Code or other form that
    results from an addition to, deletion from, or modification of the
    contents of the Program, including, for purposes of clarity any new file
    in Source Code form that contains any contents of the Program. Modified
    Works shall not include works that contain only declarations,
    interfaces, types, classes, structures, or files of the Program solely
    in each case in order to link to, bind by name, or subclass the Program
    or Modified Works thereof.

    "Distribute" means the acts of a) distributing or b) making available
    in any manner that enables the transfer of a copy.

    "Source Code" means the form of a Program preferred for making
    modifications, including but not limited to software source code,
    documentation source, and configuration files.

    "Secondary License" means either the GNU General Public License,
    Version 2.0, or any later versions of that license, including any
    exceptions or additional permissions as identified by the initial
    Contributor.

    2. GRANT OF RIGHTS

      a) Subject to the terms of this Agreement, each Contributor hereby
      grants Recipient a non-exclusive, worldwide, royalty-free copyright
      license to reproduce, prepare Derivative Works of, publicly display,
      publicly perform, Distribute and sublicense the Contribution of such
      Contributor, if any, and such Derivative Works.

      b) Subject to the terms of this Agreement, each Contributor hereby
      grants Recipient a non-exclusive, worldwide, royalty-free patent
      license under Licensed Patents to make, use, sell, offer to sell,
      import and otherwise transfer the Contribution of such Contributor,
      if any, in Source Code or other form. This patent license shall
      apply to the combination of the Contribution and the Program if, at
      the time the Contribution is added by the Contributor, such addition
      of the Contribution causes such combination to be covered by the
      Licensed Patents. The patent license shall not apply to any other
      combinations which include the Contribution. No hardware per se is
      licensed hereunder.

      c) Recipient understands that although each Contributor grants the
      licenses to its Contributions set forth herein, no assurances are
      provided by any Contributor that the Program does not infringe the
      patent or other intellectual property rights of any other entity.
      Each Contributor disclaims any liability to Recipient for claims
      brought by any other entity based on infringement of intellectual
      property rights or otherwise. As a condition to exercising the
      rights and licenses granted hereunder, each Recipient hereby
      assumes sole responsibility to secure any other intellectual
      property rights needed, if any. For example, if a third party
      patent license is required to allow Recipient to Distribute the
      Program, it is Recipient's responsibility to acquire that license
      before distributing the Program.

      d) Each Contributor represents that to its knowledge it has
      sufficient copyright rights in its Contribution, if any, to grant
      the copyright license set forth in this Agreement.

      e) Notwithstanding the terms of any Secondary License, no
      Contributor makes additional grants to any Recipient (other than
      those set forth in this Agreement) as a result of such Recipient's
      receipt of the Program under the terms of a Secondary License
      (if permitted under the terms of Section 3).

    3. REQUIREMENTS

    3.1 If a Contributor Distributes the Program in any form, then:

      a) the Program must also be made available as Source Code, in
      accordance with section 3.2, and the Contributor must accompany
      the Program with a statement that the Source Code for the Program
      is available under this Agreement, and informs Recipients how to
      obtain it in a reasonable manner on or through a medium customarily
      used for software exchange; and

      b) the Contributor may Distribute the Program under a license
      different than this Agreement, provided that such license:
         i) effectively disclaims on behalf of all other Contributors all
         warranties and conditions, express and implied, including
         warranties or conditions of title and non-infringement, and
         implied warranties or conditions of merchantability and fitness
         for a particular purpose;

         ii) effectively excludes on behalf of all other Contributors all
         liability for damages, including direct, indirect, special,
         incidental and consequential damages, such as lost profits;

         iii) does not attempt to limit or alter the recipients' rights
         in the Source Code under section 3.2; and

         iv) requires any subsequent distribution of the Program by any
         party to be under a license that satisfies the requirements
         of this section 3.

    3.2 When the Program is Distributed as Source Code:

      a) it must be made available under this Agreement, or if the
      Program (i) is combined with other material in a separate file or
      files made available under a Secondary License, and (ii) the initial
      Contributor attached to the Source Code the notice described in
      Exhibit A of this Agreement, then the Program may be made available
      under the terms of such Secondary Licenses, and

      b) a copy of this Agreement must be included with each copy of
      the Program.

    3.3 Contributors may not remove or alter any copyright, patent,
    trademark, attribution notices, disclaimers of warranty, or limitations
    of liability ("notices") contained within the Program from any copy of
    the Program which they Distribute, provided that Contributors may add
    their own appropriate notices.

    4. COMMERCIAL DISTRIBUTION

    Commercial distributors of software may accept certain responsibilities
    with respect to end users, business partners and the like. While this
    license is intended to facilitate the commercial use of the Program,
    the Contributor who includes the Program in a commercial product
    offering should do so in a manner which does not create potential
    liability for other Contributors. Therefore, if a Contributor includes
    the Program in a commercial product offering, such Contributor
    ("Commercial Contributor") hereby agrees to defend and indemnify every
    other Contributor ("Indemnified Contributor") against any losses,
    damages and costs (collectively "Losses") arising from claims, lawsuits
    and other legal actions brought by a third party against the Indemnified
    Contributor to the extent caused by the acts or omissions of such
    Commercial Contributor in connection with its distribution of the Program
    in a commercial product offering. The obligations in this section do not
    apply to any claims or Losses relating to any actual or alleged
    intellectual property infringement. In order to qualify, an Indemnified
    Contributor must: a) promptly notify the Commercial Contributor in
    writing of such claim, and b) allow the Commercial Contributor to control,
    and cooperate with the Commercial Contributor in, the defense and any
    related settlement negotiations. The Indemnified Contributor may
    participate in any such claim at its own expense.

    For example, a Contributor might include the Program in a commercial
    product offering, Product X. That Contributor is then a Commercial
    Contributor. If that Commercial Contributor then makes performance
    claims, or offers warranties related to Product X, those performance
    claims and warranties are such Commercial Contributor's responsibility
    alone. Under this section, the Commercial Contributor would have to
    defend claims against the other Contributors related to those performance
    claims and warranties, and if a court requires any other Contributor to
    pay any damages as a result, the Commercial Contributor must pay
    those damages.

    5. NO WARRANTY

    EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
    PERMITTED BY APPLICABLE LAW, THE PROGRAM IS PROVIDED ON AN "AS IS"
    BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR
    IMPLIED INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF
    TITLE, NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR
    PURPOSE. Each Recipient is solely responsible for determining the
    appropriateness of using and distributing the Program and assumes all
    risks associated with its exercise of rights under this Agreement,
    including but not limited to the risks and costs of program errors,
    compliance with applicable laws, damage to or loss of data, programs
    or equipment, and unavailability or interruption of operations.

    6. DISCLAIMER OF LIABILITY

    EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
    PERMITTED BY APPLICABLE LAW, NEITHER RECIPIENT NOR ANY CONTRIBUTORS
    SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING WITHOUT LIMITATION LOST
    PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
    CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
    ARISING IN ANY WAY OUT OF THE USE OR DISTRIBUTION OF THE PROGRAM OR THE
    EXERCISE OF ANY RIGHTS GRANTED HEREUNDER, EVEN IF ADVISED OF THE
    POSSIBILITY OF SUCH DAMAGES.

    7. GENERAL

    If any provision of this Agreement is invalid or unenforceable under
    applicable law, it shall not affect the validity or enforceability of
    the remainder of the terms of this Agreement, and without further
    action by the parties hereto, such provision shall be reformed to the
    minimum extent necessary to make such provision valid and enforceable.

    If Recipient institutes patent litigation against any entity
    (including a cross-claim or counterclaim in a lawsuit) alleging that the
    Program itself (excluding combinations of the Program with other software
    or hardware) infringes such Recipient's patent(s), then such Recipient's
    rights granted under Section 2(b) shall terminate as of the date such
    litigation is filed.

    All Recipient's rights under this Agreement shall terminate if it
    fails to comply with any of the material terms or conditions of this
    Agreement and does not cure such failure in a reasonable period of
    time after becoming aware of such noncompliance. If all Recipient's
    rights under this Agreement terminate, Recipient agrees to cease use
    and distribution of the Program as soon as reasonably practicable.
    However, Recipient's obligations under this Agreement and any licenses
    granted by Recipient relating to the Program shall continue and survive.

    Everyone is permitted to copy and distribute copies of this Agreement,
    but in order to avoid inconsistency the Agreement is copyrighted and
    may only be modified in the following manner. The Agreement Steward
    reserves the right to publish new versions (including revisions) of
    this Agreement from time to time. No one other than the Agreement
    Steward has the right to modify this Agreement. The Eclipse Foundation
    is the initial Agreement Steward. The Eclipse Foundation may assign the
    responsibility to serve as the Agreement Steward to a suitable separate
    entity. Each new version of the Agreement will be given a distinguishing
    version number. The Program (including Contributions) may always be
    Distributed subject to the version of the Agreement under which it was
    received. In addition, after a new version of the Agreement is published,
    Contributor may elect to Distribute the Program (including its
    Contributions) under the new version.

    Except as expressly stated in Sections 2(a) and 2(b) above, Recipient
    receives no rights or licenses to the intellectual property of any
    Contributor under this Agreement, whether expressly, by implication,
    estoppel or otherwise. All rights in the Program not expressly granted
    under this Agreement are reserved. Nothing in this Agreement is intended
    to be enforceable by any entity that is not a Contributor or Recipient.
    No third-party beneficiary rights are created under this Agreement.

    Exhibit A - Form of Secondary Licenses Notice

    "This Source Code may also be made available under the following 
    Secondary Licenses when the conditions for such availability set forth 
    in the Eclipse Public License, v. 2.0 are satisfied: {name license(s),
    version(s), and exceptions or additional permissions here}."

      Simply including a copy of this Agreement, including this Exhibit A
      is not sufficient to license the Source Code under Secondary Licenses.

      If it is not possible or desirable to put the notice in a particular
      file, then You may include the notice in a location (such as a LICENSE
      file in a relevant directory) where a recipient would be likely to
      look for such a notice.

      You may add additional accurate notices of copyright ownership.
```

### GNU GENERAL PUBLIC LICENSE, Version 2

<!-- Source: bundled META-INF/LICENSE.md of jakarta.annotation-api 3.0.0 (GPL-2.0 section) -->

```text
##    The GNU General Public License (GPL) Version 2, June 1991

    Copyright (C) 1989, 1991 Free Software Foundation, Inc.
    51 Franklin Street, Fifth Floor
    Boston, MA 02110-1335
    USA

    Everyone is permitted to copy and distribute verbatim copies
    of this license document, but changing it is not allowed.

    Preamble

    The licenses for most software are designed to take away your freedom to
    share and change it. By contrast, the GNU General Public License is
    intended to guarantee your freedom to share and change free software--to
    make sure the software is free for all its users. This General Public
    License applies to most of the Free Software Foundation's software and
    to any other program whose authors commit to using it. (Some other Free
    Software Foundation software is covered by the GNU Library General
    Public License instead.) You can apply it to your programs, too.

    When we speak of free software, we are referring to freedom, not price.
    Our General Public Licenses are designed to make sure that you have the
    freedom to distribute copies of free software (and charge for this
    service if you wish), that you receive source code or can get it if you
    want it, that you can change the software or use pieces of it in new
    free programs; and that you know you can do these things.

    To protect your rights, we need to make restrictions that forbid anyone
    to deny you these rights or to ask you to surrender the rights. These
    restrictions translate to certain responsibilities for you if you
    distribute copies of the software, or if you modify it.

    For example, if you distribute copies of such a program, whether gratis
    or for a fee, you must give the recipients all the rights that you have.
    You must make sure that they, too, receive or can get the source code.
    And you must show them these terms so they know their rights.

    We protect your rights with two steps: (1) copyright the software, and
    (2) offer you this license which gives you legal permission to copy,
    distribute and/or modify the software.

    Also, for each author's protection and ours, we want to make certain
    that everyone understands that there is no warranty for this free
    software. If the software is modified by someone else and passed on, we
    want its recipients to know that what they have is not the original, so
    that any problems introduced by others will not reflect on the original
    authors' reputations.

    Finally, any free program is threatened constantly by software patents.
    We wish to avoid the danger that redistributors of a free program will
    individually obtain patent licenses, in effect making the program
    proprietary. To prevent this, we have made it clear that any patent must
    be licensed for everyone's free use or not licensed at all.

    The precise terms and conditions for copying, distribution and
    modification follow.

    TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

    0. This License applies to any program or other work which contains a
    notice placed by the copyright holder saying it may be distributed under
    the terms of this General Public License. The "Program", below, refers
    to any such program or work, and a "work based on the Program" means
    either the Program or any derivative work under copyright law: that is
    to say, a work containing the Program or a portion of it, either
    verbatim or with modifications and/or translated into another language.
    (Hereinafter, translation is included without limitation in the term
    "modification".) Each licensee is addressed as "you".

    Activities other than copying, distribution and modification are not
    covered by this License; they are outside its scope. The act of running
    the Program is not restricted, and the output from the Program is
    covered only if its contents constitute a work based on the Program
    (independent of having been made by running the Program). Whether that
    is true depends on what the Program does.

    1. You may copy and distribute verbatim copies of the Program's source
    code as you receive it, in any medium, provided that you conspicuously
    and appropriately publish on each copy an appropriate copyright notice
    and disclaimer of warranty; keep intact all the notices that refer to
    this License and to the absence of any warranty; and give any other
    recipients of the Program a copy of this License along with the Program.

    You may charge a fee for the physical act of transferring a copy, and
    you may at your option offer warranty protection in exchange for a fee.

    2. You may modify your copy or copies of the Program or any portion of
    it, thus forming a work based on the Program, and copy and distribute
    such modifications or work under the terms of Section 1 above, provided
    that you also meet all of these conditions:

        a) You must cause the modified files to carry prominent notices
        stating that you changed the files and the date of any change.

        b) You must cause any work that you distribute or publish, that in
        whole or in part contains or is derived from the Program or any part
        thereof, to be licensed as a whole at no charge to all third parties
        under the terms of this License.

        c) If the modified program normally reads commands interactively
        when run, you must cause it, when started running for such
        interactive use in the most ordinary way, to print or display an
        announcement including an appropriate copyright notice and a notice
        that there is no warranty (or else, saying that you provide a
        warranty) and that users may redistribute the program under these
        conditions, and telling the user how to view a copy of this License.
        (Exception: if the Program itself is interactive but does not
        normally print such an announcement, your work based on the Program
        is not required to print an announcement.)

    These requirements apply to the modified work as a whole. If
    identifiable sections of that work are not derived from the Program, and
    can be reasonably considered independent and separate works in
    themselves, then this License, and its terms, do not apply to those
    sections when you distribute them as separate works. But when you
    distribute the same sections as part of a whole which is a work based on
    the Program, the distribution of the whole must be on the terms of this
    License, whose permissions for other licensees extend to the entire
    whole, and thus to each and every part regardless of who wrote it.

    Thus, it is not the intent of this section to claim rights or contest
    your rights to work written entirely by you; rather, the intent is to
    exercise the right to control the distribution of derivative or
    collective works based on the Program.

    In addition, mere aggregation of another work not based on the Program
    with the Program (or with a work based on the Program) on a volume of a
    storage or distribution medium does not bring the other work under the
    scope of this License.

    3. You may copy and distribute the Program (or a work based on it,
    under Section 2) in object code or executable form under the terms of
    Sections 1 and 2 above provided that you also do one of the following:

        a) Accompany it with the complete corresponding machine-readable
        source code, which must be distributed under the terms of Sections 1
        and 2 above on a medium customarily used for software interchange; or,

        b) Accompany it with a written offer, valid for at least three
        years, to give any third party, for a charge no more than your cost
        of physically performing source distribution, a complete
        machine-readable copy of the corresponding source code, to be
        distributed under the terms of Sections 1 and 2 above on a medium
        customarily used for software interchange; or,

        c) Accompany it with the information you received as to the offer to
        distribute corresponding source code. (This alternative is allowed
        only for noncommercial distribution and only if you received the
        program in object code or executable form with such an offer, in
        accord with Subsection b above.)

    The source code for a work means the preferred form of the work for
    making modifications to it. For an executable work, complete source code
    means all the source code for all modules it contains, plus any
    associated interface definition files, plus the scripts used to control
    compilation and installation of the executable. However, as a special
    exception, the source code distributed need not include anything that is
    normally distributed (in either source or binary form) with the major
    components (compiler, kernel, and so on) of the operating system on
    which the executable runs, unless that component itself accompanies the
    executable.

    If distribution of executable or object code is made by offering access
    to copy from a designated place, then offering equivalent access to copy
    the source code from the same place counts as distribution of the source
    code, even though third parties are not compelled to copy the source
    along with the object code.

    4. You may not copy, modify, sublicense, or distribute the Program
    except as expressly provided under this License. Any attempt otherwise
    to copy, modify, sublicense or distribute the Program is void, and will
    automatically terminate your rights under this License. However, parties
    who have received copies, or rights, from you under this License will
    not have their licenses terminated so long as such parties remain in
    full compliance.

    5. You are not required to accept this License, since you have not
    signed it. However, nothing else grants you permission to modify or
    distribute the Program or its derivative works. These actions are
    prohibited by law if you do not accept this License. Therefore, by
    modifying or distributing the Program (or any work based on the
    Program), you indicate your acceptance of this License to do so, and all
    its terms and conditions for copying, distributing or modifying the
    Program or works based on it.

    6. Each time you redistribute the Program (or any work based on the
    Program), the recipient automatically receives a license from the
    original licensor to copy, distribute or modify the Program subject to
    these terms and conditions. You may not impose any further restrictions
    on the recipients' exercise of the rights granted herein. You are not
    responsible for enforcing compliance by third parties to this License.

    7. If, as a consequence of a court judgment or allegation of patent
    infringement or for any other reason (not limited to patent issues),
    conditions are imposed on you (whether by court order, agreement or
    otherwise) that contradict the conditions of this License, they do not
    excuse you from the conditions of this License. If you cannot distribute
    so as to satisfy simultaneously your obligations under this License and
    any other pertinent obligations, then as a consequence you may not
    distribute the Program at all. For example, if a patent license would
    not permit royalty-free redistribution of the Program by all those who
    receive copies directly or indirectly through you, then the only way you
    could satisfy both it and this License would be to refrain entirely from
    distribution of the Program.

    If any portion of this section is held invalid or unenforceable under
    any particular circumstance, the balance of the section is intended to
    apply and the section as a whole is intended to apply in other
    circumstances.

    It is not the purpose of this section to induce you to infringe any
    patents or other property right claims or to contest validity of any
    such claims; this section has the sole purpose of protecting the
    integrity of the free software distribution system, which is implemented
    by public license practices. Many people have made generous
    contributions to the wide range of software distributed through that
    system in reliance on consistent application of that system; it is up to
    the author/donor to decide if he or she is willing to distribute
    software through any other system and a licensee cannot impose that choice.

    This section is intended to make thoroughly clear what is believed to be
    a consequence of the rest of this License.

    8. If the distribution and/or use of the Program is restricted in
    certain countries either by patents or by copyrighted interfaces, the
    original copyright holder who places the Program under this License may
    add an explicit geographical distribution limitation excluding those
    countries, so that distribution is permitted only in or among countries
    not thus excluded. In such case, this License incorporates the
    limitation as if written in the body of this License.

    9. The Free Software Foundation may publish revised and/or new
    versions of the General Public License from time to time. Such new
    versions will be similar in spirit to the present version, but may
    differ in detail to address new problems or concerns.

    Each version is given a distinguishing version number. If the Program
    specifies a version number of this License which applies to it and "any
    later version", you have the option of following the terms and
    conditions either of that version or of any later version published by
    the Free Software Foundation. If the Program does not specify a version
    number of this License, you may choose any version ever published by the
    Free Software Foundation.

    10. If you wish to incorporate parts of the Program into other free
    programs whose distribution conditions are different, write to the
    author to ask for permission. For software which is copyrighted by the
    Free Software Foundation, write to the Free Software Foundation; we
    sometimes make exceptions for this. Our decision will be guided by the
    two goals of preserving the free status of all derivatives of our free
    software and of promoting the sharing and reuse of software generally.

    NO WARRANTY

    11. BECAUSE THE PROGRAM IS LICENSED FREE OF CHARGE, THERE IS NO
    WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW.
    EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR
    OTHER PARTIES PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND,
    EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
    WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE
    ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH
    YOU. SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL
    NECESSARY SERVICING, REPAIR OR CORRECTION.

    12. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN
    WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY
    AND/OR REDISTRIBUTE THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR
    DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL
    DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE PROGRAM
    (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING RENDERED
    INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A FAILURE OF
    THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS), EVEN IF SUCH HOLDER OR
    OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

    END OF TERMS AND CONDITIONS

    How to Apply These Terms to Your New Programs

    If you develop a new program, and you want it to be of the greatest
    possible use to the public, the best way to achieve this is to make it
    free software which everyone can redistribute and change under these terms.

    To do so, attach the following notices to the program. It is safest to
    attach them to the start of each source file to most effectively convey
    the exclusion of warranty; and each file should have at least the
    "copyright" line and a pointer to where the full notice is found.

        One line to give the program's name and a brief idea of what it does.
        Copyright (C) <year> <name of author>

        This program is free software; you can redistribute it and/or modify
        it under the terms of the GNU General Public License as published by
        the Free Software Foundation; either version 2 of the License, or
        (at your option) any later version.

        This program is distributed in the hope that it will be useful, but
        WITHOUT ANY WARRANTY; without even the implied warranty of
        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
        General Public License for more details.

        You should have received a copy of the GNU General Public License
        along with this program; if not, write to the Free Software
        Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1335 USA

    Also add information on how to contact you by electronic and paper mail.

    If the program is interactive, make it output a short notice like this
    when it starts in an interactive mode:

        Gnomovision version 69, Copyright (C) year name of author
        Gnomovision comes with ABSOLUTELY NO WARRANTY; for details type
        `show w'. This is free software, and you are welcome to redistribute
        it under certain conditions; type `show c' for details.

    The hypothetical commands `show w' and `show c' should show the
    appropriate parts of the General Public License. Of course, the commands
    you use may be called something other than `show w' and `show c'; they
    could even be mouse-clicks or menu items--whatever suits your program.

    You should also get your employer (if you work as a programmer) or your
    school, if any, to sign a "copyright disclaimer" for the program, if
    necessary. Here is a sample; alter the names:

        Yoyodyne, Inc., hereby disclaims all copyright interest in the
        program `Gnomovision' (which makes passes at compilers) written by
        James Hacker.

        signature of Ty Coon, 1 April 1989
        Ty Coon, President of Vice

    This General Public License does not permit incorporating your program
    into proprietary programs. If your program is a subroutine library, you
    may consider it more useful to permit linking proprietary applications
    with the library. If this is what you want to do, use the GNU Library
    General Public License instead of this License.
```

### GNU GENERAL PUBLIC LICENSE, Version 2 + Classpath Exception

<!-- Source: bundled META-INF/LICENSE.md of jakarta.annotation-api 3.0.0 (Classpath Exception section) -->

```text
## CLASSPATH EXCEPTION

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library.  Thus, the terms and
    conditions of the GNU General Public License version 2 cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under
    terms of your choice, provided that you also meet, for each linked
    independent module, the terms and conditions of the license of that
    module.  An independent module is a module which is not derived from or
    based on this library.  If you modify this library, you may extend this
    exception to your version of the library, but you are not obligated to
    do so.  If you do not wish to do so, delete this exception statement
    from your version.
```

### GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1

<!-- Source: https://raw.githubusercontent.com/spdx/license-list-data/main/text/LGPL-2.1-only.txt -->

```text
GNU LESSER GENERAL PUBLIC LICENSE

Version 2.1, February 1999

Copyright (C) 1991, 1999 Free Software Foundation, Inc.
51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA

Everyone is permitted to copy and distribute verbatim copies of this license document, but changing it is not allowed.

[This is the first released version of the Lesser GPL.  It also counts as the successor of the GNU Library Public License, version 2, hence the version number 2.1.]

Preamble

The licenses for most software are designed to take away your freedom to share and change it. By contrast, the GNU General Public Licenses are intended to guarantee your freedom to share and change free software--to make sure the software is free for all its users.

This license, the Lesser General Public License, applies to some specially designated software packages--typically libraries--of the Free Software Foundation and other authors who decide to use it. You can use it too, but we suggest you first think carefully about whether this license or the ordinary General Public License is the better strategy to use in any particular case, based on the explanations below.

When we speak of free software, we are referring to freedom of use, not price. Our General Public Licenses are designed to make sure that you have the freedom to distribute copies of free software (and charge for this service if you wish); that you receive source code or can get it if you want it; that you can change the software and use pieces of it in new free programs; and that you are informed that you can do these things.

To protect your rights, we need to make restrictions that forbid distributors to deny you these rights or to ask you to surrender these rights. These restrictions translate to certain responsibilities for you if you distribute copies of the library or if you modify it.

For example, if you distribute copies of the library, whether gratis or for a fee, you must give the recipients all the rights that we gave you. You must make sure that they, too, receive or can get the source code. If you link other code with the library, you must provide complete object files to the recipients, so that they can relink them with the library after making changes to the library and recompiling it. And you must show them these terms so they know their rights.

We protect your rights with a two-step method: (1) we copyright the library, and (2) we offer you this license, which gives you legal permission to copy, distribute and/or modify the library.

To protect each distributor, we want to make it very clear that there is no warranty for the free library. Also, if the library is modified by someone else and passed on, the recipients should know that what they have is not the original version, so that the original author's reputation will not be affected by problems that might be introduced by others.

Finally, software patents pose a constant threat to the existence of any free program. We wish to make sure that a company cannot effectively restrict the users of a free program by obtaining a restrictive license from a patent holder. Therefore, we insist that any patent license obtained for a version of the library must be consistent with the full freedom of use specified in this license.

Most GNU software, including some libraries, is covered by the ordinary GNU General Public License. This license, the GNU Lesser General Public License, applies to certain designated libraries, and is quite different from the ordinary General Public License. We use this license for certain libraries in order to permit linking those libraries into non-free programs.

When a program is linked with a library, whether statically or using a shared library, the combination of the two is legally speaking a combined work, a derivative of the original library. The ordinary General Public License therefore permits such linking only if the entire combination fits its criteria of freedom. The Lesser General Public License permits more lax criteria for linking other code with the library.

We call this license the "Lesser" General Public License because it does Less to protect the user's freedom than the ordinary General Public License. It also provides other free software developers Less of an advantage over competing non-free programs. These disadvantages are the reason we use the ordinary General Public License for many libraries. However, the Lesser license provides advantages in certain special circumstances.

For example, on rare occasions, there may be a special need to encourage the widest possible use of a certain library, so that it becomes a de-facto standard. To achieve this, non-free programs must be allowed to use the library. A more frequent case is that a free library does the same job as widely used non-free libraries. In this case, there is little to gain by limiting the free library to free software only, so we use the Lesser General Public License.

In other cases, permission to use a particular library in non-free programs enables a greater number of people to use a large body of free software. For example, permission to use the GNU C Library in non-free programs enables many more people to use the whole GNU operating system, as well as its variant, the GNU/Linux operating system.

Although the Lesser General Public License is Less protective of the users' freedom, it does ensure that the user of a program that is linked with the Library has the freedom and the wherewithal to run that program using a modified version of the Library.

The precise terms and conditions for copying, distribution and modification follow. Pay close attention to the difference between a "work based on the library" and a "work that uses the library". The former contains code derived from the library, whereas the latter must be combined with the library in order to run.

GNU LESSER GENERAL PUBLIC LICENSE
TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

0. This License Agreement applies to any software library or other program which contains a notice placed by the copyright holder or other authorized party saying it may be distributed under the terms of this Lesser General Public License (also called "this License"). Each licensee is addressed as "you".

A "library" means a collection of software functions and/or data prepared so as to be conveniently linked with application programs (which use some of those functions and data) to form executables.

The "Library", below, refers to any such software library or work which has been distributed under these terms. A "work based on the Library" means either the Library or any derivative work under copyright law: that is to say, a work containing the Library or a portion of it, either verbatim or with modifications and/or translated straightforwardly into another language. (Hereinafter, translation is included without limitation in the term "modification".)

"Source code" for a work means the preferred form of the work for making modifications to it. For a library, complete source code means all the source code for all modules it contains, plus any associated interface definition files, plus the scripts used to control compilation and installation of the library.

Activities other than copying, distribution and modification are not covered by this License; they are outside its scope. The act of running a program using the Library is not restricted, and output from such a program is covered only if its contents constitute a work based on the Library (independent of the use of the Library in a tool for writing it). Whether that is true depends on what the Library does and what the program that uses the Library does.

1. You may copy and distribute verbatim copies of the Library's complete source code as you receive it, in any medium, provided that you conspicuously and appropriately publish on each copy an appropriate copyright notice and disclaimer of warranty; keep intact all the notices that refer to this License and to the absence of any warranty; and distribute a copy of this License along with the Library.

You may charge a fee for the physical act of transferring a copy, and you may at your option offer warranty protection in exchange for a fee.

2. You may modify your copy or copies of the Library or any portion of it, thus forming a work based on the Library, and copy and distribute such modifications or work under the terms of Section 1 above, provided that you also meet all of these conditions:

     a) The modified work must itself be a software library.

     b) You must cause the files modified to carry prominent notices stating that you changed the files and the date of any change.

     c) You must cause the whole of the work to be licensed at no charge to all third parties under the terms of this License.

     d) If a facility in the modified Library refers to a function or a table of data to be supplied by an application program that uses the facility, other than as an argument passed when the facility is invoked, then you must make a good faith effort to ensure that, in the event an application does not supply such function or table, the facility still operates, and performs whatever part of its purpose remains meaningful.

(For example, a function in a library to compute square roots has a purpose that is entirely well-defined independent of the application. Therefore, Subsection 2d requires that any application-supplied function or table used by this function must be optional: if the application does not supply it, the square root function must still compute square roots.)

These requirements apply to the modified work as a whole. If identifiable sections of that work are not derived from the Library, and can be reasonably considered independent and separate works in themselves, then this License, and its terms, do not apply to those sections when you distribute them as separate works. But when you distribute the same sections as part of a whole which is a work based on the Library, the distribution of the whole must be on the terms of this License, whose permissions for other licensees extend to the entire whole, and thus to each and every part regardless of who wrote it.

Thus, it is not the intent of this section to claim rights or contest your rights to work written entirely by you; rather, the intent is to exercise the right to control the distribution of derivative or collective works based on the Library.

In addition, mere aggregation of another work not based on the Library with the Library (or with a work based on the Library) on a volume of a storage or distribution medium does not bring the other work under the scope of this License.

3. You may opt to apply the terms of the ordinary GNU General Public License instead of this License to a given copy of the Library. To do this, you must alter all the notices that refer to this License, so that they refer to the ordinary GNU General Public License, version 2, instead of to this License. (If a newer version than version 2 of the ordinary GNU General Public License has appeared, then you can specify that version instead if you wish.) Do not make any other change in these notices.

Once this change is made in a given copy, it is irreversible for that copy, so the ordinary GNU General Public License applies to all subsequent copies and derivative works made from that copy.

This option is useful when you wish to copy part of the code of the Library into a program that is not a library.

4. You may copy and distribute the Library (or a portion or derivative of it, under Section 2) in object code or executable form under the terms of Sections 1 and 2 above provided that you accompany it with the complete corresponding machine-readable source code, which must be distributed under the terms of Sections 1 and 2 above on a medium customarily used for software interchange.

If distribution of object code is made by offering access to copy from a designated place, then offering equivalent access to copy the source code from the same place satisfies the requirement to distribute the source code, even though third parties are not compelled to copy the source along with the object code.

5. A program that contains no derivative of any portion of the Library, but is designed to work with the Library by being compiled or linked with it, is called a "work that uses the Library". Such a work, in isolation, is not a derivative work of the Library, and therefore falls outside the scope of this License.

However, linking a "work that uses the Library" with the Library creates an executable that is a derivative of the Library (because it contains portions of the Library), rather than a "work that uses the library". The executable is therefore covered by this License. Section 6 states terms for distribution of such executables.

When a "work that uses the Library" uses material from a header file that is part of the Library, the object code for the work may be a derivative work of the Library even though the source code is not. Whether this is true is especially significant if the work can be linked without the Library, or if the work is itself a library. The threshold for this to be true is not precisely defined by law.

If such an object file uses only numerical parameters, data structure layouts and accessors, and small macros and small inline functions (ten lines or less in length), then the use of the object file is unrestricted, regardless of whether it is legally a derivative work. (Executables containing this object code plus portions of the Library will still fall under Section 6.)

Otherwise, if the work is a derivative of the Library, you may distribute the object code for the work under the terms of Section 6. Any executables containing that work also fall under Section 6, whether or not they are linked directly with the Library itself.

6. As an exception to the Sections above, you may also combine or link a "work that uses the Library" with the Library to produce a work containing portions of the Library, and distribute that work under terms of your choice, provided that the terms permit modification of the work for the customer's own use and reverse engineering for debugging such modifications.

You must give prominent notice with each copy of the work that the Library is used in it and that the Library and its use are covered by this License. You must supply a copy of this License. If the work during execution displays copyright notices, you must include the copyright notice for the Library among them, as well as a reference directing the user to the copy of this License. Also, you must do one of these things:

     a) Accompany the work with the complete corresponding machine-readable source code for the Library including whatever changes were used in the work (which must be distributed under Sections 1 and 2 above); and, if the work is an executable linked with the Library, with the complete machine-readable "work that uses the Library", as object code and/or source code, so that the user can modify the Library and then relink to produce a modified executable containing the modified Library. (It is understood that the user who changes the contents of definitions files in the Library will not necessarily be able to recompile the application to use the modified definitions.)

     b) Use a suitable shared library mechanism for linking with the Library. A suitable mechanism is one that (1) uses at run time a copy of the library already present on the user's computer system, rather than copying library functions into the executable, and (2) will operate properly with a modified version of the library, if the user installs one, as long as the modified version is interface-compatible with the version that the work was made with.

     c) Accompany the work with a written offer, valid for at least three years, to give the same user the materials specified in Subsection 6a, above, for a charge no more than the cost of performing this distribution.

     d) If distribution of the work is made by offering access to copy from a designated place, offer equivalent access to copy the above specified materials from the same place.

     e) Verify that the user has already received a copy of these materials or that you have already sent this user a copy.

For an executable, the required form of the "work that uses the Library" must include any data and utility programs needed for reproducing the executable from it. However, as a special exception, the materials to be distributed need not include anything that is normally distributed (in either source or binary form) with the major components (compiler, kernel, and so on) of the operating system on which the executable runs, unless that component itself accompanies the executable.

It may happen that this requirement contradicts the license restrictions of other proprietary libraries that do not normally accompany the operating system. Such a contradiction means you cannot use both them and the Library together in an executable that you distribute.

7. You may place library facilities that are a work based on the Library side-by-side in a single library together with other library facilities not covered by this License, and distribute such a combined library, provided that the separate distribution of the work based on the Library and of the other library facilities is otherwise permitted, and provided that you do these two things:

     a) Accompany the combined library with a copy of the same work based on the Library, uncombined with any other library facilities. This must be distributed under the terms of the Sections above.

     b) Give prominent notice with the combined library of the fact that part of it is a work based on the Library, and explaining where to find the accompanying uncombined form of the same work.

8. You may not copy, modify, sublicense, link with, or distribute the Library except as expressly provided under this License. Any attempt otherwise to copy, modify, sublicense, link with, or distribute the Library is void, and will automatically terminate your rights under this License. However, parties who have received copies, or rights, from you under this License will not have their licenses terminated so long as such parties remain in full compliance.

9. You are not required to accept this License, since you have not signed it. However, nothing else grants you permission to modify or distribute the Library or its derivative works. These actions are prohibited by law if you do not accept this License. Therefore, by modifying or distributing the Library (or any work based on the Library), you indicate your acceptance of this License to do so, and all its terms and conditions for copying, distributing or modifying the Library or works based on it.

10. Each time you redistribute the Library (or any work based on the Library), the recipient automatically receives a license from the original licensor to copy, distribute, link with or modify the Library subject to these terms and conditions. You may not impose any further restrictions on the recipients' exercise of the rights granted herein. You are not responsible for enforcing compliance by third parties with this License.

11. If, as a consequence of a court judgment or allegation of patent infringement or for any other reason (not limited to patent issues), conditions are imposed on you (whether by court order, agreement or otherwise) that contradict the conditions of this License, they do not excuse you from the conditions of this License. If you cannot distribute so as to satisfy simultaneously your obligations under this License and any other pertinent obligations, then as a consequence you may not distribute the Library at all. For example, if a patent license would not permit royalty-free redistribution of the Library by all those who receive copies directly or indirectly through you, then the only way you could satisfy both it and this License would be to refrain entirely from distribution of the Library.

If any portion of this section is held invalid or unenforceable under any particular circumstance, the balance of the section is intended to apply, and the section as a whole is intended to apply in other circumstances.

It is not the purpose of this section to induce you to infringe any patents or other property right claims or to contest validity of any such claims; this section has the sole purpose of protecting the integrity of the free software distribution system which is implemented by public license practices. Many people have made generous contributions to the wide range of software distributed through that system in reliance on consistent application of that system; it is up to the author/donor to decide if he or she is willing to distribute software through any other system and a licensee cannot impose that choice.

This section is intended to make thoroughly clear what is believed to be a consequence of the rest of this License.

12. If the distribution and/or use of the Library is restricted in certain countries either by patents or by copyrighted interfaces, the original copyright holder who places the Library under this License may add an explicit geographical distribution limitation excluding those countries, so that distribution is permitted only in or among countries not thus excluded. In such case, this License incorporates the limitation as if written in the body of this License.

13. The Free Software Foundation may publish revised and/or new versions of the Lesser General Public License from time to time. Such new versions will be similar in spirit to the present version, but may differ in detail to address new problems or concerns.

Each version is given a distinguishing version number. If the Library specifies a version number of this License which applies to it and "any later version", you have the option of following the terms and conditions either of that version or of any later version published by the Free Software Foundation. If the Library does not specify a license version number, you may choose any version ever published by the Free Software Foundation.

14. If you wish to incorporate parts of the Library into other free programs whose distribution conditions are incompatible with these, write to the author to ask for permission. For software which is copyrighted by the Free Software Foundation, write to the Free Software Foundation; we sometimes make exceptions for this. Our decision will be guided by the two goals of preserving the free status of all derivatives of our free software and of promoting the sharing and reuse of software generally.

NO WARRANTY

15. BECAUSE THE LIBRARY IS LICENSED FREE OF CHARGE, THERE IS NO WARRANTY FOR THE LIBRARY, TO THE EXTENT PERMITTED BY APPLICABLE LAW. EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR OTHER PARTIES PROVIDE THE LIBRARY "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE LIBRARY IS WITH YOU. SHOULD THE LIBRARY PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

16. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY AND/OR REDISTRIBUTE THE LIBRARY AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE LIBRARY (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A FAILURE OF THE LIBRARY TO OPERATE WITH ANY OTHER SOFTWARE), EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

END OF TERMS AND CONDITIONS

How to Apply These Terms to Your New Libraries

If you develop a new library, and you want it to be of the greatest possible use to the public, we recommend making it free software that everyone can redistribute and change. You can do so by permitting redistribution under these terms (or, alternatively, under the terms of the ordinary General Public License).

To apply these terms, attach the following notices to the library. It is safest to attach them to the start of each source file to most effectively convey the exclusion of warranty; and each file should have at least the "copyright" line and a pointer to where the full notice is found.

     one line to give the library's name and an idea of what it does.
     Copyright (C) year  name of author

     This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1 of the License, or (at your option) any later version.

     This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.

     You should have received a copy of the GNU Lesser General Public License along with this library; if not, write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA Also add information on how to contact you by electronic and paper mail.

You should also get your employer (if you work as a programmer) or your school, if any, to sign a "copyright disclaimer" for the library, if necessary. Here is a sample; alter the names:

Yoyodyne, Inc., hereby disclaims all copyright interest in
the library `Frob' (a library for tweaking knobs) written
by James Random Hacker.

signature of Ty Coon, 1 April 1990
Ty Coon, President of Vice
That's all there is to it!
```

### ISC

<!-- Source: https://raw.githubusercontent.com/spdx/license-list-data/main/text/ISC.txt -->

```text
ISC License

Copyright <year> <owner> 

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

### MIT License

<!-- Source: https://raw.githubusercontent.com/spdx/license-list-data/main/text/MIT.txt -->

```text
MIT License

Copyright (c) <year> <copyright holders>

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### MIT-0

<!-- Source: https://raw.githubusercontent.com/spdx/license-list-data/main/text/MIT-0.txt -->

```text
MIT No Attribution

Copyright <YEAR> <COPYRIGHT HOLDER>

Permission is hereby granted, free of charge, to any person obtaining a copy of this
software and associated documentation files (the "Software"), to deal in the Software
without restriction, including without limitation the rights to use, copy, modify,
merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### MPL-2.0

<!-- Source: bundled LICENSE-MPL of dompurify 3.4.12 -->

```text
Mozilla Public License Version 2.0
==================================

1. Definitions
--------------

1.1. "Contributor"
    means each individual or legal entity that creates, contributes to
    the creation of, or owns Covered Software.

1.2. "Contributor Version"
    means the combination of the Contributions of others (if any) used
    by a Contributor and that particular Contributor's Contribution.

1.3. "Contribution"
    means Covered Software of a particular Contributor.

1.4. "Covered Software"
    means Source Code Form to which the initial Contributor has attached
    the notice in Exhibit A, the Executable Form of such Source Code
    Form, and Modifications of such Source Code Form, in each case
    including portions thereof.

1.5. "Incompatible With Secondary Licenses"
    means

    (a) that the initial Contributor has attached the notice described
        in Exhibit B to the Covered Software; or

    (b) that the Covered Software was made available under the terms of
        version 1.1 or earlier of the License, but not also under the
        terms of a Secondary License.

1.6. "Executable Form"
    means any form of the work other than Source Code Form.

1.7. "Larger Work"
    means a work that combines Covered Software with other material, in 
    a separate file or files, that is not Covered Software.

1.8. "License"
    means this document.

1.9. "Licensable"
    means having the right to grant, to the maximum extent possible,
    whether at the time of the initial grant or subsequently, any and
    all of the rights conveyed by this License.

1.10. "Modifications"
    means any of the following:

    (a) any file in Source Code Form that results from an addition to,
        deletion from, or modification of the contents of Covered
        Software; or

    (b) any new file in Source Code Form that contains any Covered
        Software.

1.11. "Patent Claims" of a Contributor
    means any patent claim(s), including without limitation, method,
    process, and apparatus claims, in any patent Licensable by such
    Contributor that would be infringed, but for the grant of the
    License, by the making, using, selling, offering for sale, having
    made, import, or transfer of either its Contributions or its
    Contributor Version.

1.12. "Secondary License"
    means either the GNU General Public License, Version 2.0, the GNU
    Lesser General Public License, Version 2.1, the GNU Affero General
    Public License, Version 3.0, or any later versions of those
    licenses.

1.13. "Source Code Form"
    means the form of the work preferred for making modifications.

1.14. "You" (or "Your")
    means an individual or a legal entity exercising rights under this
    License. For legal entities, "You" includes any entity that
    controls, is controlled by, or is under common control with You. For
    purposes of this definition, "control" means (a) the power, direct
    or indirect, to cause the direction or management of such entity,
    whether by contract or otherwise, or (b) ownership of more than
    fifty percent (50%) of the outstanding shares or beneficial
    ownership of such entity.

2. License Grants and Conditions
--------------------------------

2.1. Grants

Each Contributor hereby grants You a world-wide, royalty-free,
non-exclusive license:

(a) under intellectual property rights (other than patent or trademark)
    Licensable by such Contributor to use, reproduce, make available,
    modify, display, perform, distribute, and otherwise exploit its
    Contributions, either on an unmodified basis, with Modifications, or
    as part of a Larger Work; and

(b) under Patent Claims of such Contributor to make, use, sell, offer
    for sale, have made, import, and otherwise transfer either its
    Contributions or its Contributor Version.

2.2. Effective Date

The licenses granted in Section 2.1 with respect to any Contribution
become effective for each Contribution on the date the Contributor first
distributes such Contribution.

2.3. Limitations on Grant Scope

The licenses granted in this Section 2 are the only rights granted under
this License. No additional rights or licenses will be implied from the
distribution or licensing of Covered Software under this License.
Notwithstanding Section 2.1(b) above, no patent license is granted by a
Contributor:

(a) for any code that a Contributor has removed from Covered Software;
    or

(b) for infringements caused by: (i) Your and any other third party's
    modifications of Covered Software, or (ii) the combination of its
    Contributions with other software (except as part of its Contributor
    Version); or

(c) under Patent Claims infringed by Covered Software in the absence of
    its Contributions.

This License does not grant any rights in the trademarks, service marks,
or logos of any Contributor (except as may be necessary to comply with
the notice requirements in Section 3.4).

2.4. Subsequent Licenses

No Contributor makes additional grants as a result of Your choice to
distribute the Covered Software under a subsequent version of this
License (see Section 10.2) or under the terms of a Secondary License (if
permitted under the terms of Section 3.3).

2.5. Representation

Each Contributor represents that the Contributor believes its
Contributions are its original creation(s) or it has sufficient rights
to grant the rights to its Contributions conveyed by this License.

2.6. Fair Use

This License is not intended to limit any rights You have under
applicable copyright doctrines of fair use, fair dealing, or other
equivalents.

2.7. Conditions

Sections 3.1, 3.2, 3.3, and 3.4 are conditions of the licenses granted
in Section 2.1.

3. Responsibilities
-------------------

3.1. Distribution of Source Form

All distribution of Covered Software in Source Code Form, including any
Modifications that You create or to which You contribute, must be under
the terms of this License. You must inform recipients that the Source
Code Form of the Covered Software is governed by the terms of this
License, and how they can obtain a copy of this License. You may not
attempt to alter or restrict the recipients' rights in the Source Code
Form.

3.2. Distribution of Executable Form

If You distribute Covered Software in Executable Form then:

(a) such Covered Software must also be made available in Source Code
    Form, as described in Section 3.1, and You must inform recipients of
    the Executable Form how they can obtain a copy of such Source Code
    Form by reasonable means in a timely manner, at a charge no more
    than the cost of distribution to the recipient; and

(b) You may distribute such Executable Form under the terms of this
    License, or sublicense it under different terms, provided that the
    license for the Executable Form does not attempt to limit or alter
    the recipients' rights in the Source Code Form under this License.

3.3. Distribution of a Larger Work

You may create and distribute a Larger Work under terms of Your choice,
provided that You also comply with the requirements of this License for
the Covered Software. If the Larger Work is a combination of Covered
Software with a work governed by one or more Secondary Licenses, and the
Covered Software is not Incompatible With Secondary Licenses, this
License permits You to additionally distribute such Covered Software
under the terms of such Secondary License(s), so that the recipient of
the Larger Work may, at their option, further distribute the Covered
Software under the terms of either this License or such Secondary
License(s).

3.4. Notices

You may not remove or alter the substance of any license notices
(including copyright notices, patent notices, disclaimers of warranty,
or limitations of liability) contained within the Source Code Form of
the Covered Software, except that You may alter any license notices to
the extent required to remedy known factual inaccuracies.

3.5. Application of Additional Terms

You may choose to offer, and to charge a fee for, warranty, support,
indemnity or liability obligations to one or more recipients of Covered
Software. However, You may do so only on Your own behalf, and not on
behalf of any Contributor. You must make it absolutely clear that any
such warranty, support, indemnity, or liability obligation is offered by
You alone, and You hereby agree to indemnify every Contributor for any
liability incurred by such Contributor as a result of warranty, support,
indemnity or liability terms You offer. You may include additional
disclaimers of warranty and limitations of liability specific to any
jurisdiction.

4. Inability to Comply Due to Statute or Regulation
---------------------------------------------------

If it is impossible for You to comply with any of the terms of this
License with respect to some or all of the Covered Software due to
statute, judicial order, or regulation then You must: (a) comply with
the terms of this License to the maximum extent possible; and (b)
describe the limitations and the code they affect. Such description must
be placed in a text file included with all distributions of the Covered
Software under this License. Except to the extent prohibited by statute
or regulation, such description must be sufficiently detailed for a
recipient of ordinary skill to be able to understand it.

5. Termination
--------------

5.1. The rights granted under this License will terminate automatically
if You fail to comply with any of its terms. However, if You become
compliant, then the rights granted under this License from a particular
Contributor are reinstated (a) provisionally, unless and until such
Contributor explicitly and finally terminates Your grants, and (b) on an
ongoing basis, if such Contributor fails to notify You of the
non-compliance by some reasonable means prior to 60 days after You have
come back into compliance. Moreover, Your grants from a particular
Contributor are reinstated on an ongoing basis if such Contributor
notifies You of the non-compliance by some reasonable means, this is the
first time You have received notice of non-compliance with this License
from such Contributor, and You become compliant prior to 30 days after
Your receipt of the notice.

5.2. If You initiate litigation against any entity by asserting a patent
infringement claim (excluding declaratory judgment actions,
counter-claims, and cross-claims) alleging that a Contributor Version
directly or indirectly infringes any patent, then the rights granted to
You by any and all Contributors for the Covered Software under Section
2.1 of this License shall terminate.

5.3. In the event of termination under Sections 5.1 or 5.2 above, all
end user license agreements (excluding distributors and resellers) which
have been validly granted by You or Your distributors under this License
prior to termination shall survive termination.

************************************************************************
*                                                                      *
*  6. Disclaimer of Warranty                                           *
*  -------------------------                                           *
*                                                                      *
*  Covered Software is provided under this License on an "as is"       *
*  basis, without warranty of any kind, either expressed, implied, or  *
*  statutory, including, without limitation, warranties that the       *
*  Covered Software is free of defects, merchantable, fit for a        *
*  particular purpose or non-infringing. The entire risk as to the     *
*  quality and performance of the Covered Software is with You.        *
*  Should any Covered Software prove defective in any respect, You     *
*  (not any Contributor) assume the cost of any necessary servicing,   *
*  repair, or correction. This disclaimer of warranty constitutes an   *
*  essential part of this License. No use of any Covered Software is   *
*  authorized under this License except under this disclaimer.         *
*                                                                      *
************************************************************************

************************************************************************
*                                                                      *
*  7. Limitation of Liability                                          *
*  --------------------------                                          *
*                                                                      *
*  Under no circumstances and under no legal theory, whether tort      *
*  (including negligence), contract, or otherwise, shall any           *
*  Contributor, or anyone who distributes Covered Software as          *
*  permitted above, be liable to You for any direct, indirect,         *
*  special, incidental, or consequential damages of any character      *
*  including, without limitation, damages for lost profits, loss of    *
*  goodwill, work stoppage, computer failure or malfunction, or any    *
*  and all other commercial damages or losses, even if such party      *
*  shall have been informed of the possibility of such damages. This   *
*  limitation of liability shall not apply to liability for death or   *
*  personal injury resulting from such party's negligence to the       *
*  extent applicable law prohibits such limitation. Some               *
*  jurisdictions do not allow the exclusion or limitation of           *
*  incidental or consequential damages, so this exclusion and          *
*  limitation may not apply to You.                                    *
*                                                                      *
************************************************************************

8. Litigation
-------------

Any litigation relating to this License may be brought only in the
courts of a jurisdiction where the defendant maintains its principal
place of business and such litigation shall be governed by laws of that
jurisdiction, without reference to its conflict-of-law provisions.
Nothing in this Section shall prevent a party's ability to bring
cross-claims or counter-claims.

9. Miscellaneous
----------------

This License represents the complete agreement concerning the subject
matter hereof. If any provision of this License is held to be
unenforceable, such provision shall be reformed only to the extent
necessary to make it enforceable. Any law or regulation which provides
that the language of a contract shall be construed against the drafter
shall not be used to construe this License against a Contributor.

10. Versions of the License
---------------------------

10.1. New Versions

Mozilla Foundation is the license steward. Except as provided in Section
10.3, no one other than the license steward has the right to modify or
publish new versions of this License. Each version will be given a
distinguishing version number.

10.2. Effect of New Versions

You may distribute the Covered Software under the terms of the version
of the License under which You originally received the Covered Software,
or under the terms of any subsequent version published by the license
steward.

10.3. Modified Versions

If you create software not governed by this License, and you want to
create a new license for such software, you may create and use a
modified version of this License if you rename the license and remove
any references to the name of the license steward (except to note that
such modified license differs from this License).

10.4. Distributing Source Code Form that is Incompatible With Secondary
Licenses

If You choose to distribute Source Code Form that is Incompatible With
Secondary Licenses under the terms of this version of the License, the
notice described in Exhibit B of this License must be attached.

Exhibit A - Source Code Form License Notice
-------------------------------------------

  This Source Code Form is subject to the terms of the Mozilla Public
  License, v. 2.0. If a copy of the MPL was not distributed with this
  file, You can obtain one at http://mozilla.org/MPL/2.0/.

If it is not possible or desirable to put the notice in a particular
file, then You may include the notice in a location (such as a LICENSE
file in a relevant directory) where a recipient would be likely to look
for such a notice.

You may add additional accurate notices of copyright ownership.

Exhibit B - "Incompatible With Secondary Licenses" Notice
---------------------------------------------------------

  This Source Code Form is "Incompatible With Secondary Licenses", as
  defined by the Mozilla Public License, v. 2.0.
```

### The 3-Clause BSD License

<!-- Source: https://raw.githubusercontent.com/spdx/license-list-data/main/text/BSD-3-Clause.txt -->

```text
Copyright (c) <year> <owner>. 

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Unlicense

<!-- Source: bundled LICENSE of robust-predicates 3.0.3 -->

```text
This is free and unencumbered software released into the public domain.

Anyone is free to copy, modify, publish, use, compile, sell, or
distribute this software, either in source code form or as a compiled
binary, for any purpose, commercial or non-commercial, and by any
means.

In jurisdictions that recognize copyright laws, the author or authors
of this software dedicate any and all copyright interest in the
software to the public domain. We make this dedication for the benefit
of the public at large and to the detriment of our heirs and
successors. We intend this dedication to be an overt act of
relinquishment in perpetuity of all present and future rights to this
software under copyright law.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.

For more information, please refer to <http://unlicense.org>
```
