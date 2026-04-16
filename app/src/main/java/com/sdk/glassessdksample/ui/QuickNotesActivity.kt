package com.sdk.glassessdksample.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.sdk.glassessdksample.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quick Notes Activity - Display and manage user notes and AI reminders
 * Supports text notes with optional photo attachments
 */
class QuickNotesActivity : AppCompatActivity() {

    private lateinit var notesManager: QuickNotesManager
    private lateinit var notesAdapter: QuickNotesAdapter
    private lateinit var rvNotes: RecyclerView
    private lateinit var fabAddNote: FloatingActionButton
    private lateinit var layoutEmptyState: LinearLayout
    
    private val TAG = "QuickNotesActivity"
    
    // Camera capture state
    private var currentPhotoPath: String? = null
    private var pendingImagePath: String? = null
    private var activeDialog: AlertDialog? = null
    
    // Camera launcher
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentPhotoPath != null) {
            Log.d(TAG, "Photo captured: $currentPhotoPath")
            pendingImagePath = currentPhotoPath
            updateDialogImagePreview()
        } else {
            Log.d(TAG, "Photo capture cancelled or failed")
        }
    }
    
    // Gallery picker launcher
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            Log.d(TAG, "Image picked from gallery: $uri")
            // Copy the image to our internal storage
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    val tempId = System.currentTimeMillis().toString()
                    val savedPath = notesManager.saveNoteImage(bitmap, tempId)
                    pendingImagePath = savedPath
                    updateDialogImagePreview()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load gallery image: ${e.message}", e)
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // Camera permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(this, "Camera permission required to take photos", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_notes)

        notesManager = QuickNotesManager(this)
        setupToolbar()
        setupUI()
        loadNotes()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupUI() {
        rvNotes = findViewById(R.id.rv_notes)
        fabAddNote = findViewById(R.id.fab_add_note)
        layoutEmptyState = findViewById(R.id.layout_empty_state)

        // Setup RecyclerView
        notesAdapter = QuickNotesAdapter(
            notes = emptyList(),
            onEdit = { note -> showEditNoteDialog(note) },
            onDelete = { note -> confirmDeleteNote(note) },
            onClick = { note -> showNoteDetails(note) }
        )
        
        rvNotes.apply {
            layoutManager = LinearLayoutManager(this@QuickNotesActivity)
            adapter = notesAdapter
        }

        // FAB to add new note
        fabAddNote.setOnClickListener {
            showEditNoteDialog(null)
        }
    }

    private fun loadNotes() {
        val notes = notesManager.getAllNotes()
        notesAdapter.updateNotes(notes)
        
        // Show/hide empty state
        if (notes.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            rvNotes.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            rvNotes.visibility = View.VISIBLE
        }
    }
    
    /**
     * Create a temporary file for camera capture
     */
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("NOTE_${timeStamp}_", ".jpg", storageDir)
    }
    
    /**
     * Launch the camera to take a photo
     */
    private fun launchCamera() {
        try {
            val photoFile = createImageFile()
            currentPhotoPath = photoFile.absolutePath
            val photoUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                photoFile
            )
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch camera: ${e.message}", e)
            Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Request camera permission and launch camera
     */
    private fun requestCameraAndCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    /**
     * Update the image preview in the currently active edit dialog
     */
    private fun updateDialogImagePreview() {
        val dialog = activeDialog ?: return
        val cardPreview = dialog.findViewById<CardView>(R.id.card_image_preview) ?: return
        val ivPreview = dialog.findViewById<ImageView>(R.id.iv_note_image_preview) ?: return
        val layoutAttach = dialog.findViewById<LinearLayout>(R.id.layout_attach_photo) ?: return
        
        if (pendingImagePath != null) {
            try {
                val bitmap = BitmapFactory.decodeFile(pendingImagePath)
                if (bitmap != null) {
                    ivPreview.setImageBitmap(bitmap)
                    cardPreview.visibility = View.VISIBLE
                    layoutAttach.visibility = View.GONE
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load preview: ${e.message}")
            }
        }
        // No image - show attach buttons
        cardPreview.visibility = View.GONE
        layoutAttach.visibility = View.VISIBLE
    }

    private fun showEditNoteDialog(existingNote: QuickNote?) {
        // Reset pending image
        pendingImagePath = existingNote?.imagePath
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_note, null)
        val etTitle = dialogView.findViewById<android.widget.EditText>(R.id.et_note_title)
        val etContent = dialogView.findViewById<android.widget.EditText>(R.id.et_note_content)
        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save)
        val btnTakePhoto = dialogView.findViewById<TextView>(R.id.btn_take_photo)
        val btnPickGallery = dialogView.findViewById<TextView>(R.id.btn_pick_gallery)
        val cardImagePreview = dialogView.findViewById<CardView>(R.id.card_image_preview)
        val ivImagePreview = dialogView.findViewById<ImageView>(R.id.iv_note_image_preview)
        val btnRemoveImage = dialogView.findViewById<ImageView>(R.id.btn_remove_image)
        val layoutAttachPhoto = dialogView.findViewById<LinearLayout>(R.id.layout_attach_photo)

        // Pre-fill if editing
        if (existingNote != null) {
            tvDialogTitle.text = "Edit Note"
            etTitle.setText(existingNote.title)
            etContent.setText(existingNote.content)
            
            // Show existing image if any
            if (existingNote.hasImage()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(existingNote.imagePath)
                    if (bitmap != null) {
                        ivImagePreview.setImageBitmap(bitmap)
                        cardImagePreview.visibility = View.VISIBLE
                        layoutAttachPhoto.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load existing image: ${e.message}")
                }
            }
        } else {
            tvDialogTitle.text = "New Note"
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        activeDialog = dialog
        
        // Take photo button
        btnTakePhoto.setOnClickListener {
            requestCameraAndCapture()
        }
        
        // Pick from gallery button
        btnPickGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        
        // Remove image button
        btnRemoveImage.setOnClickListener {
            pendingImagePath = null
            cardImagePreview.visibility = View.GONE
            layoutAttachPhoto.visibility = View.VISIBLE
        }

        btnCancel.setOnClickListener { 
            activeDialog = null
            dialog.dismiss() 
        }
        
        btnSave.setOnClickListener {
            val title = etTitle.text.toString().trim()
            val content = etContent.text.toString().trim()

            when {
                title.isEmpty() -> {
                    Toast.makeText(this, "Please enter a title", Toast.LENGTH_SHORT).show()
                }
                content.isEmpty() && pendingImagePath == null -> {
                    Toast.makeText(this, "Please enter some content or attach a photo", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val finalContent = if (content.isEmpty()) "Photo note" else content
                    
                    if (existingNote != null) {
                        // Update existing note with image
                        notesManager.updateNoteWithImage(existingNote.id, title, finalContent, pendingImagePath)
                        Toast.makeText(this, "Note updated", Toast.LENGTH_SHORT).show()
                    } else {
                        // Create new note with optional image
                        notesManager.createNoteWithImage(title, finalContent, pendingImagePath, QuickNote.CreatedBy.USER)
                        Toast.makeText(this, "Note created", Toast.LENGTH_SHORT).show()
                    }
                    loadNotes()
                    activeDialog = null
                    dialog.dismiss()
                }
            }
        }
        
        dialog.setOnDismissListener { activeDialog = null }
        dialog.show()
    }

    private fun confirmDeleteNote(note: QuickNote) {
        AlertDialog.Builder(this)
            .setTitle("Delete Note")
            .setMessage("Are you sure you want to delete \"${note.title}\"?")
            .setPositiveButton("Delete") { _, _ ->
                notesManager.deleteNote(note.id)
                Toast.makeText(this, "Note deleted", Toast.LENGTH_SHORT).show()
                loadNotes()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNoteDetails(note: QuickNote) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_note_detail, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_detail_title)
        val tvContent = dialogView.findViewById<TextView>(R.id.tv_detail_content)
        val tvTimestamp = dialogView.findViewById<TextView>(R.id.tv_detail_timestamp)
        val tvSource = dialogView.findViewById<TextView>(R.id.tv_detail_source)
        val cardImage = dialogView.findViewById<CardView>(R.id.card_detail_image)
        val ivImage = dialogView.findViewById<ImageView>(R.id.iv_detail_image)
        val btnClose = dialogView.findViewById<Button>(R.id.btn_detail_close)
        val btnEdit = dialogView.findViewById<Button>(R.id.btn_detail_edit)
        
        tvTitle.text = note.title
        tvContent.text = note.content
        tvTimestamp.text = note.getFormattedTimestamp()
        tvSource.text = if (note.createdBy == QuickNote.CreatedBy.AI) "Created by AI" else ""
        tvSource.visibility = if (note.createdBy == QuickNote.CreatedBy.AI) View.VISIBLE else View.GONE
        
        // Show image if available
        if (note.hasImage()) {
            val imageFile = File(note.imagePath!!)
            if (imageFile.exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(note.imagePath)
                    if (bitmap != null) {
                        ivImage.setImageBitmap(bitmap)
                        cardImage.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    cardImage.visibility = View.GONE
                }
            } else {
                cardImage.visibility = View.GONE
            }
        } else {
            cardImage.visibility = View.GONE
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        btnClose.setOnClickListener { dialog.dismiss() }
        btnEdit.setOnClickListener {
            dialog.dismiss()
            showEditNoteDialog(note)
        }
        
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        loadNotes() // Refresh notes when returning to this screen
    }
}
