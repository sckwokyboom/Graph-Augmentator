from dataclasses import dataclass, field
from harness.impact.artifacts import Coverage, Mutation

WEAK_MAX = 3  # killers <= this (and > 0) = weak


@dataclass(frozen=True)
class RegionStrength:
    method: str
    label: str
    killers: int
    strength: str  # "UNVERIFIED" | "weak" | "strong"


@dataclass(frozen=True)
class BlindSpot:
    label: str
    detail: str


@dataclass
class ImpactResult:
    changed_methods: set
    affected: set
    tier1: set
    tier2: set
    regions: list = field(default_factory=list)
    blind_spots: list = field(default_factory=list)


def _strength(killers: int) -> str:
    if killers == 0:
        return "UNVERIFIED"
    if killers <= WEAK_MAX:
        return "weak"
    return "strong"


def compute_impact(changed: set, cov: Coverage, mut: Mutation) -> ImpactResult:
    affected: set = set()
    killers: set = set()
    regions: list = []
    blind: list = []

    for m in sorted(changed):
        m_tests = cov.tests_for(m)
        affected |= m_tests
        killers |= mut.killers(m)
        if not m_tests:
            blind.append(BlindSpot(f"{m} (no covering tests)",
                                   "no test executes this method — changes here are unverifiable"))
        for reg in mut.regions(m):
            s = _strength(reg.killers)
            regions.append(RegionStrength(m, reg.label, reg.killers, s))
            if s == "UNVERIFIED":
                blind.append(BlindSpot(reg.label,
                                       f"{m}: region '{reg.label}' killed by 0 mutants — green suite is not evidence"))

    tier1 = affected & killers
    tier2 = affected - tier1
    return ImpactResult(changed, affected, tier1, tier2, regions, blind)
