package com.sdk.glassessdksample.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R

/**
 * Adapter for displaying conversations in the navigation drawer
 */
class ConversationAdapter(
    conversations: MutableList<Conversation>,
    private val onConversationClick: (Conversation) -> Unit,
    private val onDeleteClick: (Conversation, Int) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder>() {

    /**
     * What is currently on screen, which is not always every conversation: the
     * search box filters this down. It starts as a COPY of the full list rather
     * than a reference to it, so that filtering cannot mutate the caller's list.
     */
    private val visible: MutableList<Conversation> = conversations.toMutableList()

    private var selectedPosition: Int = -1

    /** Replaces the displayed rows (used by the search filter). */
    fun submit(items: List<Conversation>) {
        visible.clear()
        visible.addAll(items)
        selectedPosition = -1
        notifyDataSetChanged()
    }

    class ConversationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tvConversationTitle)
        val menuButton: ImageView = itemView.findViewById(R.id.btnConversationMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ConversationViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val conversation = visible[position]
        
        holder.title.text = conversation.title
        
        // Highlight selected conversation
        holder.itemView.alpha = if (position == selectedPosition) 1.0f else 0.7f
        
        holder.itemView.setOnClickListener {
            val oldPosition = selectedPosition
            selectedPosition = holder.adapterPosition
            notifyItemChanged(oldPosition)
            notifyItemChanged(selectedPosition)
            onConversationClick(conversation)
        }
        
        holder.menuButton.setOnClickListener {
            showDeleteMenu(holder.menuButton, conversation, position)
        }
    }

    override fun getItemCount(): Int = visible.size
    
    private fun showDeleteMenu(view: android.view.View, conversation: Conversation, position: Int) {
        val popup = android.widget.PopupMenu(view.context, view)
        popup.menu.add("Delete chat")
        popup.setOnMenuItemClickListener {
            onDeleteClick(conversation, position)
            true
        }
        popup.show()
    }
    
    /**
     * Update conversation at position
     */
    fun updateConversation(position: Int) {
        if (position in 0 until visible.size) {
            notifyItemChanged(position)
        }
    }
    
    /**
     * Set selected conversation
     */
    fun setSelected(position: Int) {
        val oldPosition = selectedPosition
        selectedPosition = position
        notifyItemChanged(oldPosition)
        notifyItemChanged(selectedPosition)
    }
    
    /**
     * Get current selected conversation
     */
    fun getSelectedConversation(): Conversation? {
        return if (selectedPosition >= 0 && selectedPosition < visible.size) {
            visible[selectedPosition]
        } else null
    }
}
