package dev.recally.domain.review

/**
 * Same-session relearning timer (ADR-005; docs/android.md, "Same-session
 * relearning"). FSRS learning steps are minutes long, so a card rated Again
 * or Hard comes back inside the session: the client re-queues it after the
 * step interval from `learning_steps_minutes` (or at the end of the queue if
 * the session is shorter).
 *
 * The step is seeded from the card's server-side `step` in
 * `GET /reviews/due` — a card already at step 1 waits the step-1 interval,
 * it does not restart at step 0. Offline there is no rate response, so the
 * client advances its own in-session counter by one step per re-queue and
 * stops re-queueing past the last entry. This is a display timer for *when
 * to show the card again*, nothing more: the client never runs FSRS, the
 * server owns the real state.
 */
class LearningStepRequeuer(
    private val learningStepsMinutes: List<Int>,
) {
    /**
     * The re-queue decision after a rating, or null when the card is not
     * re-queued this session (Good/Easy, no steps configured, or the counter
     * is already past the last step).
     */
    fun decide(
        currentStep: Int?,
        rating: Int,
    ): RequeueDecision? {
        if (rating != RATING_AGAIN && rating != RATING_HARD) return null
        // A card in `review` (step null) re-enters at the first step.
        val step = currentStep ?: 0
        if (step !in learningStepsMinutes.indices) return null
        return RequeueDecision(delayMinutes = learningStepsMinutes[step], nextStep = step + 1)
    }

    data class RequeueDecision(
        val delayMinutes: Int,
        val nextStep: Int,
    )

    companion object {
        /** `rating` values from docs/api-spec.md (`py-fsrs`): 1=Again, 2=Hard, 3=Good, 4=Easy. */
        const val RATING_AGAIN = 1
        const val RATING_HARD = 2
    }
}
