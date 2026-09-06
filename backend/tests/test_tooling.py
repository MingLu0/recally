"""Smoke test for the tooling baseline: the package is importable and installed."""

import recally


def test_package_exposes_version() -> None:
    assert recally.__version__ == "0.1.0"
