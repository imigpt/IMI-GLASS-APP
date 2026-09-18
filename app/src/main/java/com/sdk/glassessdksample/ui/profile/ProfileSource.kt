package com.sdk.glassessdksample.ui.profile

/**
 * An AI account the user can import their profile from.
 *
 * Everything site-specific lives here rather than being spread through the
 * importer, because these are the parts most likely to break: both products
 * redesign often, and when they do, this is the one file to fix.
 */
enum class ProfileSource(
    val displayName: String,
    /** Where the user signs in. */
    val signInUrl: String,
    /** A fresh conversation, so the import is not appended to an old thread. */
    val newChatUrl: String,
    /** Cookie domain, for the session check and the sign-out. */
    val cookieDomain: String,
    /** Where to send the user to sign out, when clearing cookies is not enough. */
    val logoutUrl: String
) {
    CHATGPT(
        displayName = "ChatGPT",
        signInUrl = "https://chatgpt.com/auth/login",
        // The plain root, not ?temporary-chat=false. A temporary chat is
        // explicitly memory-less, so the query string risked landing on the one
        // variant that can never answer this question — and the root already
        // opens a fresh conversation.
        newChatUrl = "https://chatgpt.com/",
        cookieDomain = "https://chatgpt.com",
        logoutUrl = "https://chatgpt.com/auth/logout"
    ),
    CLAUDE(
        displayName = "Claude",
        signInUrl = "https://claude.ai/login",
        newChatUrl = "https://claude.ai/new",
        cookieDomain = "https://claude.ai",
        logoutUrl = "https://claude.ai/logout"
    );

    /**
     * The prompt asked in the fresh chat.
     *
     * Written to pull from the assistant's MEMORY rather than the current
     * conversation, because a new chat has no history of its own — all it can
     * report is what was saved to memory across previous ones.
     *
     * It asks for prose rather than JSON on purpose: the answer is shown to the
     * user to read and edit, and it becomes background text in another model's
     * system prompt. Both of those want readable sentences.
     *
     * Deliberately plain, and deliberately WITHOUT an escape clause.
     *
     * An earlier version stacked hedges — "based only on what you actually
     * remember", "only where you genuinely know it", "do not guess or invent
     * anything" — and offered NO_MEMORY_AVAILABLE as a way out. Asked that way,
     * ChatGPT replied NO_MEMORY_AVAILABLE on an account that demonstrably had
     * memory: the same account, asked the plain question "what do you know
     * about me", answered with real details. The hedging primed it toward
     * caution, and the escape hatch was the safest thing in reach.
     *
     * So this now asks the way a person would. The risk that motivated the
     * hedges — a model inventing a plausible stranger — is handled after the
     * fact instead: the user reads and edits the profile before it is saved,
     * which catches invention far more reliably than an instruction can.
     */
    val extractionPrompt: String
        get() = """
            What do you know about me? Write it as a short profile I can give to
            another AI assistant so it understands who I am.

            Include whatever you know about my work, what I'm building or focused
            on, where I'm based, and how I like to be talked to.

            Write it as plain prose in the third person, around 150-250 words.
            No headings, no bullet points.
        """.trimIndent()

    companion object {
        /** What the model replies when it has nothing real to report. */
        const val NO_MEMORY_MARKER = "NO_MEMORY_AVAILABLE"
    }
}
