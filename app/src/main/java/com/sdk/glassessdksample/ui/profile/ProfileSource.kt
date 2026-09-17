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
        newChatUrl = "https://chatgpt.com/?temporary-chat=false",
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
     * The explicit "say you don't have it" clause matters. Without it a model
     * asked "what do you know about me" will cheerfully invent a plausible
     * person, and an invented profile is worse than no profile — it would make
     * the assistant confidently wrong about someone's life.
     */
    val extractionPrompt: String
        get() = """
            Based only on what you actually remember about me from our previous
            conversations and your saved memory, write a short profile of me for
            another AI assistant that has never met me.

            Cover, only where you genuinely know it:
            - who I am: work, role, where I live
            - what I am currently working on or focused on
            - how I prefer to be talked to: tone, length, language
            - anything you have learned that would help an assistant help me

            Write it as plain prose in the third person, about 150-250 words, no
            headings and no bullet points.

            This is important: if you do not actually have memories of me — for
            example if memory is turned off or we have not spoken before — do not
            guess or invent anything. Reply with exactly this instead:
            NO_MEMORY_AVAILABLE
        """.trimIndent()

    companion object {
        /** What the model replies when it has nothing real to report. */
        const val NO_MEMORY_MARKER = "NO_MEMORY_AVAILABLE"
    }
}
