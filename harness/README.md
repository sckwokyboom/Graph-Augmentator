# Harness

Python orchestrator for the augmentation eval harness. Reads bench config, builds artifacts via
graph-tipper, invokes the LLM, runs tests, writes a report comparing all six arms.

## Run pilot

```bash
cd harness
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
ANTHROPIC_API_KEY=... PICOCLI_ROOT=/path/to/picocli \
  python -m harness.orchestrator --bench javabench-pa21 --arms all --samples 3
```

## Test

```bash
cd harness
python -m pytest -v
```

The pilot smoke (`tests/test_pilot_smoke.py`) is gated by `PICOCLI_ROOT` and runs only when set.
