"""REST access layer (architecture doc §2): accounts, tokens, admin."""
from app.api.accounts import router as accounts_router
from app.api.admin import router as admin_router

__all__ = ["accounts_router", "admin_router"]
