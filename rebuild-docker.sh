#!/bin/bash
APP_VERSION=$(git describe --tags --exact-match 2>/dev/null \
  || git describe --tags --always 2>/dev/null \
  || git rev-parse --abbrev-ref HEAD 2>/dev/null \
  || echo "dev")
export APP_VERSION

# Stop and remove containers
docker-compose -f docker-compose.yml -f docker-compose.build.yml -f docker-compose.dev.yml down

# Remove the problematic volumes
docker volume rm homer_node_modules 2>/dev/null || true
docker volume rm homer_next_cache 2>/dev/null || true

# Start fresh
docker-compose -f docker-compose.yml -f docker-compose.build.yml -f docker-compose.dev.yml up --build
