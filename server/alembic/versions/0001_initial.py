"""initial schema: users, api_tokens, devices, patterns, safety_config

Revision ID: 0001_initial
Revises:
Create Date: 2026-09-21

Implements docs/redesign/02-architecture.md §4 exactly.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.String(), primary_key=True),
        sa.Column("username", sa.String(), nullable=False, unique=True),
        sa.Column("password_hash", sa.String(), nullable=False),
        sa.Column("is_admin", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("is_active", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("created_at", sa.String(), nullable=False),
    )
    op.create_table(
        "api_tokens",
        sa.Column("id", sa.String(), primary_key=True),
        sa.Column("user_id", sa.String(), sa.ForeignKey("users.id"),
                  nullable=False, index=True),
        sa.Column("name", sa.String(), nullable=False),
        sa.Column("kind", sa.String(), nullable=False),
        sa.Column("token_hash", sa.String(), nullable=False, unique=True),
        sa.Column("prefix", sa.String(), nullable=False),
        sa.Column("scopes", sa.String(), nullable=False,
                  server_default='["control","status","config"]'),
        sa.Column("expires_at", sa.String(), nullable=True),
        sa.Column("revoked_at", sa.String(), nullable=True),
        sa.Column("last_used_at", sa.String(), nullable=True),
        sa.Column("created_at", sa.String(), nullable=False),
        sa.CheckConstraint("kind IN ('agent','phone','human')",
                           name="ck_api_tokens_kind"),
    )
    op.create_table(
        "devices",
        sa.Column("id", sa.String(), primary_key=True),
        sa.Column("user_id", sa.String(), sa.ForeignKey("users.id"),
                  nullable=False, index=True),
        sa.Column("name", sa.String(), nullable=False),
        sa.Column("platform", sa.String(), nullable=True),
        sa.Column("created_at", sa.String(), nullable=False),
        sa.Column("last_seen_at", sa.String(), nullable=True),
    )
    op.create_table(
        "patterns",
        sa.Column("id", sa.String(), primary_key=True),
        sa.Column("user_id", sa.String(), sa.ForeignKey("users.id"),
                  nullable=False, index=True),
        sa.Column("name", sa.String(), nullable=False),
        sa.Column("definition", sa.Text(), nullable=False),
        sa.Column("created_at", sa.String(), nullable=False),
        sa.Column("updated_at", sa.String(), nullable=False),
    )
    op.create_table(
        "safety_config",
        sa.Column("user_id", sa.String(), sa.ForeignKey("users.id"),
                  primary_key=True),
        sa.Column("governor_enabled", sa.Integer(), nullable=False,
                  server_default="1"),
        sa.Column("heat_rate", sa.Float(), nullable=True),
        sa.Column("cool_rate", sa.Float(), nullable=True),
        sa.Column("cooldown_threshold", sa.Float(), nullable=True),
        sa.Column("cooldown_exit", sa.Float(), nullable=True),
        sa.Column("cooldown_duration", sa.Float(), nullable=True),
        sa.Column("updated_at", sa.String(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("safety_config")
    op.drop_table("patterns")
    op.drop_table("devices")
    op.drop_table("api_tokens")
    op.drop_table("users")
