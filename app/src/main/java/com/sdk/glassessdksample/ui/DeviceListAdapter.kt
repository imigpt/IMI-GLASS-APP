package com.sdk.glassessdksample.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R

class DeviceListAdapter(private val context: Context, private val deviceList: List<SmartWatch>) :
    RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    private var onItemClickListener: ((adapter: DeviceListAdapter, view: View, position: Int) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_device_list, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = deviceList[position]
        holder.tvDeviceName.text = device.deviceName
        holder.tvDeviceAddress.text = device.deviceAddress

        holder.itemView.setOnClickListener { 
            onItemClickListener?.invoke(this, it, position)
        }
    }

    override fun getItemCount(): Int {
        return deviceList.size
    }

    fun setOnItemClickListener(listener: (adapter: DeviceListAdapter, view: View, position: Int) -> Unit) {
        this.onItemClickListener = listener
    }

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDeviceName: TextView = itemView.findViewById(R.id.tv_device_name)
        val tvDeviceAddress: TextView = itemView.findViewById(R.id.tv_device_address)
    }
}
