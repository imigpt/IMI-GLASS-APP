package com.sdk.glassessdksample.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.databinding.ItemOnboardingBinding
import java.io.IOException

class OnboardingAdapter(
    private val items: List<OnboardingItem>
) : RecyclerView.Adapter<OnboardingAdapter.OnboardingViewHolder>() {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OnboardingViewHolder {
        val binding = ItemOnboardingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OnboardingViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: OnboardingViewHolder, position: Int) {
        holder.bind(items[position])
    }
    
    override fun getItemCount(): Int = items.size
    
    class OnboardingViewHolder(
        private val binding: ItemOnboardingBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(item: OnboardingItem) {
            try {
                val assetManager = binding.root.context.assets
                val inputStream = assetManager.open(item.imagePath)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                binding.ivOnboarding.setImageBitmap(bitmap)
                inputStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            binding.tvTitle.text = item.title
            binding.tvDescription.text = item.description
        }
    }
}
