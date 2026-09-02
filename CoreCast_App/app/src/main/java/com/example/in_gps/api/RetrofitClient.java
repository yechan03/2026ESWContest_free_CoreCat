package com.example.in_gps.api;


import com.github.mikephil.charting.BuildConfig;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {
    private static final String BASE_URL = "http://13.209.92.219:8000/";
    private static volatile RetrofitClient instance;
    private final ApiService apiService;

    private RetrofitClient() {
        // 로깅은 디버그 빌드에서만, 헤더 수준(BASIC)까지만.
        // Level.BODY는 차트 응답(수천 행 JSON)을 통째로 문자열화해
        // 파싱보다 로깅이 더 오래 걸리는 병목이었음.
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.BASIC
                : HttpLoggingInterceptor.Level.NONE);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        apiService = retrofit.create(ApiService.class);
    }

    public static RetrofitClient getInstance() {
        if (instance == null) {
            synchronized (RetrofitClient.class) {
                if (instance == null) instance = new RetrofitClient();
            }
        }
        return instance;
    }

    public ApiService getApiService() {
        return apiService;
    }
}
