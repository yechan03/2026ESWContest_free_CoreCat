package com.example.in_gps.repository;

import com.example.in_gps.api.RetrofitClient;
import com.example.in_gps.model.DeviceModel;
import com.example.in_gps.model.DeviceModelResponse;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DeviceRepository {

    public interface OnDevicesLoadedCallback {
        void onResult(List<DeviceModel> devices);
    }

    public void fetchDevices(OnDevicesLoadedCallback callback) {
        RetrofitClient.getInstance().getApiService()
                .getDevices()
                .enqueue(new Callback<DeviceModelResponse>() {
                    @Override
                    public void onResponse(Call<DeviceModelResponse> call, Response<DeviceModelResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            callback.onResult(response.body().items);
                        }
                    }

                    @Override
                    public void onFailure(Call<DeviceModelResponse> call, Throwable t) {
                        // polling will retry on next interval
                    }
                });
    }
}
