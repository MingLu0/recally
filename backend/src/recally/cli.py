"""The `recally` CLI — the validation-checkpoint client (docs/workflow.md,
"Validation checkpoint"; roadmap step 3).

One of the entry points in docs/backend.md, "Wiring and entry points": it calls
container services directly — never HTTP, never a DAO — so it works with no server
running, which is the point while the checkpoint uses the backend before the app
exists. Nothing here imports a web framework or an HTTP client (docs/backend.md,
"Layering", rule 1); `test_design_invariants.py` and `test_cli.py` probe that.

`approve`/`reject` are the second human-decision entry point of the hard-rule-1
gate; the guarded `cards.status` writes live in `services/cards.py` so the API
route and this CLI share one implementation. `rate` sends a client `rated_at` and
no `device_id` (docs/api-spec.md, `POST /reviews/{card_id}/rate`): the timestamp
feeds `review_datetime` (hard rule 5), and the null device keeps CLI ratings
unattributable to a phone.

Errors surface as a one-line `error <status>: <detail>` on stderr and exit code 1,
mirroring the API's problem+json pair — never a traceback for an expected failure.
`argparse` is the standard library; nine subcommands do not need a dependency.
"""

import argparse
import sys
from collections.abc import Callable, Sequence

from sqlalchemy.orm import Session

from recally.container import Container, get_container
from recally.models import Card
from recally.models.base import utc_now
from recally.services.cards import (
    QUEUE_STATUSES,
    bury,
    list_pending_cards,
    record_approval,
    record_rejection,
    suspend,
    unsuspend,
)
from recally.services.reviews import RatingCommand, ReviewError, list_due, rate_card

EXIT_ERROR = 1


class CliError(Exception):
    """An expected command failure, carrying the same status/detail pair the API
    emits as problem+json."""

    def __init__(self, *, status: int, detail: str) -> None:
        super().__init__(detail)
        self.status = status
        self.detail = detail


def build_parser() -> argparse.ArgumentParser:
    """The nine roadmap commands: pending, approve, reject, due, rate, edit, bury,
    suspend, unsuspend."""
    parser = argparse.ArgumentParser(
        prog="recally",
        description="Use the Recally backend directly: approval queue, due cards, ratings.",
    )
    subcommands = parser.add_subparsers(dest="command", required=True)

    pending = subcommands.add_parser("pending", help="List the approval queue.")
    pending.set_defaults(handler=_cmd_pending)

    approve = subcommands.add_parser("approve", help="Approve a card (enters FSRS).")
    approve.add_argument("card_id", type=int)
    approve.set_defaults(handler=_cmd_approve)

    reject = subcommands.add_parser("reject", help="Reject a card with a reason.")
    reject.add_argument("card_id", type=int)
    reject.add_argument("--reason", required=True, help="Why the card is rejected.")
    reject.set_defaults(handler=_cmd_reject)

    due = subcommands.add_parser("due", help="List today's due cards, with state and step.")
    due.set_defaults(handler=_cmd_due)

    rate = subcommands.add_parser("rate", help="Rate a card: 1=Again, 2=Hard, 3=Good, 4=Easy.")
    rate.add_argument("card_id", type=int)
    rate.add_argument("rating", type=int, help="1=Again, 2=Hard, 3=Good, 4=Easy.")
    rate.add_argument(
        "--response-ms",
        type=int,
        default=0,
        help="Flip-to-rate duration in milliseconds (default 0: the CLI has no flip).",
    )
    rate.set_defaults(handler=_cmd_rate)

    edit = subcommands.add_parser("edit", help="Edit an approved card's front/back.")
    edit.add_argument("card_id", type=int)
    edit.add_argument("--front")
    edit.add_argument("--back")
    edit.set_defaults(handler=_cmd_edit)

    bury_parser = subcommands.add_parser("bury", help="Hide a card until tomorrow.")
    bury_parser.add_argument("card_id", type=int)
    bury_parser.set_defaults(handler=_cmd_bury)

    suspend_parser = subcommands.add_parser(
        "suspend", help="Take a card out of rotation indefinitely."
    )
    suspend_parser.add_argument("card_id", type=int)
    suspend_parser.set_defaults(handler=_cmd_suspend)

    unsuspend_parser = subcommands.add_parser("unsuspend", help="Put a card back in rotation.")
    unsuspend_parser.add_argument("card_id", type=int)
    unsuspend_parser.set_defaults(handler=_cmd_unsuspend)

    return parser


def main(argv: Sequence[str] | None = None, *, container: Container | None = None) -> int:
    """Parse `argv` and run the command against `container` (default: the process-wide
    one). Returns the exit code; expected failures print one line to stderr."""
    args = build_parser().parse_args(argv)
    container = container or get_container()
    handler: Callable[[argparse.Namespace, Container], int] = args.handler
    try:
        return handler(args, container)
    except (CliError, ReviewError) as error:
        print(f"error {error.status}: {error.detail}", file=sys.stderr)
        return EXIT_ERROR


