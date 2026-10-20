# JSON schemas

Homer publishes JSON schemas for its two configuration files. Editors that speak the YAML
language server (VS Code with the Red Hat YAML extension, JetBrains IDEs, Neovim) use them for
completion and validation.

| File | Schema |
| --- | --- |
| An agent's `agent.yaml` | [`homer-agent.schema.json`](./schemas/homer-agent.schema.json){target="_self"} |
| The server's `config.yaml` | [`homer-config.schema.json`](./schemas/homer-config.schema.json){target="_self"} |

Each documentation version serves the schemas of that release, so pin the version your server
runs. Add a directive as the first line of the file:

```yaml
# yaml-language-server: $schema=https://livesport.github.io/homer/schemas/latest/homer-agent.schema.json
```

`latest` follows the newest release; replace it with a version such as `1.34.0` to pin one. The
[agent reference](./agents/reference.md) describes every field.
