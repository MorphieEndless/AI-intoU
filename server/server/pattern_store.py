"""Legacy import facade; the live library now lives in the application database."""
from app.domain.patterns import PatternStore as _PatternStore
from app.schemas.patterns import StoredPattern, PatternStep, MIN_STEP_MS, MAX_STEPS, MAX_REPEAT, MAX_TOTAL_MS
from . import config


class PatternStore(_PatternStore):
    def __init__(self, root: str, db_path: str | None = None):
        super().__init__(root, db_path or config.DB_PATH)


pattern_store = PatternStore(config.PATTERNS_DIR)
