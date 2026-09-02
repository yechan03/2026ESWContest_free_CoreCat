package com.example.in_gps.model;

import com.google.gson.annotations.SerializedName;

public class DeviceModel {
    @SerializedName("device_id")
    public String deviceId;
    @SerializedName("equipment_id")
    public String equipmentId;
    @SerializedName("status")
    public String status;
    @SerializedName("installed_on")
    public String installedOn;
    @SerializedName("last_seen_at")
    public String lastSeenAt;
    @SerializedName("created_at")
    public String createdAt;
    @SerializedName("updated_at")
    public String updatedAt;

    // ── 디바이스별 최신 온도 (server: GET /devices, 2026-09-01 추가) ──────────────
    // 서버는 최근 10분(LATEST_TEMP_MAX_AGE_MINUTES) 내 temperature_log 행이 없으면
    // 아래 3개를 모두 JSON null로 내려준다.
    //
    // wrapper Float 필수: primitive float면 Gson이 JSON null을 0.0f로 채워
    // "0°C인 정상 디바이스"로 오인된다. 알림 판정에서는 조용히 임계 미달로 처리돼
    // 알림이 영원히 뜨지 않는 실패가 된다 (TemperatureModel.coreTemp와 같은 함정).

    /** 최신 표면온도 [°C]. 최근 10분 내 유효 측정이 없으면 null. */
    @SerializedName("latest_temp1")
    public Float latestTemp1;

    /** 최신 AI 추정 코어온도 [°C]. 게이트웨이 mfg_data core_temp 파싱 미완으로 현재는 항상 null(정상). */
    @SerializedName("latest_core_temp")
    public Float latestCoreTemp;

    /** 위 두 값이 나온 temperature_log.created_at. createdAt(디바이스 등록 시각)과 다르다. */
    @SerializedName("latest_temp_at")
    public String latestTempAt;
}