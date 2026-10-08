"""Add the waveform compatibility library without migrating live identities."""
from alembic import op
from app.models.library import LibraryPattern, LibraryImport

revision = "0002_waveform_library"
down_revision = "0001_initial"
branch_labels = None
depends_on = None


def upgrade():
    LibraryPattern.__table__.create(op.get_bind(), checkfirst=True)
    LibraryImport.__table__.create(op.get_bind(), checkfirst=True)


def downgrade():
    LibraryImport.__table__.drop(op.get_bind(), checkfirst=True)
    LibraryPattern.__table__.drop(op.get_bind(), checkfirst=True)
