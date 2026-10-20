# Runs that hold no credentials

Homer's maintainers develop Homer with Homer: a set of agents picks up
tickets, writes the change, opens a merge request, reviews other merge
requests and answers review threads. Every one of those runs reads text that
someone else wrote — a ticket, a merge request description, a review comment,
a file in the repository. Any of it can carry a prompt injection.

This case study describes how those runs work against the code host, the
issue tracker, the security scanner, chat and the LLM without holding a single
token.

## The problem

A run sees everything in its environment. The obvious setup puts the API
tokens in `globalEnv` (sourced with `@SYSENV:`), and every run can then read
them. Homer masks `@SYSENV:` values in telemetry
([`app/SERVER.md`](../../app/SERVER.md) § *Langfuse Configuration*), but
masking only covers what Homer records. A run steered by hostile text can still
print a token into a merge request comment, commit it, or send it anywhere the
network allows. Homer does not stop that: it is not a sandbox, and on any
runner a run has the full reach of the user it runs as
([README](../../README.md) § *Security model*).

So the goal was: **a run that is fully taken over still has no token to
leak.**

## The setup

```
runner pod
├─ homer-runner container ── the run: claude, the forge CLI, git, curl
│     │  plain HTTP to 127.0.0.1:150xx, placeholder tokens only
└─ envoy sidecar ─────────── credentials from a Secret only it mounts
      │  adds the credential, opens TLS
      ▼  one fixed upstream per port
   code host · issue tracker · scanner · chat · LLM gateway
```

