"""Core primitives: security (hashing / token generation / resolution).

Pure building blocks with no FastAPI and no request objects — every access
layer (api / mcp / relay) is expected to call into here (architecture doc §2).
"""
