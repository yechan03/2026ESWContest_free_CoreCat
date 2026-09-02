package com.example.in_gps.model;

import com.google.gson.annotations.SerializedName;

public class TemperatureModel {
    @SerializedName("id")
    public long id;

    @SerializedName("device_id")
    public String deviceId;

    // Thermistor (NTC) °C
    @SerializedName("temp1")
    public float temp1;

    @SerializedName("temp2")
    public float temp2;

    // AI 예측 코어 온도 [°C] — 센서 무효/미산출 시 서버가 null을 내려준다.
    // wrapper Float 필수: primitive float면 Gson이 null을 0.0f로 채워
    // "0°C"로 조용히 오렌더링된다(temp1/temp2가 걸려 있는 기존 함정).
    @SerializedName("core_temp")
    public Float coreTemp;

    // ADXL335 RMS [mg] — server v4 (angle_* → rms_* rename)
    @SerializedName("rms_x")
    public Integer rmsX;

    @SerializedName("rms_y")
    public Integer rmsY;

    @SerializedName("rms_z")
    public Integer rmsZ;

    @SerializedName("event")
    public String event;

    @SerializedName("created_at")
    public String createdAt;

    // 집계 응답 전용 (aggregated=true 일 때만 값 존재)
    @SerializedName("temp1_max")
    public Float temp1Max;

    @SerializedName("temp2_max")
    public Float temp2Max;

    @SerializedName("temp1_min")
    public Float temp1Min;

    @SerializedName("temp2_min")
    public Float temp2Min;
}
