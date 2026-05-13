# Graph-Tipper

CLI that produces a Markdown context-augmentation artifact for a Java target
method using a Code Property Graph produced by Joern.

## Build

```
./gradlew installDist
```

## Run

```
./build/install/graph-tipper/bin/graph-tipper \
  --project /path/to/picocli \
  --target 'src/main/java/picocli/CommandLine.java#TextTable.putValue(int,int,Text)' \
  --out /tmp/gt-out
```

Joern must be on PATH (`javasrc2cpg`, `joern-export`). Install with
`tools/install-joern.sh`.

## Smoke test against picocli

```
GRAPHTIPPER_PICOCLI_HOME=/abs/path/to/picocli ./gradlew test --tests com.graphtipper.PicocliSmokeTest
```
