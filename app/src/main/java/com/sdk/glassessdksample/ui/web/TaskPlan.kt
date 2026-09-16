package com.sdk.glassessdksample.ui.web

import org.json.JSONObject

/**
 * A plan the user has to approve before the browser touches anything.
 *
 * The browser agent used to start navigating the moment it was given a goal,
 * guessing every detail it had not been told — dates, times, passenger counts —
 * and then getting stuck on a page it had reached for the wrong reason. This
 * moves all of that uncertainty to the front, into a conversation, and puts the
 * result in the user's hands before any of it runs.
 */
data class TaskPlan(
    /** One line, spoken first: "Book a Delhi to Jaipur flight on the 20th." */
    val summary: String,
    /** The ordered steps, in plain language the user can judge. */
    val steps: List<String>,
    /** What the agent still assumed rather than asked. Spoken as caveats. */
    val assumptions: List<String>,
    /** Where the agent will stop and hand the phone over (payment, sign-in). */
    val handoffPoints: List<String>,
    /** The goal, rewritten with every gathered answer folded in. */
    val resolvedGoal: String
) {

    /**
     * The plan as the glasses should say it. Deliberately shorter than the
     * on-screen version: a spoken list longer than a few items is not something
     * anyone can hold in their head, and the phone is showing the full thing.
     */
    fun toSpoken(): String {
        val sb = StringBuilder(summary)
        if (steps.isNotEmpty()) {
            sb.append(" Here's the plan: ")
            steps.take(MAX_SPOKEN_STEPS).forEachIndexed { i, step ->
                sb.append(i + 1).append(". ").append(step).append(". ")
            }
            if (steps.size > MAX_SPOKEN_STEPS) {
                sb.append("And ").append(steps.size - MAX_SPOKEN_STEPS)
                    .append(" more steps, all on your phone. ")
            }
        }
        if (handoffPoints.isNotEmpty()) {
            sb.append("I'll hand you the phone for ")
                .append(handoffPoints.joinToString(" and ")).append(". ")
        }
        sb.append("Shall I go ahead?")
        return sb.toString()
    }

    /** The full plan for the phone screen, where length costs nothing. */
    fun toDisplayText(): String {
        val sb = StringBuilder()
        sb.append(summary).append("\n\n")
        steps.forEachIndexed { i, step ->
            sb.append(i + 1).append(". ").append(step).append('\n')
        }
        if (assumptions.isNotEmpty()) {
            sb.append("\nI'm assuming:\n")
            assumptions.forEach { sb.append("• ").append(it).append('\n') }
        }
        if (handoffPoints.isNotEmpty()) {
            sb.append("\nYou'll take over for:\n")
            handoffPoints.forEach { sb.append("• ").append(it).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    companion object {
        /** Beyond this, a spoken plan stops being followable. */
        private const val MAX_SPOKEN_STEPS = 4

        fun fromJson(json: JSONObject): TaskPlan? {
            val summary = json.optString("summary").takeIf { it.isNotBlank() }
                ?: return null
            return TaskPlan(
                summary = summary,
                steps = json.stringList("steps"),
                assumptions = json.stringList("assumptions"),
                handoffPoints = json.stringList("handoff_points"),
                resolvedGoal = json.optString("resolved_goal").ifBlank { summary }
            )
        }

        private fun JSONObject.stringList(key: String): List<String> {
            val arr = optJSONArray(key) ?: return emptyList()
            return (0 until arr.length())
                .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }
    }
}

/**
 * One turn of the requirement-gathering conversation: either a question the
 * agent still needs answered, or the finished plan.
 */
sealed class TaskTurn {

    /** Ask the user this, then come back with their answer. */
    data class Question(val text: String) : TaskTurn()

    /** Everything needed is known; here is the plan to approve. */
    data class Ready(val plan: TaskPlan) : TaskTurn()

    /** The request cannot be turned into a browsable task at all. */
    data class Refused(val reason: String) : TaskTurn()

    companion object {
        fun fromJson(json: JSONObject): TaskTurn? =
            when (json.optString("phase").lowercase().trim()) {
                "question" -> json.optString("question")
                    .takeIf { it.isNotBlank() }
                    ?.let { Question(it) }

                "plan" -> TaskPlan.fromJson(json)?.let { Ready(it) }

                "refuse" -> Refused(
                    json.optString("reason").ifBlank {
                        "I can't do that one in the browser."
                    }
                )

                else -> null
            }
    }
}
