#!/usr/bin/env bash
set -euo pipefail
echo "Installing Joern via the official installer..."
curl -L "https://github.com/joernio/joern/releases/latest/download/joern-install.sh" | bash -s -- --without-docker
echo "Done. Verify: javasrc2cpg --version"
