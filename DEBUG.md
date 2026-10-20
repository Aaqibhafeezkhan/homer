# Homer Debug Guide

`agentdebug` validates and runs Homer agents locally, without the server.

```bash
# Build the debugger (from the repo root; needs a JDK 25)
JAVA_HOME=<path to a JDK 25> app/gradlew -p app :agentdebug:shadowJar

./app/agentdebug.sh --config <host-path config> list
```

The bundled `example/config/config.json` uses container paths, so a local run
needs a config pointing at host paths. Setup, the full CLI reference, output
formats and the env merge order are in
[`app/agentdebug/README.md`](app/agentdebug/README.md).
