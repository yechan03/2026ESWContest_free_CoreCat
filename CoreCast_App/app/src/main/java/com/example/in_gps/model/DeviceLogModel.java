package com.example.in_gps.model;

import com.google.gson.annotations.SerializedName;

public class DeviceLogModel {
    @SerializedName("log_id")
    public String logId;
    @SerializedName("device_id")
    public String deviceId;
    @SerializedName("reboot_count")
    public int rebootCount;
    @SerializedName("temp_out_c")
    public double tempOutC;
    @SerializedName("temp_core_c")
    public double tempCoreC;
    @SerializedName("fault_grade")
    public String faultGrade;
    @SerializedName("created_at")
    public String createdAt;
}
