package com.sdk.glassessdksample.ui

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manager for storing and retrieving user memory/preferences.
 * Uses SharedPreferences for persistent storage.
 *
 * KEY BEHAVIOUR:
 *  - Automatically learns from every conversation (name, location, occupation,
 *    likes, dislikes, interests) via learnFromUserMessage().
 *  - Tracks total conversations and total messages automatically via
 *    incrementMessageStats().
 *  - No manual data-entry required.
 */
class UserMemoryManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("user_memory_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val TAG = "UserMemoryManager"

        private const val KEY_USER_NAME            = "user_name"
        private const val KEY_OCCUPATION           = "occupation"
        private const val KEY_LOCATION             = "location"
        private const val KEY_LIKES                = "likes"
        private const val KEY_DISLIKES             = "dislikes"
        private const val KEY_INTERESTS            = "interests"
        private const val KEY_RESPONSE_STYLE       = "response_style"
        private const val KEY_PREFERRED_LANGUAGE   = "preferred_language"
        private const val KEY_IMPORTANT_NOTES      = "important_notes"
        private const val KEY_AUTO_LEARNED_FACTS   = "auto_learned_facts"
        private const val KEY_CONVERSATION_COUNT   = "conversation_count"
        private const val KEY_TOTAL_MESSAGES       = "total_messages"
        private const val KEY_LAST_UPDATED         = "last_updated"

        private val NAME_PATTERNS = listOf(
            Regex("""my name is ([A-Za-z]+)""", RegexOption.IGNORE_CASE),
            Regex("""call me ([A-Za-z]+)""",    RegexOption.IGNORE_CASE),
            Regex("""you can call me ([A-Za-z]+)""", RegexOption.IGNORE_CASE),
            Regex("""i go by ([A-Za-z]+)""",    RegexOption.IGNORE_CASE)
        )
        private val OCCUPATION_PATTERNS = listOf(
            Regex("""i(?:'m| am) (?:a|an) ([A-Za-z ]{3,30})""",    RegexOption.IGNORE_CASE),
            Regex("""i work as (?:a|an) ([A-Za-z ]{3,30})""",       RegexOption.IGNORE_CASE),
            Regex("""my (?:job|occupation|profession) is ([A-Za-z ]{3,30})""", RegexOption.IGNORE_CASE)
        )
        private val LOCATION_PATTERNS = listOf(
            Regex("""i(?:'m| am) from ([A-Za-z ]{2,30})""",     RegexOption.IGNORE_CASE),
            Regex("""i live in ([A-Za-z ]{2,30})""",             RegexOption.IGNORE_CASE),
            Regex("""i(?:'m| am) in ([A-Za-z ]{2,30})""",       RegexOption.IGNORE_CASE),
            Regex("""i(?:'m| am) based in ([A-Za-z ]{2,30})""", RegexOption.IGNORE_CASE)
        )
        private val LIKE_PATTERNS = listOf(
            Regex("""i (?:really )?like ([^,.!?]{3,40})""",   RegexOption.IGNORE_CASE),
            Regex("""i (?:really )?love ([^,.!?]{3,40})""",   RegexOption.IGNORE_CASE),
            Regex("""i enjoy ([^,.!?]{3,40})""",               RegexOption.IGNORE_CASE),
            Regex("""i(?:'m| am) into ([^,.!?]{3,40})""",     RegexOption.IGNORE_CASE),
            Regex("""i(?:'m| am) a fan of ([^,.!?]{3,40})""", RegexOption.IGNORE_CASE)
        )
        private val DISLIKE_PATTERNS = listOf(
            Regex("""i (?:don't|do not|dont) like ([^,.!?]{3,40})""", RegexOption.IGNORE_CASE),
            Regex("""i (?:really )?hate ([^,.!?]{3,40})""",           RegexOption.IGNORE_CASE),
            Regex("""i dislike ([^,.!?]{3,40})""",                     RegexOption.IGNORE_CASE),
            Regex("""i(?:'m| am) not (?:a fan of|into|fond of) ([^,.!?]{3,40})""", RegexOption.IGNORE_CASE)
        )
        private val INTEREST_PATTERNS = listOf(
            Regex("""i(?:'m| am) interested in ([^,.!?]{3,40})""",  RegexOption.IGNORE_CASE),
            Regex("""my hobby is ([^,.!?]{3,40})""",                 RegexOption.IGNORE_CASE),
            Regex("""my hobbies (?:are|include) ([^,.!?]{3,60})""", RegexOption.IGNORE_CASE),
            Regex("""i spend (?:my )?time (?:on|with) ([^,.!?]{3,40})""", RegexOption.IGNORE_CASE)
        )
        private val STOP_WORDS = setOf(
            "not", "okay", "sure", "here", "there", "good", "fine", "just",
            "really", "very", "trying", "going", "doing", "looking", "thinking",
            "happy", "sad", "tired", "busy", "ready", "available", "able", "sorry"
        )
    }

    // -----------------------------------------------------------------------
    //  Save / Load
    // -----------------------------------------------------------------------

    fun saveUserMemory(memory: UserMemory) {
        try {
            prefs.edit().apply {
                putString(KEY_USER_NAME,          memory.userName)
                putString(KEY_OCCUPATION,         memory.occupation)
                putString(KEY_LOCATION,           memory.location)
                putString(KEY_LIKES,              gson.toJson(memory.likes))
                putString(KEY_DISLIKES,           gson.toJson(memory.dislikes))
                putString(KEY_INTERESTS,          gson.toJson(memory.interests))
                putString(KEY_RESPONSE_STYLE,     memory.preferredResponseStyle.name)
                putString(KEY_PREFERRED_LANGUAGE, memory.preferredLanguage)
                putString(KEY_IMPORTANT_NOTES,    gson.toJson(memory.importantNotes))
                putString(KEY_AUTO_LEARNED_FACTS, gson.toJson(memory.autoLearnedFacts))
                putInt(KEY_CONVERSATION_COUNT,    memory.conversationCount)
                putInt(KEY_TOTAL_MESSAGES,        memory.totalMessages)
                putLong(KEY_LAST_UPDATED,         memory.lastUpdated)
                apply()
            }
            Log.d(TAG, "User memory saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving user memory", e)
        }
    }

    fun getUserMemory(): UserMemory {
        return try {
            UserMemory(
                userName          = prefs.getString(KEY_USER_NAME, "") ?: "",
                occupation        = prefs.getString(KEY_OCCUPATION, "") ?: "",
                location          = prefs.getString(KEY_LOCATION, "") ?: "",
                likes             = deserializeStringList(prefs.getString(KEY_LIKES, null)),
                dislikes          = deserializeStringList(prefs.getString(KEY_DISLIKES, null)),
                interests         = deserializeStringList(prefs.getString(KEY_INTERESTS, null)),
                preferredResponseStyle = UserMemory.ResponseStyle.valueOf(
                    prefs.getString(KEY_RESPONSE_STYLE, UserMemory.ResponseStyle.BALANCED.name)
                        ?: UserMemory.ResponseStyle.BALANCED.name
                ),
                preferredLanguage = prefs.getString(KEY_PREFERRED_LANGUAGE, "English") ?: "English",
                importantNotes    = deserializeStringList(prefs.getString(KEY_IMPORTANT_NOTES, null)),
                autoLearnedFacts  = deserializeStringList(prefs.getString(KEY_AUTO_LEARNED_FACTS, null)),
                conversationCount = prefs.getInt(KEY_CONVERSATION_COUNT, 0),
                totalMessages     = prefs.getInt(KEY_TOTAL_MESSAGES, 0),
                lastUpdated       = prefs.getLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user memory, returning defaults", e)
            UserMemory()
        }
    }

    // -----------------------------------------------------------------------
    //  AUTO-LEARNING
    // -----------------------------------------------------------------------

    fun learnFromUserMessage(userMessage: String) {
        if (userMessage.isBlank()) return
        val memory = getUserMemory()
        var updated = memory

        if (updated.userName.isBlank()) {
            for (pattern in NAME_PATTERNS) {
                val match = pattern.find(userMessage)?.groupValues?.getOrNull(1)?.trim()
                if (match != null && match.lowercase() !in STOP_WORDS && match.length >= 2) {
                    updated = updated.copy(userName = match.replaceFirstChar { it.uppercase() })
                    Log.d(TAG, "Auto-learned name: ${updated.userName}")
                    break
                }
            }
        }

        if (updated.occupation.isBlank()) {
            for (pattern in OCCUPATION_PATTERNS) {
                val match = pattern.find(userMessage)?.groupValues?.getOrNull(1)?.trim()
                if (match != null && match.lowercase() !in STOP_WORDS && match.length >= 3) {
                    updated = updated.copy(occupation = match.replaceFirstChar { it.uppercase() })
                    Log.d(TAG, "Auto-learned occupation: ${updated.occupation}")
                    break
                }
            }
        }

        if (updated.location.isBlank()) {
            for (pattern in LOCATION_PATTERNS) {
                val match = pattern.find(userMessage)?.groupValues?.getOrNull(1)?.trim()
                if (match != null && match.lowercase() !in STOP_WORDS && match.length >= 2) {
                    updated = updated.copy(location = match.replaceFirstChar { it.uppercase() })
                    Log.d(TAG, "Auto-learned location: ${updated.location}")
                    break
                }
            }
        }

        for (pattern in LIKE_PATTERNS) {
            val match = pattern.find(userMessage)?.groupValues?.getOrNull(1)?.trim()
                ?.removeSuffix(" a lot")?.removeSuffix(" so much")?.trim()
            if (match != null && match.length >= 3 && match.lowercase() !in STOP_WORDS) {
                val likes = updated.likes.toMutableList()
                if (!likes.any { it.equals(match, ignoreCase = true) }) {
                    likes.add(match.replaceFirstChar { it.uppercase() })
                    updated = updated.copy(likes = likes)
                }
            }
        }

        for (pattern in DISLIKE_PATTERNS) {
            val match = pattern.find(userMessage)?.groupValues?.getOrNull(1)?.trim()
            if (match != null && match.length >= 3 && match.lowercase() !in STOP_WORDS) {
                val dislikes = updated.dislikes.toMutableList()
                if (!dislikes.any { it.equals(match, ignoreCase = true) }) {
                    dislikes.add(match.replaceFirstChar { it.uppercase() })
                    updated = updated.copy(dislikes = dislikes)
                }
            }
        }

        for (pattern in INTEREST_PATTERNS) {
            val match = pattern.find(userMessage)?.groupValues?.getOrNull(1)?.trim()
            if (match != null && match.length >= 3 && match.lowercase() !in STOP_WORDS) {
                val interests = updated.interests.toMutableList()
                if (!interests.any { it.equals(match, ignoreCase = true) }) {
                    interests.add(match.replaceFirstChar { it.uppercase() })
                    updated = updated.copy(interests = interests)
                }
            }
        }

        if (updated != memory) {
            saveUserMemory(updated.copy(lastUpdated = System.currentTimeMillis()))
        }
    }

    fun incrementMessageStats(isNewConversation: Boolean = false) {
        val memory = getUserMemory()
        saveUserMemory(memory.copy(
            conversationCount = if (isNewConversation) memory.conversationCount + 1 else memory.conversationCount,
            totalMessages     = memory.totalMessages + 1,
            lastUpdated       = System.currentTimeMillis()
        ))
    }

    fun addAutoLearnedFact(fact: String) {
        if (fact.isBlank()) return
        val memory = getUserMemory()
        val facts  = memory.autoLearnedFacts.toMutableList()
        if (!facts.any { it.equals(fact, ignoreCase = true) }) {
            facts.add(fact)
            val trimmed = if (facts.size > 50) facts.takeLast(50) else facts
            saveUserMemory(memory.copy(autoLearnedFacts = trimmed, lastUpdated = System.currentTimeMillis()))
            Log.d(TAG, "Auto fact added: $fact")
        }
    }

    // -----------------------------------------------------------------------
    //  Manual setters (used by UserMemoryActivity)
    // -----------------------------------------------------------------------

    fun setUserName(name: String) =
        saveUserMemory(getUserMemory().copy(userName = name, lastUpdated = System.currentTimeMillis()))

    fun setOccupation(occupation: String) =
        saveUserMemory(getUserMemory().copy(occupation = occupation, lastUpdated = System.currentTimeMillis()))

    fun setLocation(location: String) =
        saveUserMemory(getUserMemory().copy(location = location, lastUpdated = System.currentTimeMillis()))

    fun addLike(like: String) {
        val memory = getUserMemory()
        val list = memory.likes.toMutableList()
        if (!list.contains(like)) {
            list.add(like)
            saveUserMemory(memory.copy(likes = list, lastUpdated = System.currentTimeMillis()))
        }
    }

    fun removeLike(like: String) {
        val memory = getUserMemory()
        val list = memory.likes.toMutableList()
        list.remove(like)
        saveUserMemory(memory.copy(likes = list, lastUpdated = System.currentTimeMillis()))
    }

    fun addDislike(dislike: String) {
        val memory = getUserMemory()
        val list = memory.dislikes.toMutableList()
        if (!list.contains(dislike)) {
            list.add(dislike)
            saveUserMemory(memory.copy(dislikes = list, lastUpdated = System.currentTimeMillis()))
        }
    }

    fun removeDislike(dislike: String) {
        val memory = getUserMemory()
        val list = memory.dislikes.toMutableList()
        list.remove(dislike)
        saveUserMemory(memory.copy(dislikes = list, lastUpdated = System.currentTimeMillis()))
    }

    fun addInterest(interest: String) {
        val memory = getUserMemory()
        val list = memory.interests.toMutableList()
        if (!list.contains(interest)) {
            list.add(interest)
            saveUserMemory(memory.copy(interests = list, lastUpdated = System.currentTimeMillis()))
        }
    }

    fun removeInterest(interest: String) {
        val memory = getUserMemory()
        val list = memory.interests.toMutableList()
        list.remove(interest)
        saveUserMemory(memory.copy(interests = list, lastUpdated = System.currentTimeMillis()))
    }

    fun setResponseStyle(style: UserMemory.ResponseStyle) =
        saveUserMemory(getUserMemory().copy(preferredResponseStyle = style, lastUpdated = System.currentTimeMillis()))

    fun setPreferredLanguage(language: String) =
        saveUserMemory(getUserMemory().copy(preferredLanguage = language, lastUpdated = System.currentTimeMillis()))

    fun addNote(note: String) {
        val memory = getUserMemory()
        val list = memory.importantNotes.toMutableList()
        if (!list.contains(note)) {
            list.add(note)
            saveUserMemory(memory.copy(importantNotes = list, lastUpdated = System.currentTimeMillis()))
        }
    }

    fun removeNote(note: String) {
        val memory = getUserMemory()
        val list = memory.importantNotes.toMutableList()
        list.remove(note)
        saveUserMemory(memory.copy(importantNotes = list, lastUpdated = System.currentTimeMillis()))
    }

    // -----------------------------------------------------------------------
    //  Utility
    // -----------------------------------------------------------------------

    fun clearAll() {
        prefs.edit().clear().apply()
        Log.d(TAG, "User memory cleared")
    }

    fun hasMemory(): Boolean = !getUserMemory().isEmpty()

    private fun deserializeStringList(json: String?): List<String> {
        return try {
            if (json.isNullOrBlank()) emptyList()
            else {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing list", e)
            emptyList()
        }
    }
}
