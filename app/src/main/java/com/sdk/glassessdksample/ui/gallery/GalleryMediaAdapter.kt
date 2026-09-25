package com.sdk.glassessdksample.ui.gallery

import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.ui.wifi.GlassMediaTransfer.MediaFileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What kind of media a file is, decided from its type field and extension. */
enum class GalleryMediaKind { IMAGE, VIDEO, RECORDING }

fun MediaFileInfo.kind(): GalleryMediaKind {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when {
        fileType == "audio" || ext in AUDIO_EXTENSIONS -> GalleryMediaKind.RECORDING
        fileType == "video" || ext in VIDEO_EXTENSIONS -> GalleryMediaKind.VIDEO
        else -> GalleryMediaKind.IMAGE
    }
}

val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm")
val AUDIO_EXTENSIONS = setOf("opus", "wav", "m4a", "aac", "mp3", "amr", "ogg", "pcm")

/** A row in the gallery grid: a full-width day header or a media tile. */
sealed class GalleryRow {
    data class Header(val title: String, val count: Int) : GalleryRow()
    data class Tile(val item: MediaFileInfo) : GalleryRow()
}

/**
 * Grid adapter for the glass media gallery. Tiles show a type badge, the video
 * length, a selection check and — in Download mode — a "Saved" pill for files
 * that are already on the phone. Thumbnails load off the main thread via Glide.
 */
class GalleryMediaAdapter(
    private val scope: CoroutineScope,
    private val onTap: (MediaFileInfo) -> Unit,
    private val onLongPress: (MediaFileInfo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<GalleryRow> = emptyList()
    private var selected: Set<String> = emptySet()
    private var selectionEnabled = false
    private var showSavedBadge = false

    /** Video durations are costly to read, so each file is measured once. */
    private val durationCache = LruCache<String, String>(500)

    fun submit(
        rows: List<GalleryRow>,
        selected: Set<String>,
        selectionEnabled: Boolean,
        showSavedBadge: Boolean,
    ) {
        this.rows = rows
        this.selected = selected.toSet()
        this.selectionEnabled = selectionEnabled
        this.showSavedBadge = showSavedBadge
        notifyDataSetChanged()
    }

    /** Headers take the full row; tiles take one column. */
    fun spanSizeLookup(spanCount: Int) = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) =
            if (rows.getOrNull(position) is GalleryRow.Header) spanCount else 1
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) =
        if (rows[position] is GalleryRow.Header) TYPE_HEADER else TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_gallery_section_header, parent, false))
        } else {
            TileHolder(inflater.inflate(R.layout.item_gallery_photo, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is GalleryRow.Header -> (holder as HeaderHolder).bind(row)
            is GalleryRow.Tile -> (holder as TileHolder).bind(row.item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TileHolder) Glide.with(holder.image).clear(holder.image)
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.tvSectionTitle)
        private val count: TextView = view.findViewById(R.id.tvSectionCount)

        fun bind(row: GalleryRow.Header) {
            title.text = row.title
            count.text = "${row.count} item${if (row.count == 1) "" else "s"}"
        }
    }

    private inner class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.imageView)
        private val placeholder: LinearLayout = view.findViewById(R.id.placeholder)
        private val placeholderIcon: ImageView = view.findViewById(R.id.placeholderIcon)
        private val placeholderName: TextView = view.findViewById(R.id.placeholderName)
        private val typeBadge: ImageView = view.findViewById(R.id.typeBadge)
        private val checkBadge: ImageView = view.findViewById(R.id.checkBadge)
        private val savedBadge: TextView = view.findViewById(R.id.savedBadge)
        private val durationBadge: TextView = view.findViewById(R.id.durationBadge)
        private val selectedOverlay: View = view.findViewById(R.id.selectedOverlay)

        fun bind(item: MediaFileInfo) {
            val kind = item.kind()
            val iconRes = when (kind) {
                GalleryMediaKind.IMAGE -> R.drawable.ic_gm_image
                GalleryMediaKind.VIDEO -> R.drawable.ic_gm_video
                GalleryMediaKind.RECORDING -> R.drawable.ic_gm_audio
            }
            typeBadge.setImageResource(iconRes)

            // Preview: only possible once the file is on the phone, and never for audio.
            val file = item.localPath?.let(::File)?.takeIf { it.exists() }
            val hasPreview = file != null && kind != GalleryMediaKind.RECORDING
            Glide.with(image).clear(image)
            if (hasPreview) {
                image.visibility = View.VISIBLE
                placeholder.visibility = View.GONE
                Glide.with(image)
                    .load(file)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .centerCrop()
                    .thumbnail(0.25f)
                    .into(image)
            } else {
                image.visibility = View.INVISIBLE
                placeholder.visibility = View.VISIBLE
                placeholderIcon.setImageResource(iconRes)
                placeholderName.text = item.fileName
            }

            bindDuration(item, kind, file)

            val isSelected = item.fileName in selected
            val isSaved = showSavedBadge && item.isDownloaded
            savedBadge.visibility = if (isSaved) View.VISIBLE else View.GONE
            checkBadge.visibility = if (selectionEnabled && !isSaved) View.VISIBLE else View.GONE
            checkBadge.setBackgroundResource(
                if (isSelected) R.drawable.bg_gm_check_on else R.drawable.bg_gm_check_off
            )
            checkBadge.setImageResource(if (isSelected) R.drawable.ic_gm_check else 0)
            selectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE

            itemView.contentDescription = buildString {
                append(item.fileName)
                if (isSaved) append(", saved")
                if (isSelected) append(", selected")
            }
            itemView.setOnClickListener { onTap(item) }
            itemView.setOnLongClickListener { onLongPress(item); true }
        }

        private fun bindDuration(item: MediaFileInfo, kind: GalleryMediaKind, file: File?) {
            durationBadge.visibility = View.GONE
            if (kind == GalleryMediaKind.IMAGE || file == null) return

            val key = file.absolutePath
            durationCache.get(key)?.let { show(it); return }

            val boundTo = item.fileName
            scope.launch {
                val text = withContext(Dispatchers.IO) { readDuration(file) } ?: return@launch
                durationCache.put(key, text)
                // The holder may have been recycled for another file meanwhile.
                val current = (rows.getOrNull(adapterPosition) as? GalleryRow.Tile)?.item
                if (current?.fileName == boundTo) show(text)
            }
        }

        private fun show(text: String) {
            durationBadge.text = text
            durationBadge.visibility = View.VISIBLE
        }
    }

    private fun readDuration(file: File): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            val totalSec = ms / 1000
            "%02d:%02d".format(totalSec / 60, totalSec % 60)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TILE = 1
    }
}
