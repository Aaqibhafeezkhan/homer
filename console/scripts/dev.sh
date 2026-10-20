#!/bin/bash

# Homer Console Development Script
# Starts the development server with proper environment setup

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Homer Console - Development Server${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check if .env.local exists
if [ ! -f .env.local ]; then
    echo -e "${YELLOW}⚠️  .env.local not found. Creating from .env.example...${NC}"
    cp .env.example .env.local
    echo -e "${GREEN}✓ Created .env.local${NC}"
    echo ""
    echo -e "${YELLOW}Please edit .env.local with your Homer API URL and settings.${NC}"
    echo ""
fi

# Check if node_modules exists
if [ ! -d node_modules ]; then
    echo -e "${YELLOW}⚠️  node_modules not found. Installing dependencies...${NC}"
    npm install
    echo -e "${GREEN}✓ Dependencies installed${NC}"
    echo ""
fi

# Load environment variables
if [ -f .env.local ]; then
    export $(cat .env.local | grep -v '^#' | xargs)
fi

# Display configuration
echo -e "${BLUE}Configuration:${NC}"
echo -e "  API URL: ${CONSOLE_HOMER_API_URL:-http://localhost:8080}"
echo -e "  Auth Enabled: ${CONSOLE_AUTH_ENABLED:-false}"
echo -e "  Port: ${PORT:-3000}"
echo ""

# Start the development server
echo -e "${GREEN}Starting development server...${NC}"
echo ""

npm run dev
