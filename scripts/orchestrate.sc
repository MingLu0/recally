//> using scala 3.3.4
//> using dep org.typelevel::cats-effect::3.5.4
//> using dep com.lihaoyi::upickle::3.1.3
//> using dep com.lihaoyi::os-lib::0.9.1

// Stateful parallel dispatcher for roadmap sub-issues (ADR-013).
//
// Dispatch eligibility is never decided here: scripts/orca-ready-issues.sh --all
// is the only source (ready label, no open blocker, sub-issue, unassigned, no
// open PR). Gate and merge policy live in scripts/orca-autostart-prompt.md and
// are executed by the worktree agent. This script never runs `gh pr merge`.
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
    val Dispatched = "dispatched" // worker running, no PR yet
    val PrOpen = "pr_open"        // PR exists, not merged
    val Merged = "merged"         // terminal
    val NeedsHuman = "needs_human" // terminal until a human resets it
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
  case class State(tracked: List[TrackedIssue], lastActionNeeded: List[String] = Nil)
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

  // orca --json responses are wrapped in { ok, result }; unwrap or fail.
  def orca(args: List[String]): IO[ujson.Value] =
    sh("orca" :: args ::: List("--json")).flatMap { raw =>
      val envelope = ujson.read(raw)
      if envelope("ok").bool then IO.pure(envelope("result"))
      else IO.raiseError(new RuntimeException(s"orca ${args.mkString(" ")} failed: $raw"))
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

  def dispatchIssue(candidate: ReadyIssue, agent: String, repoId: String, runId: String): IO[TrackedIssue] =
    val assignment = s"\n\n---\nOrchestrator assignment: your issue is #${candidate.number}. " +
      s"Skip the 'Pick the issue' step; claim #${candidate.number} and implement it."
    for
      _ <- IO.println(s"[dispatch] #${candidate.number} (${candidate.title}) on $agent")
      _ <- orca(List("worktree", "create", "--repo", s"id:$repoId",
        "--name", s"issue-${candidate.number}-${slugify(candidate.title)}",
        "--issue", candidate.number.toString, "--base-branch", "main"))
      taskId <- createTask(promptText + assignment, s"Issue #${candidate.number}: ${candidate.title}", runId)
      dispatchId <- startWorker(taskId, candidate.number, agent, retryOf = None, runId)
    yield TrackedIssue(candidate.number, candidate.title, taskId, dispatchId, agent,
      attempts = 1, conflictFixes = 0, conflictDispatchId = None, evidenceNudged = false, Phase.Dispatched)

  def redispatch(t: TrackedIssue, agent: String, spec: String, title: String, runId: String): IO[String] =
    for
      taskId <- createTask(spec, title, runId)
      dispatchId <- startWorker(taskId, t.issue, agent, retryOf = Some(t.dispatchId), runId)
    yield dispatchId

  def nextAgent(current: String): Option[String] =
    AgentPool.dropWhile(_ != current).drop(1).headOption

  // --- reconcile one tracked issue ---

  def escalate(t: TrackedIssue, why: String): IO[TrackedIssue] =
    for
      _ <- IO.println(s"[needs-human] #${t.issue}: $why")
      _ <- gh(List("issue", "comment", t.issue.toString, "--repo", GithubRepo, "--body",
        s"Orchestrator: $why Leaving this for a human.")).attempt
    yield t.copy(phase = Phase.NeedsHuman)

  def releaseWorker(dispatchId: String): IO[Unit] =
    shOpt(List("orca", "orchestration", "worker-release", "--dispatch", dispatchId, "--json")).void

  def reconcile(t: TrackedIssue, repoId: String, runId: String): IO[TrackedIssue] =
    if !ActivePhases(t.phase) then IO.pure(t)
    else issueState(t.issue).flatMap {
      case "CLOSED" =>
        releaseWorker(t.dispatchId) *> IO.pure(t.copy(phase = Phase.Merged))
      case _ =>
        for
          merged <- prsForIssue("merged", t.issue)
          open <- prsForIssue("open", t.issue)
          result <-
            if merged.nonEmpty then
              IO.println(s"[merged] #${t.issue}") *> releaseWorker(t.dispatchId) *> IO.pure(t.copy(phase = Phase.Merged))
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
          case WaitingOnHuman => escalate(t, "conflict-fix worker is parked on a question only a human can answer.")
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
      IO.println(s"[conflict] #${t.issue} PR #$prNumber conflicting; dispatching rebase (attempt ${t.conflictFixes + 1})") *>
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
            IO.println(s"[evidence] #${t.issue} PR #$prNumber missing TDD evidence; nudging") *>
              redispatch(t, t.agent, spec, s"TDD evidence for issue #${t.issue}", runId)
                .map(newDispatchId => t.copy(dispatchId = newDispatchId, evidenceNudged = true))
          case _ =>
            escalate(t, s"PR #$prNumber still lacks the `$TddEvidenceMarker` section after a nudge.")
        }

  def reconcileNoPr(t: TrackedIssue, repoId: String, runId: String): IO[TrackedIssue] =
    workerStatus(t.dispatchId).flatMap {
      case Running | Unknown => IO.pure(t)
      case Settled =>
        escalate(t, "worker settled without opening a PR; check its output in Orca.")
      case WaitingOnHuman =>
        escalate(t, "worker is parked on a question only a human can answer (agentWait).")
      case Failed(rateLimited) if rateLimited =>
        nextAgent(t.agent) match
          case Some(failover) =>
            IO.println(s"[failover] #${t.issue}: ${t.agent} rate-limited; hot-swapping to $failover") *>
              redispatch(t, failover, promptText + assignmentFor(t), s"Issue #${t.issue}: ${t.title}", runId).map { newDispatchId =>
                t.copy(dispatchId = newDispatchId, agent = failover, attempts = t.attempts + 1)
              }
          case None =>
            escalate(t, s"agent pool exhausted (${AgentPool.mkString(" -> ")}); all rate-limited.")
      case Failed(_) =>
        if t.attempts < MaxAttempts then
          IO.println(s"[retry] #${t.issue}: worker failed; retry ${t.attempts + 1} on ${t.agent}") *>
            redispatch(t, t.agent, promptText + assignmentFor(t), s"Issue #${t.issue}: ${t.title}", runId).map { newDispatchId =>
              t.copy(dispatchId = newDispatchId, attempts = t.attempts + 1)
            }
        else escalate(t, s"worker failed ${t.attempts} times; giving up.")
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

  // Open sub-issues in the step scope that are unblocked, unassigned and
  // unclaimed by a PR but not labelled 'ready' — the only thing standing
  // between them and dispatch is the human's label.
  def awaitingReady(stepLabel: String, claimed: Set[Int]): IO[List[Int]] =
    val Array(owner, name) = GithubRepo.split("/")
    val query = """query($owner:String!,$name:String!){repository(owner:$owner,name:$name){issues(first:100,states:OPEN){nodes{number labels(first:20){nodes{name}} assignees(first:5){totalCount} parent{number} subIssues(first:1){totalCount}}}}}"""
    gh(List("api", "graphql", "-f", s"owner=$owner", "-f", s"name=$name", "-f", s"query=$query"))
      .map(ujson.read(_))
      .flatMap { json =>
        val nodes = json("data")("repository")("issues")("nodes").arr.toList
        val candidates = nodes.filter { i =>
          val labels = i("labels")("nodes").arr.map(_("name").str).toList
          labels.contains(stepLabel) && !labels.contains("ready") &&
            i("parent") != ujson.Null && i("subIssues")("totalCount").num.toInt == 0 &&
            i("assignees")("totalCount").num.toInt == 0 &&
            !claimed(i("number").num.toInt)
        }
        candidates.traverse { i =>
          val n = i("number").num.toInt
          gh(List("api", s"repos/$GithubRepo/issues/$n/dependencies/blocked_by",
              "-q", """[.[] | select(.state == "open")] | length"""))
            .map(count => if count.trim == "0" then Some(n) else None)
            .handleError(_ => None) // report fails closed too: error means not listed
        }.map(_.flatten.sorted)
      }

  def buildActions(tracked: List[TrackedIssue], prs: List[OpenPr], awaiting: List[Int]): List[String] =
    val needsHuman = tracked.filter(_.phase == Phase.NeedsHuman).map(t => s"#${t.issue} needs-human (see issue comment)")
    val docsOnlyWaiting = for
      pr <- prs if pr.docsOnly
      issue <- pr.closes if tracked.exists(t => t.issue == issue && ActivePhases(t.phase))
    yield s"#$issue PR #${pr.number} is docs-only — waiting for human merge"
    val awaitingLine = if awaiting.isEmpty then Nil
      else List(s"${awaiting.map(n => s"#$n").mkString(" ")} unblocked but not labelled 'ready' — label to dispatch")
    (needsHuman ++ docsOnlyWaiting ++ awaitingLine).sorted

  def printStatus(tracked: List[TrackedIssue], freeSlots: Int, actions: List[String]): IO[Unit] =
    val time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    val active = tracked.filter(t => ActivePhases(t.phase))
    val activeDesc = if active.isEmpty then "none"
      else active.map(t => s"#${t.issue} ${t.phase} (${t.agent})").mkString(", ")
    IO.println(s"[tick $time] active: $activeDesc | free: $freeSlots") >>
      actions.traverse(a => IO.println(s"[action-needed] $a")).void

  def notifyMac(actions: List[String]): IO[Unit] =
    val summary = actions.take(3).mkString("; ").replace("\"", "'")
    shOpt(List("osascript", "-e",
      s"""display notification "$summary" with title "Recally orchestrator" sound name "Glass"""")).void

  // --- main loop ---

  def tick(dryRun: Boolean, stepScope: Option[String], runId: String): IO[State] =
    for
      state <- loadState
      repoId <- resolveRepoId
      tracked <-
        if dryRun then IO.pure(state.tracked)
        else state.tracked.traverse(reconcile(_, repoId, runId))
      active = tracked.filter(t => ActivePhases(t.phase))
      freeSlots = WorktreeCap - active.size
      prs <- openPrs
      claimed = prs.flatMap(_.closes).toSet
      candidates <- if freeSlots > 0 then readyIssues else IO.pure(Nil)
      scoped = candidates.filter(c => stepScope.forall(scope => c.labels.contains(scope)))
      fresh = scoped.filterNot(c => tracked.exists(_.issue == c.number)).take(freeSlots)
      dispatched <-
        if dryRun then
          IO.println(s"[dry-run] scope=${stepScope.getOrElse("all")} active=${active.size} free=$freeSlots dispatchable=${fresh.map(_.number).mkString(", ")}")
            .as(tracked)
        else fresh.traverse(c => dispatchIssue(c, AgentPool.head, repoId, runId)).map(tracked ++ _)
      awaiting <- stepScope.fold(IO.pure(List.empty[Int]))(awaitingReady(_, claimed))
      actions = buildActions(dispatched, prs, awaiting)
      _ <- printStatus(dispatched, freeSlots, actions)
      _ <-
        if dryRun then IO.unit
        else
          val changed = actions != state.lastActionNeeded
          (if changed && actions.nonEmpty then notifyMac(actions) else IO.unit) >>
            saveState(State(dispatched, actions))
    yield State(dispatched, actions)

  def loop(stepScope: Option[String], runId: String): IO[Unit] =
    tick(dryRun = false, stepScope, runId) >> IO.sleep(PollInterval) >> loop(stepScope, runId)

  def program(args: List[String]): IO[ExitCode] =
    val dryRun = args.contains("--dry-run")
    val once = args.contains("--once")
    val stepScope = args.find(_.startsWith("--step=")).map(_.stripPrefix("--step="))
    if dryRun then tick(dryRun = true, stepScope, runId = "").void.as(ExitCode.Success)
    else
      // One Run per process; every orca mutation passes --run explicitly because
      // each CLI call is a fresh process with no binding.
      createRun.flatMap { runId =>
        IO.println(s"Orchestrator run: $runId (cap=$WorktreeCap pool=${AgentPool.mkString("->")} poll=$PollInterval scope=${stepScope.getOrElse("all")})") >>
          (if once then tick(dryRun = false, stepScope, runId).void
           else loop(stepScope, runId))
      }.as(ExitCode.Success)

// .sc entry point: the script wrapper main runs top-level statements, so the
// IOApp object above must be invoked explicitly.
import cats.effect.unsafe.implicits.global
sys.exit(Orchestrator.program(args.toList).unsafeRunSync().code)
