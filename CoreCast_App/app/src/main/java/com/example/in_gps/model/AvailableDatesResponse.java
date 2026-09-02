package com.example.in_gps.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** GET /temperature/dates 응답: 데이터가 존재하는 날짜(YYYY-MM-DD) 목록. */
public class AvailableDatesResponse {
    @SerializedName("device_id")
    public String deviceId;

    @SerializedName("dates")
    public List<String> dates;
}
