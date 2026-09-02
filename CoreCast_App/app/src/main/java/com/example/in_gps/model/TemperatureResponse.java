package com.example.in_gps.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class TemperatureResponse {
    @SerializedName("items")
    public List<TemperatureModel> items;
}
