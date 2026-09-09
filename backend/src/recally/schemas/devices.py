"""`POST /devices` payloads (docs/api-spec.md, "Devices")."""

from pydantic import BaseModel, Field


class DeviceRegisterRequest(BaseModel):
    """Push registration. The app calls this on every start and every token
    refresh, so it is idempotent on `fcm_token` (UNIQUE in `devices`)."""

    fcm_token: str = Field(min_length=1)
    # Only "android" exists in v1 (docs/PRD.md); kept a plain string so a future
    # platform is a client change, not a schema change.
    platform: str = Field(min_length=1)


class DeviceRegisteredResponse(BaseModel):
    """The id the client stores and sends on ratings (`review_logs.device_id`)."""

    device_id: int
