package nl.bert.faceid

object Matcher {

    /**
     * How much the best person must beat the second-best person by before the
     * app is willing to say a name.
     *
     * Without this rule the most annoying failure mode appears: two people who
     * look somewhat alike both score just above the threshold, and the app
     * confidently says the wrong name. Saying nothing is better than that.
     */
    const val RUNNER_UP_MARGIN = 0.05f

    data class Result(
        val name: String?,
        val score: Float,
        val runnerUpName: String?,
        val runnerUpScore: Float
    ) {
        val recognised: Boolean get() = name != null
    }

    /** Both vectors are already length 1, so the dot product is the cosine. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    /**
     * Compares [query] against everyone in [people] and returns the best match,
     * or a Result with a null name when nothing is confident enough.
     *
     * A person with several enrolment photos scores as their *best* photo, not
     * their average. Averaging punishes you for adding a photo taken in bad
     * light; taking the best rewards you for adding more angles.
     */
    fun match(query: FloatArray, people: List<Person>, threshold: Float): Result {
        var bestName: String? = null
        var bestScore = -1f
        var secondName: String? = null
        var secondScore = -1f

        for (person in people) {
            var personBest = -1f
            for (vector in person.vectors) {
                val s = cosine(query, vector)
                if (s > personBest) personBest = s
            }
            if (personBest > bestScore) {
                secondName = bestName
                secondScore = bestScore
                bestName = person.name
                bestScore = personBest
            } else if (personBest > secondScore) {
                secondName = person.name
                secondScore = personBest
            }
        }

        val confident = bestScore >= threshold &&
            (secondScore < 0f || bestScore - secondScore >= RUNNER_UP_MARGIN)

        return Result(
            name = if (confident) bestName else null,
            score = bestScore,
            runnerUpName = secondName,
            runnerUpScore = secondScore
        )
    }
}
