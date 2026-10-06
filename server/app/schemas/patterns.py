from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, StrictBool, model_validator

MIN_STEP_MS = 100
MAX_STEPS = 128
MAX_REPEAT = 60
MAX_TOTAL_MS = 600_000
BUILTIN_ID = "builtin-wave"
BUILTIN_NAME = "示例波形-波浪"


class PatternStep(BaseModel):
    duration_ms: int = Field(ge=MIN_STEP_MS, le=MAX_TOTAL_MS)
    vibrate: float = Field(0., ge=0., le=1., allow_inf_nan=False)
    constrict: float = Field(0., ge=0., le=1., allow_inf_nan=False)
    constrict_mode: int | None = Field(None, ge=1, le=8)


class StoredPattern(BaseModel):
    id: str
    name: str
    description: str = ""
    device: str = "yingti"
    repeat: int = Field(1, ge=1, le=MAX_REPEAT)
    intensity_scale: float = Field(1., ge=0., le=1., allow_inf_nan=False)
    steps: list[PatternStep] = Field(min_length=1, max_length=MAX_STEPS)
    created_at: float = 0.
    updated_at: float = 0.
    is_liked: bool = False
    is_favorite: bool = False
    builtin: bool = False

    @model_validator(mode="after")
    def duration_cap(self):
        if self.total_ms() > MAX_TOTAL_MS:
            raise ValueError("pattern may run for at most 10 minutes")
        return self

    def total_ms(self):
        return sum(s.duration_ms for s in self.steps) * self.repeat

    def peak_intensity(self):
        return max([0.] + [s.vibrate for s in self.steps] + [s.constrict for s in self.steps])


class MetadataPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    is_liked: StrictBool | None = None
    is_favorite: StrictBool | None = None
    description: str | None = Field(None, max_length=120)

    @model_validator(mode="after")
    def explicit_non_null(self):
        if not self.model_fields_set or any(getattr(self, k) is None for k in self.model_fields_set):
            raise ValueError("Supply at least one non-null metadata field")
        return self


class PatternSummary(BaseModel):
    id: str
    name: str
    description: str
    device: str
    repeat: int
    intensity_scale: float
    created_at: float
    updated_at: float
    is_liked: bool
    is_favorite: bool
    builtin: bool
    step_count: int
    total_ms: int
    steps: list[PatternStep] | None = None


class PatternPage(BaseModel):
    patterns: list[PatternSummary]
    total: int


class PreferenceEntry(BaseModel):
    id: str
    name: str
    description: str
    is_liked: bool
    is_favorite: bool
    builtin: bool
    total_ms: int


class PreferenceView(BaseModel):
    patterns: list[PreferenceEntry]
    meaning: str = "is_liked is user feedback for the AI; is_favorite is the user's own collection. Read-only."


class DeleteResult(BaseModel):
    deleted: str
    undo_seconds: int = 6
