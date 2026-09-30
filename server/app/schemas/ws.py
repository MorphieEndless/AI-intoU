"""Shared relay acknowledgement contract."""
from typing import Any, Optional
from pydantic import BaseModel


class CommandAck(BaseModel):
    type: str = "command_ack"
    success: bool = True
    message: str = ""
    request_id: Optional[str] = None
    data: Optional[dict[str, Any]] = None  # sensor readings, etc.

