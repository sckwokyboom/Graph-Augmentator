"""Parse Java stack traces (raw text or gradle test-result XML) into cause chains.

gradle/JUnit failures wrap the real exception (`Caused by:` chains, runner
frames, InvocationTargetException). Root-cause selection: the DEEPEST cause
whose frames include a project frame (class under the project package).
"""
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

_FRAME_RE = re.compile(r"^\s*at\s+([\w.$]+)\.([\w$<>]+)\(([^:)]+):?(\d+)?\)")
_HEADER_RE = re.compile(r"^(?:Caused by:\s+)?([\w.$]+?)(?::\s?(.*))?$")


@dataclass
class Frame:
    cls: str
    method: str
    file: str
    line: int


@dataclass
class Cause:
    exc_type: str
    message: str
    frames: list


def parse_trace(text):
    """Trace text -> [Cause] in order of appearance (root cause LAST)."""
    causes, cur = [], None
    for raw in text.splitlines():
        m = _FRAME_RE.match(raw)
        if m and cur is not None:
            cls, meth, fname, line = m.groups()
            cur.frames.append(Frame(cls, meth, fname, int(line) if line else -1))
            continue
        s = raw.strip()
        if not s or s.startswith("..."):
            continue
        if cur is None or s.startswith("Caused by:"):
            h = _HEADER_RE.match(s)
            if h:
                cur = Cause(h.group(1), h.group(2) or "", [])
                causes.append(cur)
    return [c for c in causes if c.frames]


def pick_root_cause(causes, package):
    """Deepest cause whose frames include a project frame; None otherwise."""
    for c in reversed(causes):
        if any(f.cls.startswith(package) for f in c.frames):
            return c
    return None


def failures_from_xml(path):
    """gradle TEST-*.xml -> [(test_id, trace_text)] for failed/errored testcases."""
    out = []
    root = ET.parse(path).getroot()
    for tc in root.iter("testcase"):
        for f in list(tc.findall("failure")) + list(tc.findall("error")):
            out.append((f'{tc.get("classname")}.{tc.get("name")}', f.text or ""))
    return out


def testcases_from_xml(path):
    """gradle TEST-*.xml -> [(classname, name, passed, trace_text)] for ALL testcases.

    One row per testcase (first failure/error text wins); skipped cases omitted.
    Green rows carry "". The pass/fail split feeds the assertion slicer's
    leakage-safe contrast set (greens from the agent's own run)."""
    out = []
    root = ET.parse(path).getroot()
    for tc in root.iter("testcase"):
        if tc.find("skipped") is not None:
            continue
        bad = list(tc.findall("failure")) + list(tc.findall("error"))
        if bad:
            out.append((tc.get("classname"), tc.get("name"), False, bad[0].text or ""))
        else:
            out.append((tc.get("classname"), tc.get("name"), True, ""))
    return out
