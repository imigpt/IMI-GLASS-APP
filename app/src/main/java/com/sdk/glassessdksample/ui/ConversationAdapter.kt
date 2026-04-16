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
    private val conversations: MutableList<Conversation>,
    private val onConversationClick: (Conversation) -> Unit,
    private val onDeleteClick: (Conversation, Int) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder>() {

    private var selectedPosition: Int = -1

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
        val conversation = conversations[position]
        
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

    override fun getItemCount(): Int = conversations.size
    
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
        if (position in 0 until conversations.size) {
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
        return if (selectedPosition >= 0 && selectedPosition < conversations.size) {
            conversations[selectedPosition]
        } else null
    }
}
