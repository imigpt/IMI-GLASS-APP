package com.sdk.glassessdksample.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R
import java.io.File

/**
 * Adapter for displaying Quick Notes in a RecyclerView
 * Supports image thumbnail display
 */
class QuickNotesAdapter(
    private var notes: List<QuickNote>,
    private val onEdit: (QuickNote) -> Unit,
    private val onDelete: (QuickNote) -> Unit,
    private val onClick: (QuickNote) -> Unit
) : RecyclerView.Adapter<QuickNotesAdapter.NoteViewHolder>() {

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tv_note_title)
        val tvContent: TextView = itemView.findViewById(R.id.tv_note_content)
        val tvTimestamp: TextView = itemView.findViewById(R.id.tv_note_timestamp)
        val tvSource: TextView = itemView.findViewById(R.id.tv_note_source)
        val btnEdit: TextView = itemView.findViewById(R.id.btn_edit_note)
        val btnDelete: TextView = itemView.findViewById(R.id.btn_delete_note)
        val cardNote: View = itemView.findViewById(R.id.card_note)
        val cardNoteImage: CardView = itemView.findViewById(R.id.card_note_image)
        val ivNoteThumbnail: ImageView = itemView.findViewById(R.id.iv_note_thumbnail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick_note, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        val note = notes[position]
        
        holder.tvTitle.text = note.title
        holder.tvContent.text = note.content
        holder.tvTimestamp.text = note.getFormattedTimestamp()
        
        // Show image thumbnail if available
        if (note.hasImage()) {
            val imageFile = File(note.imagePath!!)
            if (imageFile.exists()) {
                try {
                    // Load a scaled-down version for the thumbnail
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 4  // Scale down to 1/4 size for memory efficiency
                    }
                    val bitmap = BitmapFactory.decodeFile(note.imagePath, options)
                    if (bitmap != null) {
                        holder.ivNoteThumbnail.setImageBitmap(bitmap)
                        holder.cardNoteImage.visibility = View.VISIBLE
                    } else {
                        holder.cardNoteImage.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    holder.cardNoteImage.visibility = View.GONE
                }
            } else {
                holder.cardNoteImage.visibility = View.GONE
            }
        } else {
            holder.cardNoteImage.visibility = View.GONE
        }
        
        // Show source badge (AI or USER)
        if (note.createdBy == QuickNote.CreatedBy.AI) {
            holder.tvSource.text = "AI"
            holder.tvSource.visibility = View.VISIBLE
        } else {
            holder.tvSource.visibility = View.GONE
        }
        
        // Click handlers
        holder.cardNote.setOnClickListener { onClick(note) }
        holder.btnEdit.setOnClickListener { onEdit(note) }
        holder.btnDelete.setOnClickListener { onDelete(note) }
    }

    override fun getItemCount(): Int = notes.size
    
    fun updateNotes(newNotes: List<QuickNote>) {
        notes = newNotes
        notifyDataSetChanged()
    }
}
