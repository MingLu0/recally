//> using scala 3.3.4
//> using dep org.typelevel::cats-effect::3.5.4
//> using dep com.lihaoyi::upickle::3.1.3
//> using dep com.lihaoyi::os-lib::0.9.1

// Stateful parallel dispatcher for roadmap sub-issues (ADR-013, ADR-014).
//
// Dispatch eligibility is never decided here: scripts/orca-ready-issues.sh --all
// is the only source (open, sub-issue, no open blocker, unassigned, no open PR
// — no human label since ADR-014). Gate and merge policy live in
// scripts/orca-autostart-prompt.md and are executed by the worktree agent.
// This script never runs `gh pr merge`.
//
// Logging is change-only: a status line prints when the tracked picture
// changes, events print as they happen, and a dim heartbeat every 10 ticks
// proves the loop is alive. --dry-run always prints the full state panel.
//
// Usage: scala-cli scripts/orchestrate.sc -- [--dry-run] [--once] [--step=<label>]
//   --step=step-4 scopes dispatch to issues carrying that label (ADR-013).

import cats.effect.*
import cats.syntax.all.*
import scala.concurrent.duration.*

object Orchestrator:

  // --- configuration (ADR-013) ---
  val WorktreeCap = 10
  val AgentPool = List("opencode", "claude")
  val PollInterval = 60.seconds
  val MaxAttempts = 2       // dispatch retries per issue before needs_human
  val MaxConflictFixes = 2  // rebase dispatches per PR before needs_human
  val GithubRepo = "MingLu0/recally"
  val PrecheckScript = "scripts/orca-ready-issues.sh"
  val PromptFile: os.RelPath = os.rel / "scripts" / "orca-autostart-prompt.md"
  val StatePath = os.pwd / ".orca" / "orchestrator-state.json"
  val TddEvidenceMarker = "## TDD evidence"
  val RateLimitSignatures = List("rate limit", "429", "usage limit", "quota", "limit reached")

  object Phase:
    val Dispatched = "dispatched"  // worker running, no PR yet
    val PrOpen = "pr_open"         // PR exists, not merged
    val Merged = "merged"          // terminal
    val NeedsHuman = "needs_human" // reconciled every tick (ADR-014): closed →
    // merged, open + unassigned → dropped (redispatchable), open + assigned → kept
  val ActivePhases = Set(Phase.Dispatched, Phase.PrOpen)

  case class TrackedIssue(
    issue: Int,
    title: String,
    taskId: String,
    dispatchId: String,
    agent: String,
    attempts: Int,
    conflictFixes: Int,
    conflictDispatchId: Option[String],
    evidenceNudged: Boolean,
    phase: String,
  )
  object TrackedIssue:
    given upickle.default.ReadWriter[TrackedIssue] = upickle.default.macroRW
  // lastActionNeeded is the dedup signature for desktop notifications: a
  // notification fires only when the action-needed set changes.
  // lastStatus is the dedup signature for the terminal status line: it prints
  // only when the tracked picture changes (ADR-014, quiet logs).
  case class State(
    tracked: List[TrackedIssue],
    lastActionNeeded: List[String] = Nil,
    lastStatus: String = "",
  )
  object State:
    given upickle.default.ReadWriter[State] = upickle.default.macroRW

  case class ReadyIssue(number: Int, title: String, labels: List[String])

  // --- shell helpers ---

  def sh(cmd: List[String]): IO[String] =
    IO.blocking(os.proc(cmd).call(cwd = os.pwd, check = true).out.text().trim)

  def shOpt(cmd: List[String]): IO[Option[String]] =
    IO.blocking {
      val result = os.proc(cmd).call(cwd = os.pwd, check = false, stderr = os.Pipe)
      if result.exitCode == 0 then Some(result.out.text().trim) else None
    }

  // A structured orca failure (envelope ok=false, or result.state == "failed").
  // worker-start failures carry a dispatchId already marked failed, so callers
  // can hand them to the normal reconcile retry path instead of crashing.
  case class OrcaException(lastError: String, dispatchId: Option[String], residualTerminals: List[String])
      extends RuntimeException(lastError)

  // orca --json responses are wrapped in { ok, result }. Exit code is ignored
  // on purpose: worker-start exits 1 with ok=true + state=failed, and that
  // payload is data (ADR-014), not a process crash.
  def orca(args: List[String]): IO[ujson.Value] =
    IO.blocking(os.proc("orca" :: args ::: List("--json")).call(cwd = os.pwd, check = false, stderr = os.Pipe))
      .flatMap { procResult =>
        val raw = procResult.out.text().trim
        if raw.isEmpty then IO.raiseError(new RuntimeException(s"orca ${args.mkString(" ")} produced no JSON (exit ${procResult.exitCode})"))
        else
          val envelope = ujson.read(raw)
          val result = envelope.obj.get("result").getOrElse(ujson.Null)
          val failedState = findKey(result, "state").collect { case ujson.Str(s) => s }.exists(s => s == "failed" || s == "error")
          if envelope("ok").bool && !failedState then IO.pure(result)
          else
            val lastError = findKey(envelope, "lastError").orElse(findKey(envelope, "message"))
              .collect { case ujson.Str(s) => s }.getOrElse("unknown")
            val dispatchId = findKey(result, "dispatchId").collect { case ujson.Str(s) => s }
            val residuals = findKey(result, "residualResources").map(_.arr.toList).getOrElse(Nil)
              .filter(r => findKey(r, "kind").contains(ujson.Str("terminal")))
              .flatMap(r => findKey(r, "id").collect { case ujson.Str(s) => s })
            IO.raiseError(OrcaException(lastError, dispatchId, residuals))
      }

  def gh(args: List[String]): IO[String] = sh("gh" :: args)

  def ghJson(args: List[String]): IO[ujson.Value] = gh(args).map(ujson.read(_))

  // Orca JSON shapes are not contractual; search recursively for a key and
  // fail loudly with the raw payload when a required field is absent.
  def findKey(value: ujson.Value, key: String): Option[ujson.Value] = value match
    case obj: ujson.Obj =>
      obj.value.get(key).orElse(obj.value.values.iterator.map(findKey(_, key)).collectFirst { case Some(v) => v })
    case arr: ujson.Arr =>
      arr.value.iterator.map(findKey(_, key)).collectFirst { case Some(v) => v }
    case _ => None

  def findString(value: ujson.Value, keys: Set[String]): Option[String] =
    keys.iterator.map(k => findKey(value, k)).collectFirst { case Some(ujson.Str(s)) => s }

  def requiredString(value: ujson.Value, keys: Set[String], what: String): IO[String] =
    IO.fromOption(findString(value, keys))(new RuntimeException(s"Cannot find $what in: ${value.render()}"))

  // --- state persistence ---

  def loadState: IO[State] = IO.blocking {
    if os.exists(StatePath) then upickle.default.read[State](os.read(StatePath)) else State(Nil)
  }

  def saveState(state: State): IO[Unit] = IO.blocking {
    if !os.exists(StatePath / os.up) then os.makeDir(StatePath / os.up)
    os.write.over(StatePath, upickle.default.write(state, indent = 2))
  }

  // --- GitHub queries (read-only) ---

  def issueState(issue: Int): IO[String] =
    gh(List("issue", "view", issue.toString, "--repo", GithubRepo, "--json", "state", "-q", ".state"))

  def prsForIssue(state: String, issue: Int): IO[List[ujson.Value]] =
    ghJson(List("pr", "list", "--repo", GithubRepo, "--state", state, "--limit", "100",
      "--json", "number,mergeable,closingIssuesReferences,body,files")).map { arr =>
      arr.arr.toList.filter(pr => findKey(pr, "closingIssuesReferences").exists { refs =>
        refs.arr.exists(_("number").num.toInt == issue)
      })
    }

  // Docs-only PRs are never auto-merged (ADR-013): a mechanical gate cannot judge a
  // spec change, so the TDD-evidence check does not apply and the PR waits for a human.
  def isDocsOnly(pr: ujson.Value): Boolean =
    findKey(pr, "files").forall { files =>
      files.arr.toList.map(_("path").str).forall(p => p.startsWith("docs/") || p.endsWith(".md"))
    }

  // --- worker inspection ---

  sealed trait WorkerStatus
  case object Running extends WorkerStatus
  case object Settled extends WorkerStatus
  case class Failed(rateLimited: Boolean) extends WorkerStatus
  case object WaitingOnHuman extends WorkerStatus
  case object Unknown extends WorkerStatus

  // Shape verified against a live worker-show: result.dispatch.status
  // (dispatched → completed/failed) and result.worker.state (ready → ...).
  def workerStatus(dispatchId: String): IO[WorkerStatus] =
    shOpt(List("orca", "orchestration", "worker-show", "--dispatch", dispatchId, "--json")).map {
      case None => Unknown
      case Some(raw) =>
        val json = ujson.read(raw)
        val result = if json.obj.get("ok").exists(_.bool) then json("result") else json
        val waiting = findKey(result, "agentWait").exists(v => v != ujson.Null)
        def strAt(container: String, key: String): String =
          findKey(result, container).flatMap(findKey(_, key)).collect { case ujson.Str(s) => s.toLowerCase }.getOrElse("")
        val dispatchStatus = strAt("dispatch", "status")
        val workerState = strAt("worker", "state")
        val isRateLimited = RateLimitSignatures.exists(raw.toLowerCase.contains)
        if waiting then WaitingOnHuman
        else if dispatchStatus.contains("fail") || dispatchStatus.contains("error") || workerState.contains("fail") then Failed(isRateLimited)
        else if dispatchStatus.contains("complete") || dispatchStatus.contains("success") || workerState.contains("exit") then Settled
        else if dispatchStatus.nonEmpty || workerState.nonEmpty then Running // dispatched/ready/running all mean in flight
        else Unknown
    }.handleError(_ => Unknown)

  // --- dispatch ---

  def promptText: String = os.read(os.pwd / PromptFile)

  // Each orca invocation is a fresh process, so the Run binding from
  // run-create does not persist: every mutation must pass --run explicitly.
  def createRun: IO[String] =
    orca(List("orchestration", "run-create", "--objective", "recally orchestrator"))
      .flatMap(requiredString(_, Set("runId", "id"), "run id"))

  def createTask(spec: String, title: String, runId: String): IO[String] =
    orca(List("orchestration", "task-create", "--spec", spec, "--task-title", title, "--run", runId))
      .flatMap(requiredString(_, Set("taskId", "id"), "task id"))

  def startWorker(taskId: String, issue: Int, agent: String, retryOf: Option[String], runId: String): IO[String] =
    val base = List("orchestration", "worker-start", "--task", taskId, "--worktree", s"issue:$issue", "--agent", agent, "--run", runId)
    orca(base ::: retryOf.toList.flatMap(id => List("--retry-of", id)))
      .flatMap(requiredString(_, Set("dispatchId", "dispatch"), "dispatch id"))

  def slugify(title: String): String =
    title.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-").take(40)

  // After a failed worker-start: close any residual agent terminals, and
  // remove the worktree only when there is no dispatch to retry through (a
  // present dispatchId means reconcile's Failed branch will retry into it).
  def cleanupAfterStartFailure(issue: Int, e: OrcaException): IO[Unit] =
    e.residualTerminals.traverse(t => shOpt(List("orca", "terminal", "close", "--terminal", t, "--json"))).void *>
      (if e.dispatchId.isEmpty then
         shOpt(List("orca", "worktree", "rm", "--worktree", s"issue:$issue", "--json")).void
       else IO.unit)

  def dispatchIssue(candidate: ReadyIssue, agent: String, repoId: String, runId: String): IO[TrackedIssue] =
    val assignment = s"\n\n---\nOrchestrator assignment: your issue is #${candidate.number}. " +
      s"Skip the 'Pick the issue' step; claim #${candidate.number} and implement it."
    val blank = TrackedIssue(candidate.number, candidate.title, "", "", agent,
      attempts = 1, conflictFixes = 0, conflictDispatchId = None, evidenceNudged = false, Phase.Dispatched)
    for
      _ <- event(s"🌱 #${candidate.number} dispatched → $agent — ${candidate.title}")
      _ <- orca(List("worktree", "create", "--repo", s"id:$repoId",
        "--name", s"issue-${candidate.number}-${slugify(candidate.title)}",
        "--issue", candidate.number.toString, "--base-branch", "main"))
      taskId <- createTask(promptText + assignment, s"Issue #${candidate.number}: ${candidate.title}", runId)
      tracked <- startWorker(taskId, candidate.number, agent, retryOf = None, runId)
        .map(dispatchId => blank.copy(taskId = taskId, dispatchId = dispatchId))
        .handleErrorWith {
          case e: OrcaException =>
            cleanupAfterStartFailure(candidate.number, e) *> (e.dispatchId match
              case Some(dispatchId) =>
                // the dispatch exists and is already marked failed; the normal
                // reconcile Failed branch will retry/failover/escalate it
                event(s"💥 #${candidate.number} worker-start failed (${e.lastError}) — reconcile will retry")
                  .as(blank.copy(taskId = taskId, dispatchId = dispatchId))
              case None =>
                escalate(blank.copy(taskId = taskId),
                  s"worker-start failed (${e.lastError}) with no dispatch id to retry through.").map(_.get))
        }
    yield tracked

  // A redispatch carries a NEW spec (conflict fix, evidence nudge), so it is a
  // new task and a fresh dispatch — no --retry-of (Orca rejects retry across
  // tasks: "cannot retry from Dispatch").
  def redispatch(t: TrackedIssue, agent: String, spec: String, title: String, runId: String): IO[String] =
    for
      taskId <- createTask(spec, title, runId)
      dispatchId <- startWorker(taskId, t.issue, agent, retryOf = None, runId)
    yield dispatchId

  // A retry re-runs the SAME task after a worker failure; --retry-of links the
  // replacement attempt to the failed dispatch.
  def retryWorker(t: TrackedIssue, agent: String, runId: String): IO[String] =
    startWorker(t.taskId, t.issue, agent, retryOf = Some(t.dispatchId), runId)

  def nextAgent(current: String): Option[String] =
    AgentPool.dropWhile(_ != current).drop(1).headOption

  // --- logging (events always print; status prints on change only, ADR-014) ---

  val UseColor: Boolean = sys.env.get("TERM").forall(_ != "dumb")
  def dim(s: String): String = if UseColor then s"\u001b[2m$s\u001b[0m" else s
  def yellow(s: String): String = if UseColor then s"\u001b[33m$s\u001b[0m" else s
  def green(s: String): String = if UseColor then s"\u001b[32m$s\u001b[0m" else s
  def red(s: String): String = if UseColor then s"\u001b[31m$s\u001b[0m" else s

  def now: String = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

  def event(msg: String): IO[Unit] = IO.println(s"$now  $msg")

  def phaseGlyph(phase: String): String = phase match
    case Phase.Dispatched => "🌱"
    case Phase.PrOpen => "👀"
    case Phase.Merged => "🎉"
    case _ => "🆘"

  def phaseWord(phase: String): String = phase match
    case Phase.Dispatched => "dispatched"
    case Phase.PrOpen => "PR open"
    case Phase.Merged => "merged"
    case _ => "needs you"

  // --- reconcile one tracked issue ---
  // Returns None when the issue should be dropped from tracking (a needs-human
  // issue the human reset by unassigning — it is redispatchable via the precheck).

  def escalate(t: TrackedIssue, why: String): IO[Option[TrackedIssue]] =
    for
      _ <- event(red(s"🆘 #${t.issue} needs you — $why"))
      _ <- gh(List("issue", "comment", t.issue.toString, "--repo", GithubRepo, "--body",
        s"Orchestrator: $why Leaving this for a human.")).attempt
    yield Some(t.copy(phase = Phase.NeedsHuman))

  def releaseWorker(dispatchId: String): IO[Unit] =
    shOpt(List("orca", "orchestration", "worker-release", "--dispatch", dispatchId, "--json")).void

  // Sleep a completed worktree: close every terminal process it owns and move
  // it to the completed board column. Not `gradlew --stop` — the Gradle daemon
  // registry is shared across worktrees, so stopping could kill a sibling's
  // in-flight build (idle daemons time out on their own). Not `worktree rm` —
  // the worktree stays for diff browsing; disk reclaim is a manual sweep.
  def sleepWorktree(issue: Int, dispatchId: String, announce: Boolean): IO[Unit] =
    releaseWorker(dispatchId) *>
      shOpt(List("orca", "terminal", "close", "--worktree", s"issue:$issue", "--all", "--json")).void *>
      shOpt(List("orca", "worktree", "set", "--worktree", s"issue:$issue", "--workspace-status", "completed", "--json")).void *>
      (if announce then event(s"😴 #$issue worktree asleep") else IO.unit)

  def assigneeCount(issue: Int): IO[Int] =
    gh(List("issue", "view", issue.toString, "--repo", GithubRepo, "--json", "assignees",
      "-q", ".assignees | length")).map(_.trim.toInt).handleError(_ => 1) // fail closed: keep reporting

  def reconcile(t: TrackedIssue, repoId: String, runId: String): IO[Option[TrackedIssue]] =
    t.phase match
      case Phase.Merged => IO.pure(Some(t))
      case Phase.NeedsHuman => reconcileNeedsHuman(t)
      case _ => reconcileActive(t, repoId, runId).map(Some(_))

  // needs-human is not terminal (ADR-014): the state file follows reality.
  def reconcileNeedsHuman(t: TrackedIssue): IO[Option[TrackedIssue]] =
    issueState(t.issue).flatMap {
      case "CLOSED" =>
        event(green(s"🎉 #${t.issue} merged — closed while marked needs-human, following reality")) *>
          sleepWorktree(t.issue, t.dispatchId, announce = true) *> IO.pure(Some(t.copy(phase = Phase.Merged)))
      case _ =>
        assigneeCount(t.issue).flatMap { count =>
          if count == 0 then
            event(s"🧹 #${t.issue} reset (unassigned) — dropped from tracking, redispatchable") *>
              IO.pure(None)
          else IO.pure(Some(t))
        }
    }

  def reconcileActive(t: TrackedIssue, repoId: String, runId: String): IO[TrackedIssue] =
    issueState(t.issue).flatMap {
      case "CLOSED" =>
        event(green(s"🎉 #${t.issue} merged — slot freed")) *> sleepWorktree(t.issue, t.dispatchId, announce = true) *> IO.pure(t.copy(phase = Phase.Merged))
      case _ =>
        for
          merged <- prsForIssue("merged", t.issue)
          open <- prsForIssue("open", t.issue)
          result <-
            if merged.nonEmpty then
              event(green(s"🎉 #${t.issue} merged — slot freed")) *> sleepWorktree(t.issue, t.dispatchId, announce = true) *> IO.pure(t.copy(phase = Phase.Merged))
            else open match
              case pr :: _ => reconcileOpenPr(t, pr, repoId, runId)
              case Nil => reconcileNoPr(t, repoId, runId)
        yield result
    }

  def reconcileOpenPr(t: TrackedIssue, pr: ujson.Value, repoId: String, runId: String): IO[TrackedIssue] =
    val tracked = t.copy(phase = Phase.PrOpen)
    val mergeable = findString(pr, Set("mergeable")).getOrElse("UNKNOWN")
    if mergeable == "CONFLICTING" then handleConflict(tracked, pr, repoId, runId)
    else handleEvidence(tracked, pr, repoId, runId)

  def handleConflict(t: TrackedIssue, pr: ujson.Value, repoId: String, runId: String): IO[TrackedIssue] =
    val prNumber = pr("number").num.toInt
    t.conflictDispatchId match
      case Some(id) =>
        workerStatus(id).flatMap {
          case Running | Unknown => IO.pure(t) // fix in flight, or unverifiable — never double-dispatch
          case WaitingOnHuman => escalate(t, "conflict-fix worker is parked on a question only a human can answer.").map(_.get)
          case Failed(_) | Settled => dispatchConflictFix(t, prNumber, repoId, runId) // done but still conflicting
        }
      case None => dispatchConflictFix(t, prNumber, repoId, runId)

  def dispatchConflictFix(t: TrackedIssue, prNumber: Int, repoId: String, runId: String): IO[TrackedIssue] =
    if t.conflictFixes >= MaxConflictFixes then
      gh(List("pr", "comment", prNumber.toString, "--repo", GithubRepo, "--body",
        s"Orchestrator: still conflicting with `main` after ${t.conflictFixes} rebase attempts. Leaving this for a human.")).attempt *>
        IO.pure(t.copy(phase = Phase.NeedsHuman, conflictDispatchId = None))
    else
      val spec = s"PR #$prNumber for issue #${t.issue} has merge conflicts with `main`. " +
        "Rebase your branch onto origin/main, resolve the conflicts (docs are the spec; when your " +
        "code and a doc disagree, the doc wins), re-run `uv run pytest`, `uv run ruff check .` and " +
        "`uv run mypy src/` from backend/, force-push, and then follow the merge policy again."
      event(s"🔀 #${t.issue} PR #$prNumber conflicting → rebase dispatched (${t.conflictFixes + 1}/$MaxConflictFixes)") *>
        redispatch(t, t.agent, spec, s"Rebase issue #${t.issue} onto main", runId).map { newDispatchId =>
          t.copy(conflictFixes = t.conflictFixes + 1, conflictDispatchId = Some(newDispatchId))
        }

  def handleEvidence(t: TrackedIssue, pr: ujson.Value, repoId: String, runId: String): IO[TrackedIssue] =
    val prNumber = pr("number").num.toInt
    if isDocsOnly(pr) then IO.pure(t) // waits for a human by policy; no evidence to demand
    else
      val hasEvidence = findKey(pr, "body").exists(_.str.contains(TddEvidenceMarker))
      if hasEvidence then IO.pure(t) // worktree agent owns the merge decision from here
      else
        workerStatus(t.dispatchId).flatMap {
          case Running | Unknown => IO.pure(t) // still working; check again next tick
          case _ if !t.evidenceNudged =>
            val spec = s"PR #$prNumber for issue #${t.issue} is missing the `$TddEvidenceMarker` section. " +
              "Add it with the red run of every negative assertion captured before the implementation " +
              "existed, next to the green run, then follow the merge policy again. If the red run never " +
              "happened, say so in the PR and stop."
            event(s"📝 #${t.issue} PR #$prNumber missing $TddEvidenceMarker — nudging") *>
              redispatch(t, t.agent, spec, s"TDD evidence for issue #${t.issue}", runId)
                .map(newDispatchId => t.copy(dispatchId = newDispatchId, evidenceNudged = true))
          case _ =>
            escalate(t, s"PR #$prNumber still lacks the `$TddEvidenceMarker` section after a nudge.").map(_.get)
        }

  def reconcileNoPr(t: TrackedIssue, repoId: String, runId: String): IO[TrackedIssue] =
    workerStatus(t.dispatchId).flatMap {
      case Running | Unknown => IO.pure(t)
      case Settled =>
        escalate(t, "worker settled without opening a PR; check its output in Orca.").map(_.get)
      case WaitingOnHuman =>
        escalate(t, "worker is parked on a question only a human can answer (agentWait).").map(_.get)
      case Failed(rateLimited) if rateLimited =>
        nextAgent(t.agent) match
          case Some(failover) =>
            event(s"♻️  #${t.issue} ${t.agent} rate-limited → $failover") *>
              retryWorker(t, failover, runId).map { newDispatchId =>
                t.copy(dispatchId = newDispatchId, agent = failover, attempts = t.attempts + 1)
              }
          case None =>
            escalate(t, s"agent pool exhausted (${AgentPool.mkString(" -> ")}); all rate-limited.").map(_.get)
      case Failed(_) =>
        if t.attempts < MaxAttempts then
          event(s"🔁 #${t.issue} worker failed — retry ${t.attempts + 1}/$MaxAttempts on ${t.agent}") *>
            retryWorker(t, t.agent, runId).map { newDispatchId =>
              t.copy(dispatchId = newDispatchId, attempts = t.attempts + 1)
            }
        else escalate(t, s"worker failed ${t.attempts} times; giving up.").map(_.get)
    }

  def assignmentFor(t: TrackedIssue): String =
    s"\n\n---\nOrchestrator assignment: your issue is #${t.issue}. " +
      s"Skip the 'Pick the issue' step; claim #${t.issue} and implement it."

  // --- eligibility (the precheck is the only source) ---

  def readyIssues: IO[List[ReadyIssue]] =
    shOpt(List("bash", PrecheckScript, "--all")).map {
      case None => Nil
      case Some(out) =>
        out.linesIterator.toList.filter(_.trim.nonEmpty).map { line =>
          val json = ujson.read(line)
          val parent = findKey(json, "parent").map(_.num.toInt)
          assert(parent.nonEmpty, s"precheck printed a parent issue: $line") // structural guard, ADR-013
          ReadyIssue(json("number").num.toInt, json("title").str, json("labels").arr.map(_.str).toList)
        }
    }

  def resolveRepoId: IO[String] =
    orca(List("repo", "list")).flatMap { result =>
      val repos = findKey(result, "repos").map(_.arr.toList).getOrElse(Nil)
      repos.find(r => findKey(r, "path").exists(_.str == os.pwd.toString)) match
        case Some(repo) => requiredString(repo, Set("id"), "repo id")
        case None => IO.raiseError(new RuntimeException(s"No Orca repo registered at ${os.pwd}; run `orca repo add` first."))
    }

  // --- progress reporting (read-only; never a dispatch input) ---

  case class OpenPr(number: Int, docsOnly: Boolean, closes: Set[Int])

  def openPrs: IO[List[OpenPr]] =
    ghJson(List("pr", "list", "--repo", GithubRepo, "--state", "open", "--limit", "100",
      "--json", "number,files,closingIssuesReferences")).map { arr =>
      arr.arr.toList.map { pr =>
        val files = findKey(pr, "files").map(_.arr.toList.map(_("path").str)).getOrElse(Nil)
        val closes = findKey(pr, "closingIssuesReferences")
          .map(_.arr.toList.map(_("number").num.toInt).toSet).getOrElse(Set.empty)
        OpenPr(pr("number").num.toInt,
          files.nonEmpty && files.forall(p => p.startsWith("docs/") || p.endsWith(".md")), closes)
      }
    }

  def buildActions(tracked: List[TrackedIssue], prs: List[OpenPr]): List[String] =
    val needsHuman = tracked.filter(_.phase == Phase.NeedsHuman).map(t => s"#${t.issue} needs you (see issue comment)")
    val docsOnlyWaiting = for
      pr <- prs if pr.docsOnly
      issue <- pr.closes if tracked.exists(t => t.issue == issue && ActivePhases(t.phase))
    yield s"#$issue PR #${pr.number} is docs-only — waiting for human merge"
    (needsHuman ++ docsOnlyWaiting).sorted

  def statusSignature(tracked: List[TrackedIssue], freeSlots: Int, actions: List[String]): String =
    val active = tracked.filter(t => ActivePhases(t.phase))
    s"${active.map(t => s"${t.issue}:${t.phase}:${t.agent}").mkString(",")}|$freeSlots|${actions.mkString(";")}"

  // The boxed dashboard: full version at startup and for --dry-run (with the
  // static meta line), compact version on every state change in loop mode.
  def panelLines(meta: Option[String], tracked: List[TrackedIssue], freeSlots: Int,
                 actions: List[String], extra: List[String]): (List[String], List[String]) =
    val header = s"╭─ 🍊 recally orch ─ $now " + "─" * 20
    val trackedLines = tracked.filter(t => ActivePhases(t.phase)).map(t =>
      s"│  ${phaseGlyph(t.phase)} #${t.issue} ${phaseWord(t.phase)} (${t.agent})")
    val slotsLine = s"│  ⚡ $freeSlots slots free"
    val extraLines = extra.map(e => s"│  $e")
    val footer =
      if actions.isEmpty then "╰─ ✨ nothing needs you"
      else "╰─ ⚠️  needs you:" // actions printed below the box
    (List(header) ++ meta.toList.map(m => s"│  $m") ++ trackedLines ++ List(slotsLine) ++ extraLines ++ List(footer), actions)

  def printPanel(runId: String, stepScope: Option[String], tracked: List[TrackedIssue],
                 freeSlots: Int, actions: List[String], extra: List[String]): IO[Unit] =
    val meta = s"scope ${stepScope.getOrElse("all")} · cap $WorktreeCap · pool ${AgentPool.mkString("→")} · run $runId"
    val (lines, actionLines) = panelLines(Some(meta), tracked, freeSlots, actions, extra)
    lines.traverse(IO.println) >> actionLines.traverse(a => IO.println(s"   ${yellow(a)}")).void

  def printChange(tracked: List[TrackedIssue], freeSlots: Int, actions: List[String]): IO[Unit] =
    val (lines, actionLines) = panelLines(None, tracked, freeSlots, actions, Nil)
    lines.traverse(IO.println) >> actionLines.traverse(a => IO.println(s"   ${yellow(a)}")).void

  def heartbeat(tracked: List[TrackedIssue], actions: List[String]): IO[Unit] =
    val working = tracked.count(t => ActivePhases(t.phase))
    val tail = if actions.isEmpty then "nothing needs you" else s"${actions.size} still need${if actions.size == 1 then "s" else ""} you"
    IO.println(dim(s"· $now alive — $working working, $tail"))

  def notifyMac(actions: List[String]): IO[Unit] =
    val summary = actions.take(3).mkString("; ").replace("\"", "'")
    shOpt(List("osascript", "-e",
      s"""display notification "$summary" with title "Recally orchestrator 🍊" sound name "Glass"""")).void

  // --- main loop ---

  val HeartbeatEvery = 10 // ticks

  def tick(dryRun: Boolean, stepScope: Option[String], runId: String,
           tickNum: Int, forcePrint: Boolean): IO[State] =
    for
      state <- loadState
      repoId <- resolveRepoId
      reconciled <-
        if dryRun then IO.pure(state.tracked)
        else state.tracked.traverse(t =>
          reconcile(t, repoId, runId).handleErrorWith(e =>
            event(red(s"💥 #${t.issue} reconcile error: ${e.getMessage.take(120)} — keeping as-is, retry next tick")).as(Some(t)))
        ).map(_.flatten)
      active = reconciled.filter(t => ActivePhases(t.phase))
      freeSlots = WorktreeCap - active.size
      prs <- openPrs
      candidates <- if freeSlots > 0 then readyIssues else IO.pure(Nil)
      scoped = candidates.filter(c => stepScope.forall(scope => c.labels.contains(scope)))
      fresh = scoped.filterNot(c => reconciled.exists(_.issue == c.number)).take(freeSlots)
      dispatched <-
        if dryRun then IO.pure(reconciled)
        else fresh.traverse(c =>
          dispatchIssue(c, AgentPool.head, repoId, runId).map(Some(_)).handleErrorWith(e =>
            event(red(s"💥 #${c.number} dispatch error: ${e.getMessage.take(120)} — will retry next tick")).as(None))
        ).map(newOnes => reconciled ++ newOnes.flatten)
      freeAfter = WorktreeCap - dispatched.count(t => ActivePhases(t.phase))
      actions = buildActions(dispatched, prs)
      signature = statusSignature(dispatched, freeAfter, actions)
      changed = signature != state.lastStatus
      _ <-
        if dryRun then
          printPanel("(dry-run)", stepScope, dispatched, freeAfter, actions,
            List(s"dispatchable now: ${if fresh.isEmpty then "none" else fresh.map(_.number).mkString(", ")}"))
        else if forcePrint || changed then printChange(dispatched, freeAfter, actions)
        else if tickNum % HeartbeatEvery == 0 then heartbeat(dispatched, actions)
        else IO.unit
      _ <-
        if dryRun then IO.unit
        else
          val actionsChanged = actions != state.lastActionNeeded
          (if actionsChanged && actions.nonEmpty then notifyMac(actions) else IO.unit) >>
            saveState(State(dispatched, actions, signature))
    yield State(dispatched, actions, signature)

  def loop(stepScope: Option[String], runId: String, tickNum: Int): IO[Unit] =
    tick(dryRun = false, stepScope, runId, tickNum, forcePrint = false) >>
      IO.sleep(PollInterval) >> loop(stepScope, runId, tickNum + 1)

  // One-off sweep for worktrees that completed before the sleep lifecycle
  // existed: terminals closed, board status completed. Idempotent and cheap,
  // but noisy per issue, so it announces once. Runs on every start; after the
  // backlog is swept it is a no-op.
  def sweepCompletedWorktrees(state: State): IO[Unit] =
    val completed = state.tracked.filter(_.phase == Phase.Merged)
    completed.traverse(t => sleepWorktree(t.issue, t.dispatchId, announce = false)).void *>
      (if completed.isEmpty then IO.unit
       else event(s"😴 swept ${completed.size} completed worktree${if completed.size == 1 then "" else "s"} to sleep"))

  def program(args: List[String]): IO[ExitCode] =
    val dryRun = args.contains("--dry-run")
    val once = args.contains("--once")
    val stepScope = args.find(_.startsWith("--step=")).map(_.stripPrefix("--step="))
    if dryRun then tick(dryRun = true, stepScope, runId = "", tickNum = 0, forcePrint = true).void.as(ExitCode.Success)
    else
      // One Run per process; every orca mutation passes --run explicitly because
      // each CLI call is a fresh process with no binding.
      createRun.flatMap { runId =>
        (if once then tick(dryRun = false, stepScope, runId, tickNum = 1, forcePrint = true).void
         else
           // startup panel shows the pre-reconcile picture; the first tick prints
           // the reconciled one (forcePrint via tickNum 0 always printing? no — first
           // loop tick prints only on change, which it almost always is after startup)
           loadState.flatMap { state =>
             val active = state.tracked.filter(t => ActivePhases(t.phase))
             printPanel(runId, stepScope, state.tracked, WorktreeCap - active.size, state.lastActionNeeded, Nil) >>
               sweepCompletedWorktrees(state)
           } >> loop(stepScope, runId, tickNum = 1))
      }.as(ExitCode.Success)

// .sc entry point: the script wrapper main runs top-level statements, so the
// IOApp object above must be invoked explicitly.
import cats.effect.unsafe.implicits.global
sys.exit(Orchestrator.program(args.toList).unsafeRunSync().code)