def entrypoint() -> None:
    """The console-script entry point (`recally = recally.cli:entrypoint`)."""
    sys.exit(main())


def _cmd_pending(args: argparse.Namespace, container: Container) -> int:
    """The approval queue, grouped by book and chapter for reading."""
    with container.session() as session:
        pending = list_pending_cards(session)
    current_book: str | None = None
    current_chapter: str | None = None
    for card in pending:
        if card.book != current_book:
            current_book = card.book
            current_chapter = None
            print(f"== {card.book} ==")
        if card.chapter != current_chapter:
            current_chapter = card.chapter
            print(f"  -- {card.chapter or '(no chapter)'} --")
        truncated = " [truncated]" if card.truncated else ""
        print(f"  #{card.id} [{card.status}]{truncated} {card.front}")
    print(f"{len(pending)} card(s) awaiting review")
    return 0


def _cmd_approve(args: argparse.Namespace, container: Container) -> int:
    with container.session() as session:
        card = _queued_card(session, args.card_id)
        approved_at = record_approval(session, card)
        session.commit()
    print(f"approved #{args.card_id} (due {approved_at.isoformat()})")
    return 0


def _cmd_reject(args: argparse.Namespace, container: Container) -> int:
    with container.session() as session:
        card = _queued_card(session, args.card_id)
        record_rejection(session, card, reason=args.reason)
        session.commit()
    print(f"rejected #{args.card_id}: {args.reason}")
    return 0


def _cmd_due(args: argparse.Namespace, container: Container) -> int:
    with container.session() as session:
        scheduler = container.fsrs_scheduler(session)
        due_list = list_due(
            session, scheduler, new_cards_per_day=container.settings.new_cards_per_day
        )
    for card in due_list.cards:
        step = card.step if card.step is not None else "-"
        print(f"#{card.id} [{card.state} step={step}] {card.front}  (due {card.due.isoformat()})")
    print(f"{due_list.due_count} due, {due_list.new_count} new")
    return 0


def _cmd_rate(args: argparse.Namespace, container: Container) -> int:
    with container.session() as session:
        scheduler = container.fsrs_scheduler(session)
        result = rate_card(
            session,
            scheduler,
            RatingCommand(
                card_id=args.card_id,
                rating=args.rating,
                response_ms=args.response_ms,
                rated_at=utc_now(),
                # No device_id: that field identifies a phone, and the CLI is not one.
            ),
        )
    step = result.step if result.step is not None else "-"
    print(
        f"rated #{result.card_id} = {args.rating} "
        f"-> next due {result.next_due.isoformat()} [{result.state} step={step}]"
    )
    return 0


def _cmd_edit(args: argparse.Namespace, container: Container) -> int:
    if args.front is None and args.back is None:
        raise CliError(status=422, detail="edit needs --front and/or --back.")
    with container.session() as session:
        card = _approved_card(session, args.card_id)
        if args.front is not None:
            card.front = args.front
        if args.back is not None:
            card.back = args.back
        card.edited_at = utc_now()
        session.commit()
    print(f"edited #{args.card_id}")
    return 0


def _cmd_bury(args: argparse.Namespace, container: Container) -> int:
    with container.session() as session:
        card = _approved_card(session, args.card_id)
        boundary = bury(card, now=utc_now(), timezone_name=container.settings.timezone)
        session.commit()
    print(f"buried #{args.card_id} until {boundary.isoformat()}")
    return 0


def _cmd_suspend(args: argparse.Namespace, container: Container) -> int:
    with container.session() as session:
        card = _approved_card(session, args.card_id)
        sentinel = suspend(card)
        session.commit()
    print(f"suspended #{args.card_id} until {sentinel.isoformat()}")
    return 0


def _cmd_unsuspend(args: argparse.Namespace, container: Container) -> int:
    with container.session() as session:
        card = _approved_card(session, args.card_id)
        unsuspend(card)
        session.commit()
    print(f"unsuspended #{args.card_id}")
    return 0


def _queued_card(session: Session, card_id: int) -> Card:
    """The card, or the CLI's 404/409 pair: only a card awaiting a decision can
    receive one, so an approve/reject can never silently rewrite a decided card."""
    card = session.get(Card, card_id)
    if card is None:
        raise CliError(status=404, detail=f"Card {card_id} not found.")
    if card.status not in QUEUE_STATUSES:
        raise CliError(
            status=409,
            detail=f"Card {card_id} is not awaiting review (status: {card.status}).",
        )
    return card


def _approved_card(session: Session, card_id: int) -> Card:
    """The card, or the CLI's 404/409 pair: the ADR-008 controls act only on
    `approved` cards (hard rule 1 — edits before approval belong to `approve`)."""
    card = session.get(Card, card_id)
    if card is None:
        raise CliError(status=404, detail=f"Card {card_id} not found.")
    if card.status != "approved":
        raise CliError(
            status=409,
            detail=f"Card {card_id} is not approved (status: {card.status}).",
        )
    return card
