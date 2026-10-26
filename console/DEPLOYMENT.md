# Deployment Guide

This guide covers different deployment scenarios for the Homer Console.

## Table of Contents

- [Local Development](#local-development)
- [Docker Standalone](#docker-standalone)
- [Docker Compose](#docker-compose)
- [Production Deployment](#production-deployment)
- [Environment Configuration](#environment-configuration)
- [Dependency Security](#dependency-security)

## Local Development

### Prerequisites

- Node.js 24 (the version the Dockerfile builds with)
- npm
- Running Homer backend

### Steps

1. Clone the repository and navigate to the console directory:
```bash
cd console
```

2. Install dependencies:
```bash
npm install
```

3. Create your environment file:
```bash
cp .env.example .env.local
```

4. Edit `.env.local` with your settings:
```env
CONSOLE_HOMER_API_URL=http://localhost:8080
CONSOLE_AUTH_ENABLED=false
```

5. Start the development server:
```bash
npm run dev
```

6. Open http://localhost:3000 in your browser

## Docker Standalone

Build and run only the frontend in a Docker container.

### Build the Image

```bash
docker build -t homer-console:latest .
```

### Run the Container

#### Without Authentication

```bash
docker run -d \
  --name homer-console \
  -p 3000:3000 \
  -e CONSOLE_HOMER_API_URL=http://your-homer-backend:8080 \
  -e CONSOLE_AUTH_ENABLED=false \
  homer-console:latest
```

#### With Authentication

```bash
docker run -d \
  --name homer-console \
  -p 3000:3000 \
  -e CONSOLE_HOMER_API_URL=http://your-homer-backend:8080 \
  -e CONSOLE_AUTH_ENABLED=true \
  homer-console:latest
```

#### Custom Port

```bash
docker run -d \
  --name homer-console \
  -p 8080:4000 \
  -e PORT=4000 \
  -e CONSOLE_HOMER_API_URL=http://your-homer-backend:8080 \
  homer-console:latest
```

## Docker Compose

Run both frontend and Homer backend together using the root-level docker-compose.yml.

### Setup

From the project root:

```bash
cp .env.example .env

# Required — the backend refuses to start without it
echo "HOMER_ADMIN_TOKEN=$(openssl rand -hex 24)" >> .env
```

The default backend config uses bearer auth, so `.env.example` turns on
console sign-in with the token option (`AUTH_ENABLED=true`,
`CONSOLE_ALLOW_BEARER=true`): sign in at `/login?mode=bearer` on the Token tab
with the `HOMER_ADMIN_TOKEN` value. The token is then kept in `localStorage`,
acceptable for this loopback-only stack. To use the console without logging in,
instead set `CONFIG_PATH=config/config-local.json` and `AUTH_ENABLED=false` in
`.env` (an unsecured backend, bound to loopback); for real logins, configure a
password scheme and leave `CONSOLE_ALLOW_BEARER` off — see
[`app/SERVER.md`](../app/SERVER.md) § *Password Authentication*.

### Run

```bash
docker compose up -d
docker compose logs -f frontend     # console logs
docker compose logs -f backend      # backend logs (also in example/workspace/app.log)
docker compose down
```

For detailed Docker documentation and deployment options, see [../DOCKER.md](../DOCKER.md).

### Customization

Edit `docker-compose.yml` in the project root to customize:
- Volume mounts
- Network configuration
- The backend image
- Environment variables

The base file has no `build:` sections — it pulls the published release images,
with the tag selected by `HOMER_VERSION`. Override `image:` to run your own:

```yaml
services:
  backend:
    image: my-registry/homer-backend:v1.0.0
```

Building from source is the `docker-compose.build.yml` overlay, which adds the
`build:` sections back (and tags what it builds locally); see
[../DOCKER.md](../DOCKER.md) § *Quick Start*.

## Production Deployment

### Build Optimization

The application uses Next.js standalone output for minimal image size:

```javascript
// next.config.js
const nextConfig = {
  output: 'standalone',
  // ...
}
```

### Multi-stage Build

The Dockerfile uses multi-stage builds to minimize the final image size:

1. **Deps stage**: Installs dependencies
2. **Builder stage**: Builds the application
3. **Runner stage**: Copies only the standalone build output, `.next/static` and `public/`

### Health Checks

Add health checks to your deployment:

```yaml
healthcheck:
  test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:3000/"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 40s
```

### Reverse Proxy

When deploying behind a reverse proxy (nginx, traefik, etc.), configure accordingly:

#### Nginx Example

Terminate TLS at the proxy. Cookie auth sets `Secure` cookies by default, so a
plain-HTTP deployment silently fails to authenticate — the browser will not send
the session cookie back.

```nginx
# Redirect plain HTTP to HTTPS.
server {
    listen 80;
    server_name homer-console.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name homer-console.example.com;

    ssl_certificate     /etc/ssl/certs/homer-console.pem;
    ssl_certificate_key /etc/ssl/private/homer-console.key;

    location / {
        proxy_pass http://localhost:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;

        # SSE endpoints stream for the life of a run; don't buffer or time them out.
        proxy_buffering off;
        proxy_read_timeout 3600s;
    }
}
```

> **Rate limiting behind a proxy:** Homer keys its login limiter on the direct
> TCP peer, deliberately not `X-Forwarded-For` (which a client can spoof). Behind
> a proxy that peer is the proxy, so **all clients share one bucket**. Raise
> `server.loginRateLimit` and `server.perCallerRateLimit` accordingly, or apply
> per-IP limiting at the proxy where the real client address is known.

#### Traefik Example

```yaml
labels:
  - "traefik.enable=true"
  - "traefik.http.routers.homer-console.rule=Host(`homer-console.example.com`)"
  - "traefik.http.services.homer-console.loadbalancer.server.port=3000"
```

## Environment Configuration

### Optional Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `CONSOLE_HOMER_API_URL` | Homer backend API URL | `api.baseUrl` from `config/default.json` (`http://localhost:8080`) |
| `CONSOLE_AUTH_ENABLED` | Enable authentication | `false` |
| `CONSOLE_ALLOW_BEARER` | Offer bearer (token) login. Off = cookie-only (recommended; bearer tokens live in `localStorage` and are XSS-exfiltratable). Set `true` only for a bearer-only backend; it also makes **Token** the mode the login form opens in, since such a backend cannot accept a password. | `false` |
| `CONSOLE_ALLOWED_API_ORIGINS` | Comma-separated backend origins the console may connect to. When set, the CSP `connect-src` is pinned to `'self'` + these (a genuinely strict CSP), and the login endpoint field is restricted to them. When unset, `connect-src` stays open so any backend can be entered at login. | *(unset)* |
| `CONSOLE_PLUGINS` | Comma-separated plugin entries: a same-origin module path under `public/`, optionally `@<requirement>` (`/plugins/costs.js@admin`, where the requirement is `admin` or a Homer Action). Remote URLs are rejected; a requirement is enforced before the module is fetched. **Each entry runs with the operator's privileges in the console's origin** — treat it like source you merged, and keep `public/plugins/` writable only by the operator. See the console README's Plugins section and [ADR-0059](../docs/adr/0059-console-plugins-are-runtime-loaded-modules.md). | *(unset)* |
| `CONSOLE_THEMES` | Comma-separated theme entries as `name:path` (`brand-dark:/themes/brand-dark.css`). The name appears in the theme dropdown; the path must be a root-relative `.css` file under `public/`, served same-origin. Remote URLs, protocol-relative paths, `data:`/`blob:` URLs, `..`, non-`.css` paths, names outside `[a-z0-9-]+` and names colliding with a built-in are rejected and logged; the rest still load, and a missing file applies no tokens, so every token falls back to the `:root` (light) values. **A theme restyles the console for every user of it** — treat it like a stylesheet you approved, and keep `public/themes/` writable only by the operator. See the console README's Custom themes section and [ADR-0069](../docs/adr/0069-console-themes-are-runtime-loaded-stylesheets.md). | *(unset)* |
| `CONSOLE_THEME_MODES` | Names one `CONSOLE_THEMES` entry as the light theme and one as the dark theme, as `light:<name>,dark:<name>` (`light:corp-light,dark:corp-dark`). When set and valid, the theme dropdown and `next-themes` offer **only** the configured entries plus `System` — the built-ins `light`/`dark`/`terminal`/`win95` are hidden, `System` is the default and follows the OS colour scheme onto the named pair, and a stored built-in falls back to `System`. Both names must be `CONSOLE_THEMES` entries; a missing or unknown name, a built-in name, a duplicate or an extra segment discards the setting as a whole (built-ins stay) and logs the reason once. See the console README's Custom themes section and [ADR-0069](../docs/adr/0069-console-themes-are-runtime-loaded-stylesheets.md). | *(unset)* |
| `PORT` | Frontend server port | `3000` |
| `HOSTNAME` | Server hostname | `0.0.0.0` |
| `NODE_ENV` | Node environment | `production` |

### Configuration Precedence

1. Environment variables (highest priority)
2. `.env.local` file
3. `config/default.json` (lowest priority)

### Security Considerations

#### Authentication

- Always use HTTPS in production when authentication is enabled
- Store tokens securely (use environment variables, not hardcoded)
- Rotate tokens regularly
- Consider using a secrets management system (Vault, AWS Secrets Manager, etc.)

#### CORS

If the Homer backend and console are on different origins, pin the origin on
the **backend** (Kotlin/Ktor — `server.corsAllowedOrigins` in the Homer config,
not an Express middleware):

```yaml
server:
  # Origin pin lives on the server block.
  corsAllowedOrigins:
    - "https://homer-console.example.com"

  # Cookie settings live on the password auth scheme, not on `server` directly.
  apiAuth: !<password>
    users:
      admin:
        passwordHash: "@SYSENV:ADMIN_PASSWORD_HASH"
        role: admin
    sessionSecret: "@SYSENV:HOMER_SESSION_SECRET"
    cookieSameSite: "none"   # required for cross-origin cookie auth
    cookieSecure: true       # enforced when cookieSameSite is "none"
```

An empty `corsAllowedOrigins` allows any origin **without** credential
reflection, and is a startup error under password auth. Under
`cookieSameSite: none` the origin pin becomes the primary cross-origin control
rather than defence-in-depth, so pin it exactly. See
[`app/SERVER.md`](../app/SERVER.md) § *Password Authentication* and
[`README.md`](README.md) § *Multiple instances*.

#### CSP Headers

**Do not add a CSP header at the proxy.** The console already emits a
per-request nonce-based policy with `'strict-dynamic'` from `middleware.ts`,
and deliberately relaxes it to `'unsafe-eval'`/`'unsafe-inline'` on exactly one
route (`/agents/<name>/edit`, which hosts the Monaco editor). A blanket proxy
header would apply that relaxation site-wide and override the stricter policy
everywhere else — strictly worse than the built-in behaviour.

Note the built-in CSP is only emitted in production builds. If you need
additional headers at the proxy, add ones the app does not set (for example
`Strict-Transport-Security` once you are HTTPS-only) rather than
`Content-Security-Policy`.

### Monitoring

#### Logging

The application logs to stdout/stderr. Collect logs using:

```bash
# Docker logs
docker logs -f homer-console

# Docker Compose logs
docker compose logs -f frontend
```

#### Metrics

Monitor key metrics:
- Response times
- Error rates
- Memory usage
- CPU usage

Use tools like:
- Prometheus + Grafana
- DataDog
- New Relic

## Troubleshooting

### Cannot connect to Homer backend

**Problem**: Frontend shows "Failed to load processes"

**Solutions**:
1. Verify `CONSOLE_HOMER_API_URL` is correct and accessible
2. Check network connectivity between containers
3. Ensure Homer backend is running and healthy
4. Check CORS configuration on backend
5. Review browser console and network tab for errors

### Authentication failures

**Problem**: Login fails or the console keeps returning to the login page

**Solutions**:
1. Ensure `CONSOLE_AUTH_ENABLED=true` and that the backend has a password
   scheme (`apiAuth: !<password>` or `!<combined>`) listing the user
2. Serve the console over HTTPS — cookies are `Secure` by default, so a
   plain-HTTP deployment never sends the session back
3. Check that `server.corsAllowedOrigins` lists the console origin
4. Bearer login (`CONSOLE_ALLOW_BEARER=true` only): test the token against
   `/api/v1/heartbeat`, then clear browser localStorage and try again

### Port conflicts

**Problem**: "Port 3000 is already in use"

**Solutions**:
1. Change the `PORT` environment variable
2. Update port mappings in Docker run command
3. Stop other services using the port

### Build failures

**Problem**: Docker build fails

**Solutions**:
1. Ensure you have enough disk space
2. Clear Docker cache: `docker system prune -a`
3. Check Dockerfile for syntax errors
4. Verify all dependencies are available

### Memory issues

**Problem**: Container runs out of memory

**Solutions**:
1. Increase container memory limit
2. Reduce `pagination.defaultLimit` in `config/default.json` (compiled in; rebuild the image)

## Scaling

### Horizontal Scaling

The frontend is stateless and can be scaled horizontally. Remove the root
compose file's `container_name: homer-console` first — a fixed container name
allows only one replica:

```yaml
services:
  frontend:
    # ...
    deploy:
      replicas: 3
```

Use a load balancer (nginx, HAProxy, etc.) to distribute traffic.

### Vertical Scaling

Adjust resource limits:

```yaml
services:
  frontend:
    # ...
    deploy:
      resources:
        limits:
          cpus: '1'
          memory: 1G
        reservations:
          cpus: '0.5'
          memory: 512M
```

## Updates

### Rolling Updates

Rebuild and recreate the console container:

```bash
docker compose up -d --build frontend
```

Compose stops the old container before starting the new one, so expect a
short outage; for zero downtime, see below.

### Zero-Downtime Deployments

Use multiple replicas and a load balancer:

1. Deploy new version to a subset of replicas
2. Verify health
3. Gradually shift traffic
4. Complete rollout

## Backup and Recovery

### Configuration Backup

Backup important files:
- `.env` or `.env.local`
- Custom configurations (`config/default.json` is compiled into the image)

### Disaster Recovery

1. Keep Docker images in a registry
2. Version control all configuration files
3. Document custom modifications
4. Test restore procedures regularly

## Dependency Security

Two `overrides` entries pin patched copies of transitive dependencies:

- **`dompurify` (via `monaco-editor`)** — `overrides.dompurify` pins the
  transitive copy Monaco bundles to `^3.4.16` (fixing the XSS / mXSS /
  prototype-pollution advisories affecting `<= 3.4.10` and the DOM XSS
  affecting `3.4.13`–`3.4.15`). This
  avoids the npm-suggested "fix", which downgrades `monaco-editor` to `0.53.0`.
  Drop the override once `monaco-editor` ships a release depending on a patched
  `dompurify`.
- **`postcss`** — `overrides.postcss` pins the transitive copy bundled by
  Next.js to the patched `8.5.x`, since the audit's automatic fix would
  otherwise downgrade Next.js itself.

### Licensing note: `lightningcss` (MPL-2.0)

`lightningcss` is the one non-permissively-licensed package in the tree
(MPL-2.0 — a weak, file-level copyleft; not proprietary). It is not a
direct dependency: Tailwind CSS v4 uses it as its CSS engine
(`@tailwindcss/postcss` → `@tailwindcss/node`), and Vite declares it for
an optional CSS transformer the console never enables. Both paths are
devDependencies, so it runs only at build/test time and is **never
distributed**: the deployed image ships Next's `output: 'standalone'`
trace, which does not contain it. MPL-2.0 obligations attach to
distributing modified MPL-covered source files; an unmodified build-time
tool imposes none on the console's Apache-2.0 code or on the CSS it
emits. Kept deliberately (2026-08-18) — the only way to remove it would
be downgrading to Tailwind v3, which is not worth it for a build-only
dependency. License scanners flagging it should allowlist it as
build-time tooling.

## Support

For issues and questions:
- Check the [README.md](README.md) for general usage
- Review logs for error messages
- Open an issue in the Homer repository
