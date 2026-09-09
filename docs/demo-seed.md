# Demo seed — reviewing the app against real data

A developer runbook for looking at every Android screen with content in it,
rather than the empty/offline state an unseeded backend produces. Not part of
any roadmap gate; `docs/workflow.md` owns those.

`backend/scripts/seed_demo.py` writes rows directly through the models. It makes
**no LLM calls** and does not run the pipeline. FSRS values are hand-written
plausible ones, so the seeded database is for *looking at the UI* — never for
judging scheduling correctness (hard rule 5 keeps the server authoritative).

## Run it

```bash
cd backend
cp .env.example .env          # then set RECALLY_API_KEY to any value
export RECALLY_DATABASE_URL=sqlite:///../data/recally.db
uv run alembic upgrade head
uv run python scripts/seed_demo.py --reset
uv run uvicorn recally.main:app --host 0.0.0.0 --port 8000
```

`--reset` clears the previous demo rows first, so re-running is safe.
`--no-due` seeds no due cards, for reviewing Today's nothing-due state.

## Point the app at it

In the app's **Settings**, enter the backend URL and the `RECALLY_API_KEY` from
`.env`, then tap *Test connection*.

| Running on | Backend URL |
|---|---|
| Emulator | `http://10.0.2.2:8000` (the host loopback; the LAN IP will not work) |
| Physical phone | `http://<your-mac-lan-ip>:8000`, same Wi-Fi |

Cleartext HTTP is permitted in the **debug** build only
(`android/app/src/debug/res/xml/network_security_config.xml`); release permits
none.

## What it seeds

3 books, ~30 highlights (2 truncated), and:

| Cards | Count | Makes visible |
|---|---|---|
| approved, due now | 12 | Today's due count, a real review session |
| approved, never reviewed | 5 | The new-card allotment, capped by `NEW_CARDS_PER_DAY` |
| approved, suspended | 2 | Unsuspend on Book detail (ADR-008) |
| `pending_review` | 8 | The approval queue |
| `needs_human` | 3 | The "needs you" filter and the critique block |

Plus ~25 `review_logs` over 6 consecutive days, so the streak, `reviews_today`
and `retention_30d` tiles are non-zero. Both `qa` and `cloze` types appear.

## What it does not cover

Offline, 401, loading skeletons and font-2x are **not** reachable this way.
Those live in the 39 `@CombinedPreviews` functions in the Android source, which
render in Android Studio's preview pane. Three are reachable on a seeded
backend: aeroplane mode (offline), a wrong API key (401), and `--no-due`
(nothing-due).
