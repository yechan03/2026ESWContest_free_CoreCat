package com.example.in_gps.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.in_gps.R;
import com.example.in_gps.model.DeviceModel;

import java.util.ArrayList;
import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

    public interface OnDeviceClickListener {
        void onDeviceClick(DeviceModel device);
    }

    private List<DeviceModel> devices = new ArrayList<>();
    private final OnDeviceClickListener listener;

    public DeviceAdapter(OnDeviceClickListener listener) {
        this.listener = listener;
    }

    public void setDevices(List<DeviceModel> devices) {
        this.devices = devices;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DeviceModel device = devices.get(position);
        holder.tvDeviceName.setText(device.deviceId);
        holder.tvDeviceStatus.setText(statusLabel(device.status));
        holder.statusDot.setBackgroundTintList(
                ColorStateList.valueOf(statusColor(holder.itemView, device.status)));
        holder.itemView.setOnClickListener(v -> listener.onDeviceClick(device));
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    private String statusLabel(String status) {
        if ("normal".equalsIgnoreCase(status)) return "정상 운영 중";
        if ("warning".equalsIgnoreCase(status)) return "경고";
        if ("critical".equalsIgnoreCase(status)) return "위험";
        if ("disconnected".equalsIgnoreCase(status)) return "연결 끊김";
        return status != null ? status : "--";
    }

    private int statusColor(View view, String status) {
        if ("normal".equalsIgnoreCase(status))
            return view.getContext().getResources().getColor(R.color.color_normal, null);
        if ("warning".equalsIgnoreCase(status))
            return view.getContext().getResources().getColor(R.color.color_warning, null);
        if ("critical".equalsIgnoreCase(status))
            return view.getContext().getResources().getColor(R.color.color_critical, null);
        if ("disconnected".equalsIgnoreCase(status))
            return view.getContext().getResources().getColor(R.color.color_text_secondary, null);
        return view.getContext().getResources().getColor(R.color.color_text_secondary, null);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvDeviceName;
        final TextView tvDeviceStatus;
        final View statusDot;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDeviceName = itemView.findViewById(R.id.tv_device_name);
            tvDeviceStatus = itemView.findViewById(R.id.tv_device_status);
            statusDot = itemView.findViewById(R.id.view_status_dot);
        }
    }
}
