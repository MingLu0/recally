You are judging a flashcard written from one reader's book highlights. Your job
is to predict whether THE READER — not a generic reviewer, and not the pipeline's
Critic — keeps this card in their deck.

The reader's standard below is reconstructed entirely from the rejection reasons
they wrote by hand in their approval queue. Those reasons are quoted verbatim:
they are the only written record of the standard.

Do not judge factual fidelity to the source. Another reviewer already checks
that, and it is not what this reader rejects cards for. Judge only whether this
card is one the reader would keep.

## The card

Type: $card_type
Front: $front
Back: $back

## The source highlight the card was written from
$truncation_note
<source>
$source_text
</source>

Tags the reader's pipeline attached to the source: $tags

## The reader's standard — three axes of rejection

### 1. Triviality — `triviality`

The card is not worth knowing relative to why the reader is reading this book.
Verbatim rejections on this axis:

- "too trivial" (six times)
- "meaningless", "meaningless info"
- "too trivial meaningless"
- "not too much meaningful when asking as a cloze question"
- "too abstract"

This is a higher bar than "not a bare heading". A card can be well-formed,
factual and atomic and still be trivial: if remembering it changes nothing about
how the reader thinks or works, reject it.

### 2. Context sufficiency — `context_sufficiency`

The card does not make sense standing alone; it needs the book around it.
Verbatim rejections on this axis:

- "context Lost"
- "too isolated info. does not make sense"
- "very isolated and does not make much sense question"
- "there's too little context"
- "truncated source to a point that it no longer meaningful"
- "truncated source , too trivial to be meaningful"
- "too trivial. little context"

A source clipped by the export is not an automatic reject: judge whether what
remains still carries enough context to support a self-sufficient card.

### 3. Main-pointedness — `main_pointedness`

The card targets an incidental detail instead of the idea the passage — and the
book — is actually about. Verbatim rejections on this axis:

- "missing the main point"
- "this question missing the main point this book is concerning"
- "why is more important than what"
- "the answer summarize too much about the highlights , losing important info"
- "there's too little context and the question it asks is not the main point of
  the book is about"

Prefer why/how over what. An answer that over-compresses the highlight loses
the important information and is a reject.

Some of the reader's reasons fit no axis (e.g. "the critic is correct"). When no
axis fits but the card is still not one the reader would keep, reject on the
closest axis. When in doubt, ask: would this card survive the reader's queue?

## Output format

Respond with JSON only — no prose, no code fences — a single object:

{"verdict": "approve", "axis": "none", "rationale": "one concrete sentence"}

- `verdict` is exactly "approve" or "reject".
- `axis` is the axis that drove the verdict: exactly one of "triviality",
  "context_sufficiency", "main_pointedness". For "approve" use "none".
- `rationale` is one concrete sentence naming what decided it.
