package com.sdk.glassessdksample.ui

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R

/**
 * RecyclerView adapter showing each BLE device as a full glasses card
 * matching the Equipment screen style (dark bg, gradient card, Connect button).
 */
class BleDeviceAdapter(
    private var devices: List<SmartWatch>,
    private val onConnectClick: (SmartWatch) -> Unit
) : RecyclerView.Adapter<BleDeviceAdapter.DeviceViewHolder>() {

    fun submitList(newDevices: List<SmartWatch>) {
        devices = newDevices
        notifyDataSetChanged()
    }

    override fun getItemCount() = devices.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ble_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
        // Slide-in animation from bottom as each card appears
        holder.itemView.translationY = 120f
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .translationY(0f)
            .alpha(1f)
            .setStartDelay((position * 80L))
            .setDuration(400L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    inner class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvDeviceName: TextView    = view.findViewById(R.id.tvDeviceName)
        private val tvDeviceAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        private val btnConnect: Button        = view.findViewById(R.id.btnConnect)

        fun bind(device: SmartWatch) {
            tvDeviceName.text    = device.deviceName
            tvDeviceAddress.text = device.deviceAddress
            btnConnect.setOnClickListener { onConnectClick(device) }
        }
    }
}
