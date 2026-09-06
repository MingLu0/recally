"""Request-scoped dependencies. These only pull from the container (ADR-007)."""

from collections.abc import Iterator
from typing import Annotated

from fastapi import Depends
from sqlalchemy.orm import Session

from recally.container import Container, get_container


def container_dependency() -> Container:
    """The process-wide container.

    Wrapped in a function rather than used directly so a test can override this one
    dependency and point the whole app at an in-memory database.
    """
    return get_container()


def session_dependency(
    container: Annotated[Container, Depends(container_dependency)],
) -> Iterator[Session]:
    """A session for the lifetime of one request."""
    with container.session() as session:
        yield session


ContainerDep = Annotated[Container, Depends(container_dependency)]
SessionDep = Annotated[Session, Depends(session_dependency)]
