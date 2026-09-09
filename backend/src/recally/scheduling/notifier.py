"""The one-push-per-day notifier (docs/api-spec.md, "Devices"; hard rule 8).

Step 5a wires only the seam: `send_due_push` is the single function
`scheduling/jobs.py` calls for `{"job": "notify"}`, and step 5b fills in the
policy — due-count check, the previous-`push_runs` gate, the FCM send, and the
`push_runs` / `devices.last_push_at` bookkeeping.
"""

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from recally.container import Container


def send_due_push(container: "Container") -> None:
    """Send today's push to every registered device, subject to the policy.

    A no-op until step 5b implements the policy; the seam exists so `POST
    /jobs/run {"job": "notify"}` and the APScheduler entry point have their
    call target already wired (docs/backend.md, "Wiring and entry points").
    """
