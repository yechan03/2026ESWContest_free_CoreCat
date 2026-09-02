package com.example.in_gps.repository;

import androidx.lifecycle.MutableLiveData;

import com.example.in_gps.api.ApiService;
import com.example.in_gps.api.RetrofitClient;
import com.example.in_gps.model.AvailableDatesResponse;
import com.example.in_gps.model.TemperatureModel;
import com.example.in_gps.model.TemperatureResponse;

import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TemperatureRepository {

    /** 서버 집계 해상도. 페이로드 최소화의 핵심 — Docs/모바일_코드리뷰_효율개선.md §1-2 */
    public static final String BUCKET_MINUTE = "1m";
    public static final String BUCKET_DAY    = "1d";

    private final ApiService api = RetrofitClient.getInstance().getApiService();

    public interface OnItemsCallback {
        void onResult(List<TemperatureModel> items);
    }

    public void fetchLatest(String deviceId, MutableLiveData<TemperatureModel> liveData) {
        api.getTemperature(deviceId, 1)
                .enqueue(new Callback<TemperatureResponse>() {
                    @Override
                    public void onResponse(Call<TemperatureResponse> call, Response<TemperatureResponse> response) {
                        if (response.isSuccessful() && response.body() != null
                                && !response.body().items.isEmpty()) {
                            liveData.postValue(response.body().items.get(0));
                        }
                    }

                    @Override
                    public void onFailure(Call<TemperatureResponse> call, Throwable t) {
                        // polling will retry on next interval
                    }
                });
    }

    /**
     * 실시간 초기 로드 — 최근 N건 raw 로그 (created_at DESC).
     * 이후 갱신은 fetchSince로 증분 수신할 것.
     */
    public void fetchRecent(String deviceId, int limit, OnItemsCallback callback) {
        api.getTemperature(deviceId, limit)
                .enqueue(new Callback<TemperatureResponse>() {
                    @Override
                    public void onResponse(Call<TemperatureResponse> call, Response<TemperatureResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            callback.onResult(response.body().items);
                        }
                    }

                    @Override
                    public void onFailure(Call<TemperatureResponse> call, Throwable t) {
                        // polling will retry on next interval
                    }
                });
    }

    /**
     * 증분 폴링 — since(마지막 수신 created_at) 이후 새 행만 (ASC).
     * 구버전 서버는 since를 무시하고 최근 N건(DESC)을 주므로,
     * 호출 측(ViewModel)에서 created_at > since 필터로 방어한다.
     */
    public void fetchSince(String deviceId, String since, int limit, OnItemsCallback callback) {
        api.getTemperatureSince(deviceId, since, limit)
                .enqueue(new Callback<TemperatureResponse>() {
                    @Override
                    public void onResponse(Call<TemperatureResponse> call, Response<TemperatureResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            callback.onResult(response.body().items);
                        }
                    }

                    @Override
                    public void onFailure(Call<TemperatureResponse> call, Throwable t) {
                        // polling will retry on next interval
                    }
                });
    }

    /**
     * 기간 차트 조회 — 서버 집계로 수신.
     * days == 1 → 1m 버킷(분단위 곡선용, ~1,440행)
     * days >  1 → 1d 버킷(주/월/년 뷰용, 최대 365행) — 기존 1주 뷰 ~10,000행 문제 해결.
     */
    public void fetchPeriod(String deviceId, int days, MutableLiveData<List<TemperatureModel>> liveData) {
        String bucket = (days <= 1) ? BUCKET_MINUTE : BUCKET_DAY;
        api.getTemperatureChart(deviceId, days, bucket)
                .enqueue(new Callback<TemperatureResponse>() {
                    @Override
                    public void onResponse(Call<TemperatureResponse> call, Response<TemperatureResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            liveData.postValue(response.body().items);
                        }
                    }

                    @Override
                    public void onFailure(Call<TemperatureResponse> call, Throwable t) {
                        // silently ignore
                    }
                });
    }

    /**
     * 기간(시작~종료) 차트 조회. 날짜는 YYYY-MM-DD (종료일 포함).
     * 단일 날짜는 1m(분단위 곡선), 여러 날은 1d 서버 집계.
     */
    public void fetchRange(String deviceId, String startDate, String endDate,
                           MutableLiveData<List<TemperatureModel>> liveData) {
        String bucket = startDate.equals(endDate) ? BUCKET_MINUTE : BUCKET_DAY;
        api.getTemperatureChartRange(deviceId, startDate, endDate, bucket)
                .enqueue(new Callback<TemperatureResponse>() {
                    @Override
                    public void onResponse(Call<TemperatureResponse> call, Response<TemperatureResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            liveData.postValue(response.body().items);
                        } else {
                            liveData.postValue(Collections.emptyList());
                        }
                    }

                    @Override
                    public void onFailure(Call<TemperatureResponse> call, Throwable t) {
                        liveData.postValue(Collections.emptyList());
                    }
                });
    }

    /** 데이터가 존재하는 날짜 목록(캘린더에서 없는 날짜 표시용). */
    public void fetchAvailableDates(String deviceId, MutableLiveData<List<String>> liveData) {
        api.getAvailableDates(deviceId)
                .enqueue(new Callback<AvailableDatesResponse>() {
                    @Override
                    public void onResponse(Call<AvailableDatesResponse> call, Response<AvailableDatesResponse> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().dates != null) {
                            liveData.postValue(response.body().dates);
                        } else {
                            liveData.postValue(Collections.emptyList());
                        }
                    }

                    @Override
                    public void onFailure(Call<AvailableDatesResponse> call, Throwable t) {
                        liveData.postValue(Collections.emptyList());
                    }
                });
    }
}
