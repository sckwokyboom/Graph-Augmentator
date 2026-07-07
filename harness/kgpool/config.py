"""Config for the kgpool handles. STRICT leak policy is structural: nothing in this
module or its consumers may read reference-implementation runs; reference_file (if
set) is used ONLY by leak_sweep, eval-side."""
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


@dataclass
class KgPoolConfig:
    target_fqn: str
    target_signature: str
    stub_body: str
    project: Path
    package: str
    export_json: Path
    pool: Path
    includes: str
    source_file: str            # target's source file, relative to project
    bytecode_classes: list
    type_decls: dict            # snippet name -> class decl search string
    ladder: list                # [{"name": ..., "tests": [gradle --tests filters]}]
    reference_file: Optional[Path] = None   # eval-side only (leak_sweep)
    vcap: int = 6                            # per-(method,test) return samples — was 2, too thin
    vexc: int = 4                            # per-(method,test) exception samples (target/consumer throw)

    @property
    def pool_raw(self):
        return self.pool / "_raw"

    @property
    def pool_tools(self):
        return self.pool / "_tools"

    @property
    def pool_iters(self):
        return self.pool / "_iterations"

    def provenance(self, file, cmd, note):
        with open(self.pool_tools / "provenance.jsonl", "a") as f:
            f.write(json.dumps({"file": file, "cmd": cmd, "note": note},
                               ensure_ascii=False) + "\n")


def load_config(path) -> KgPoolConfig:
    d = json.loads(Path(path).read_text())
    for k in ("project", "export_json", "pool"):
        d[k] = Path(d[k]).expanduser()
    if d.get("reference_file"):
        d["reference_file"] = Path(d["reference_file"]).expanduser()
    return KgPoolConfig(**d)
