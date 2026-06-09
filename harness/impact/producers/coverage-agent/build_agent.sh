#!/usr/bin/env bash
# Build the in-JVM coverage agent: gtcov-agent.jar (fat, system loader) + gtcov-boot.jar
# (Recorder only, bootstrap loader). Compiles against the cached ByteBuddy jar; falls back
# to downloading it from Maven Central if absent.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/src"
BUILD="$HERE/build"
rm -rf "$BUILD"; mkdir -p "$BUILD/classes"

# Locate ByteBuddy (cached); else download.
BB="$(find "$HOME/.gradle/caches" -name 'byte-buddy-1.14.18.jar' 2>/dev/null | head -1 || true)"
if [ -z "$BB" ]; then
  BB="$(find "$HOME/.gradle/caches" -name 'byte-buddy-*.jar' 2>/dev/null | grep -Ev 'agent|dep' | head -1 || true)"
fi
if [ -z "$BB" ]; then
  echo "[build_agent] ByteBuddy not cached; downloading 1.14.18 from Maven Central"
  BB="$BUILD/byte-buddy-1.14.18.jar"
  curl -fsSL -o "$BB" \
    https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy/1.14.18/byte-buddy-1.14.18.jar
fi
echo "[build_agent] ByteBuddy = $BB"

# Compile all three sources against ByteBuddy. --release 11 loads fine on the Java 21 worker.
javac --release 11 -cp "$BB" -d "$BUILD/classes" "$SRC"/gtcov/*.java

# boot jar: Recorder ONLY (bootstrap-resident; unique package, no conflict).
mkdir -p "$BUILD/boot/gtcov"
cp "$BUILD/classes/gtcov/Recorder.class" "$BUILD/boot/gtcov/"
( cd "$BUILD/boot" && jar cf "$HERE/gtcov-boot.jar" gtcov/Recorder.class )

# agent jar: Agent + CovAdvice + exploded ByteBuddy, but NOT Recorder (it lives only on
# the bootstrap loader to avoid a split-package double-load — Recorder is the single shared
# class). Explode ByteBuddy without its META-INF/module-info.
mkdir -p "$BUILD/agent"
cp -r "$BUILD/classes/gtcov" "$BUILD/agent/"
rm -f "$BUILD/agent/gtcov/Recorder.class"
( cd "$BUILD/agent" && unzip -oq "$BB" -x 'META-INF/*' 'module-info.class' )
cat > "$BUILD/MANIFEST.MF" <<'MF'
Premain-Class: gtcov.Agent
Can-Retransform-Classes: true
Can-Redefine-Classes: true
MF
( cd "$BUILD/agent" && jar cfm "$HERE/gtcov-agent.jar" "$BUILD/MANIFEST.MF" . )

echo "[build_agent] wrote $HERE/gtcov-agent.jar and $HERE/gtcov-boot.jar"
