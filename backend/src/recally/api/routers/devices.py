"""`POST /devices` (docs/api-spec.md, "Devices").

Thin adapter over `services/devices.py`: registration is idempotent on
`fcm_token`, so the app calls this on every start and every token refresh.
"""

from fastapi import APIRouter

from recally.api.auth import ApiKeyGuard
from recally.api.deps import SessionDep
from recally.schemas.devices import DeviceRegisteredResponse, DeviceRegisterRequest
from recally.services.devices import register_device

router = APIRouter(prefix="/devices", tags=["devices"], dependencies=[ApiKeyGuard])


@router.post("", response_model=DeviceRegisteredResponse)
def register(request: DeviceRegisterRequest, session: SessionDep) -> DeviceRegisteredResponse:
    """Register for push, returning the id the client sends on ratings."""
    device = register_device(session, fcm_token=request.fcm_token, platform=request.platform)
    return DeviceRegisteredResponse(device_id=device.id)
