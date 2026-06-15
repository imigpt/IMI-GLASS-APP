package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.databinding.ActivityMoreBinding
import com.sdk.glassessdksample.ui.BottomNavManager
import com.sdk.glassessdksample.ui.ChatActivity
import com.sdk.glassessdksample.ui.ConversationHistoryActivity
import com.sdk.glassessdksample.ui.MeetingMinutesActivity
import com.sdk.glassessdksample.ui.QuickNotesActivity
import com.sdk.glassessdksample.ui.UserMemoryActivity
import com.sdk.glassessdksample.ui.gallery.GlassMediaGalleryActivity
import com.sdk.glassessdksample.ui.gallery.ImageDescriptionVaultActivity
import com.sdk.glassessdksample.ui.gallery.LiveGalleryActivity

class MoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoreBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoreBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupActions()
        BottomNavManager.setup(binding.bottomNavigation, R.id.nav_more, this)
    }

    private fun setupActions() {
        binding.cardCamera.setOnClickListener { open(CameraActivity::class.java) }
        binding.cardQuickNotes.setOnClickListener { open(QuickNotesActivity::class.java) }
        binding.cardMeetingMinutes.setOnClickListener { open(MeetingMinutesActivity::class.java) }
        binding.cardConversationHistory.setOnClickListener { open(ConversationHistoryActivity::class.java) }
        binding.cardChat.setOnClickListener { open(ChatActivity::class.java) }
        binding.cardUserMemory.setOnClickListener { open(UserMemoryActivity::class.java) }
        binding.cardLiveGallery.setOnClickListener { open(LiveGalleryActivity::class.java) }
        binding.cardVisionDescriptions.setOnClickListener { open(ImageDescriptionVaultActivity::class.java) }
        binding.cardGlassGallery.setOnClickListener { open(GlassMediaGalleryActivity::class.java) }
        binding.cardVisionChat.setOnClickListener { open(VisionChatActivity::class.java) }
    }

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }
}
