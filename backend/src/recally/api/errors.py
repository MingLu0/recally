"""problem+json error responses (docs/api-spec.md, "Errors").

Every error the API emits has the same body — `{"status": ..., "detail": ...}` — so
the Android client parses one shape. FastAPI's own defaults do not: `HTTPException`
renders `{"detail": ...}` with no `status`, and a validation failure renders a list of
per-field errors. Both are re-rendered by the handlers registered here.
"""

from collections.abc import Sequence
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"


class ProblemDetail(Exception):
    """An error to return to the client verbatim, as problem+json."""

    def __init__(self, *, status: int, detail: str) -> None:
        super().__init__(detail)
        self.status = status
        self.detail = detail


def problem_response(
    status: int, detail: str, headers: dict[str, str] | None = None
) -> JSONResponse:
    """The one response body shape the API returns for every error."""
    return JSONResponse(
        status_code=status,
        content={"status": status, "detail": detail},
        media_type=PROBLEM_JSON_MEDIA_TYPE,
        headers=headers,
    )


def register_error_handlers(app: FastAPI) -> None:
    """Point FastAPI's error paths at `problem_response`."""

    async def handle_problem_detail(_: Request, exc: Exception) -> JSONResponse:
        assert isinstance(exc, ProblemDetail)
        return problem_response(exc.status, exc.detail)

    async def handle_http_exception(_: Request, exc: Exception) -> JSONResponse:
        assert isinstance(exc, StarletteHTTPException)
        # Covers the responses Starlette raises before any route runs — 404 and 405 in
        # particular, which never pass through a handler of ours.
        headers = getattr(exc, "headers", None)
        return problem_response(exc.status_code, str(exc.detail), headers)

    async def handle_validation_error(_: Request, exc: Exception) -> JSONResponse:
        assert isinstance(exc, RequestValidationError)
        return problem_response(422, _summarize_validation_errors(exc.errors()))

    async def handle_unexpected_error(_: Request, __: Exception) -> JSONResponse:
        # A crash inside a route keeps the one body shape too, with a fixed detail:
        # the exception itself may carry paths or SQL the client must never see.
        return problem_response(500, "Internal server error.")

    app.add_exception_handler(ProblemDetail, handle_problem_detail)
    app.add_exception_handler(StarletteHTTPException, handle_http_exception)
    app.add_exception_handler(RequestValidationError, handle_validation_error)
    app.add_exception_handler(Exception, handle_unexpected_error)


def _summarize_validation_errors(errors: Sequence[Any]) -> str:
    """Flatten FastAPI's per-field error list into the single `detail` string.

    The location is kept — without it "field required" says nothing about which field —
    but the list collapses to one sentence so the body keeps the documented shape.
    """
    parts: list[str] = []
    for error in errors:
        location = ".".join(str(part) for part in error.get("loc", ()) if part != "body")
        message = error.get("msg", "Invalid value")
        parts.append(f"{location}: {message}" if location else message)
    return "; ".join(parts) or "Request validation failed."
