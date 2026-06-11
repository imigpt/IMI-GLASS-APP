package com.sdk.glassessdksample.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.R

/**
 * Full-screen editor for creating and editing a Quick Note (mockup screen 1).
 * Text-only: a title field and a multi-line body. The note is saved automatically
 * when the user navigates back, as long as there is some content.
 */
class NoteEditorActivity : AppCompatActivity() {

    private lateinit var notesManager: QuickNotesManager
    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText

    private var existingNoteId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)

        notesManager = QuickNotesManager(this)

        etTitle = findViewById(R.id.et_title)
        etContent = findViewById(R.id.et_content)

        existingNoteId = intent.getStringExtra(EXTRA_NOTE_ID)
        if (existingNoteId != null) {
            etTitle.setText(intent.getStringExtra(EXTRA_NOTE_TITLE) ?: "")
            etContent.setText(intent.getStringExtra(EXTRA_NOTE_CONTENT) ?: "")
        }

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { saveAndFinish() }
        findViewById<ImageView>(R.id.btn_share).setOnClickListener { shareNote() }
    }

    private fun shareNote() {
        val title = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()
        if (title.isEmpty() && content.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }
        val text = if (title.isEmpty()) content else "$title\n\n$content"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share note"))
    }

    private fun saveAndFinish() {
        val titleRaw = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()

        // Nothing entered -> just leave without saving
        if (titleRaw.isEmpty() && content.isEmpty()) {
            finish()
            return
        }

        val title = titleRaw.ifEmpty { content.take(40) }

        if (existingNoteId != null) {
            notesManager.updateNote(existingNoteId!!, title, content)
            Toast.makeText(this, "Note updated", Toast.LENGTH_SHORT).show()
        } else {
            notesManager.createNote(title, content, QuickNote.CreatedBy.USER)
            Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        saveAndFinish()
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_NOTE_TITLE = "note_title"
        const val EXTRA_NOTE_CONTENT = "note_content"
    }
}