- **Where runs execute.** The agents run on `homer-runner` instances
  ([pull mode](../../app/SERVER.md#external-runners-pull-mode)) in Kubernetes
  pods. Homer's server only orchestrates. The pods sit in a namespace with
  no route to the internal network: what they can reach on the internet is
  decided by the proxy and the cluster's egress, not by the run.
- **One proxy per pod.** Each runner pod has an [Envoy](https://www.envoyproxy.io/)
  sidecar with one listener per upstream and credential, bound to
  `127.0.0.1`, which is the pod's own network namespace. Each listener
  forwards to one fixed host over TLS and adds the credential header with
  Envoy's `credential_injector` filter. With `overwrite: true`, a header the
  run sends is replaced, never forwarded.
- **Credentials only in the proxy.** The tokens are in a Kubernetes Secret
  mounted into the Envoy container alone. The runner container never sees
  them, and the pod mounts no ServiceAccount token
  (`automountServiceAccountToken: false`), so a run cannot ask the Kubernetes
  API for the Secret either.
- **The run's environment holds endpoints, not tokens.** Clients that refuse
  to start without a token get a placeholder, which the proxy replaces.

```yaml
# Homer config (excerpt)
globalEnv:
  FORGE_HOST: git.example.com        # clone URLs and links stay the real host
  FORGE_API_HOST: 127.0.0.1:15001    # the forge CLI's API calls go to the proxy
  FORGE_API_PROTOCOL: http
  FORGE_TOKEN: via-envoy             # the CLI needs a value; the proxy replaces it
  ANTHROPIC_BASE_URL: http://127.0.0.1:15008
  ANTHROPIC_AUTH_TOKEN: via-envoy
  TRACKER_API_URL: http://127.0.0.1:15003

runners:
  workers: !<external>
    token: "@SYSENV:HOMER_RUNNER_TOKEN"
    homerApiUrl: https://homer.example.com
```

`git` is pointed at the proxy with `url.<proxy>.insteadOf`, so repositories
still name `https://git.example.com/...`.

One listener of the proxy, trimmed:

```yaml
- name: forge_api
  address:
    socket_address: { address: 127.0.0.1, port_value: 15001 }
  filter_chains:
    - filters:
        - name: envoy.filters.network.http_connection_manager
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
            stat_prefix: forge_api
            normalize_path: true          # decode %70-style escapes before the deny rules match
            merge_slashes: true
            path_with_escaped_slashes_action: KEEP_UNCHANGED   # the forge's project ids use %2F
            route_config:
              virtual_hosts:
                - name: forge_api
                  domains: ["*"]
                  routes:
                    - match: { prefix: /api/v4/personal_access_tokens }
                      direct_response: { status: 403 }
                    - match: { safe_regex: { regex: "/api/v4/(projects|groups)/[^/]+/(access|deploy)_tokens([./].*)?" } }
                      direct_response: { status: 403 }
                    # ... more deny rules ...
                    - match: { prefix: / }
                      route: { cluster: forge, host_rewrite_literal: git.example.com }
                      request_headers_to_remove: [private-token, job-token]
            http_filters:
              - name: envoy.filters.http.credential_injector
                typed_config:
                  "@type": type.googleapis.com/envoy.extensions.filters.http.credential_injector.v3.CredentialInjector
                  overwrite: true
                  credential:
                    name: envoy.http.injected_credentials.generic
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.http.injected_credentials.generic.v3.Generic
                      header: Authorization
                      credential:
                        name: forge-api
                        sds_config:
                          path_config_source:
                            path: /etc/envoy/creds/forge-api.yaml
                            watched_directory: { path: /etc/envoy/creds }
              - name: envoy.filters.http.router
                typed_config:
                  "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
```

The `forge` cluster connects to `git.example.com:443` over TLS and checks the
server's name against the certificate. There is no dynamic forward proxy, so
a run cannot name a host of its own.

## Details that matter

- **Deny the APIs that outlive the run.** Even with a narrowly scoped token,
  a run could mint a new token, add a deploy key, a webhook, a CI variable or
  a member, or move a protected branch. All of these outlast the run and a
  token rotation. The proxy answers them with `403`. The forge CLI sends its
  placeholder as `PRIVATE-TOKEN`, which the forge would prefer over
  `Authorization`, so the proxy strips that header.
- **No admin interface.** The Envoy config has no `admin:` block, so a run
  cannot stop the proxy or dump its configuration. Kubelet probes cannot
  reach loopback, so one extra listener on the pod IP answers `GET /ready`
  and nothing else. It has no route and no credential.
- **Fail closed.** Envoy exits when a credential file is missing, and its
  startup probe holds back the runner container, so a pod without
  credentials never takes work. Envoy watches the Secret's directory, so a
  rotated token takes effect without a restart.
- **Logs without headers.** The access log has an explicit format (method,
  path, status, duration, upstream) and never prints request headers.
- **Keep it that way.** A check in the agent repository's CI rejects any
  agent definition or script that names an upstream API URL or a token
  variable directly. A new integration therefore has to go through the proxy.
- **Run Envoy hardened.** It runs as a native sidecar (an init container with
  `restartPolicy: Always`) with a read-only root filesystem and no
  capabilities. A native sidecar starts before the runner and stops after
  it, so runs that are draining keep their API access.

## What it does not solve

- **A run can still use the access while it runs.** The proxy keeps the
  token out of the run's hands, but not the authority the token carries:
  a taken-over run can comment, push or close tickets through the proxy as
  the bot. The deny rules close the worst paths. They are a denylist, not an
  allowlist. Give each bot account the narrowest scope that does the job,
  and keep human review in front of anything that merges.
- **Whoever administers the cluster can read the Secret,** or exec into the
  Envoy container.
- **Homer's own credentials are a separate layer.** The runner's registration
  token and the per-run `HMR_CALLBACK_TOKEN` stay with Homer. On Linux,
  `homer-runner` keeps its token out of the runs' reach by making its JVM
  non-dumpable ([ADR-0078](../adr/0078-runner-jvm-is-non-dumpable.md)).

## Adapting it

- Any machine whose loopback only the runs and the proxy share works the
  same way: a VM or `ssh` host with the proxy on `127.0.0.1`, or a
  `homer-runner` container started with the proxy in its network namespace
  (Docker's `network_mode: "service:…"`).
- Any proxy that can add a header and pin the upstream works too. Envoy was
  chosen for its credential injector, its file-based secrets and its deny
  rules.
- Start with one upstream, usually the LLM gateway, which every agent uses,
  and move the others one at a time, removing each token from the
  environment as you go.
