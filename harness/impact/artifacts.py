import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class MethodLocation:
    file: str
    start: int
    end: int


@dataclass(frozen=True)
class Region:
    label: str
    lines: tuple  # (start, end)
    killers: int


class Coverage:
    def __init__(self, method_to_tests: dict[str, set[str]]):
        self._m = method_to_tests

    def tests_for(self, method_fqn: str) -> set[str]:
        return set(self._m.get(method_fqn, set()))

    def all_tests(self) -> set[str]:
        out: set[str] = set()
        for ts in self._m.values():
            out |= ts
        return out


class Mutation:
    def __init__(self, data: dict):
        self._d = data

    def killers(self, method_fqn: str) -> set[str]:
        return set(self._d.get(method_fqn, {}).get("killers", []))

    def regions(self, method_fqn: str) -> list[Region]:
        out = []
        for r in self._d.get(method_fqn, {}).get("regions", []):
            out.append(Region(r["label"], tuple(r["lines"]), int(r["killers"])))
        return out


class MethodIndex:
    def __init__(self, data: dict):
        self._d = data

    def location(self, method_fqn: str) -> "MethodLocation | None":
        v = self._d.get(method_fqn)
        if v is None:
            return None
        return MethodLocation(v["file"], int(v["start"]), int(v["end"]))

    def all(self) -> dict[str, MethodLocation]:
        return {k: MethodLocation(v["file"], int(v["start"]), int(v["end"]))
                for k, v in self._d.items()}


def load_coverage(path: Path) -> Coverage:
    raw = json.loads(Path(path).read_text())
    return Coverage({k: set(v) for k, v in raw.items()})


def load_mutation(path: Path) -> Mutation:
    return Mutation(json.loads(Path(path).read_text()))


def load_methods(path: Path) -> MethodIndex:
    return MethodIndex(json.loads(Path(path).read_text()))
