package com.example.in_gps.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.in_gps.R;
import com.example.in_gps.model.DeviceModel;
import com.example.in_gps.screen.MainActivity;

import java.util.List;
import java.util.Locale;

/**
 * 온도 임계 초과 로컬 알림 판정/발행 유틸.
 *
 * <p>판정 대상은 <b>코어(core_temp) + 표면(temp1) 두 센서뿐</b>이다.
 * temp2("외부")는 명시적 비대상이며 여기서 어떤 알림도 만들지 않는다.
 *
 * <p><b>Context를 필드로 보관하지 않는다.</b> 전부 인자로만 받는 static 메서드다.
 * 호출 지점은 {@code MainActivity}의 LiveData observe 콜백 <b>한 곳</b>뿐이다
 * (SensorDetailFragment 등에 중복으로 붙이면 같은 초과를 두 경로가 판정해 경합이 생긴다).
 */
public final class TempAlertNotifier {

    private TempAlertNotifier() { }

    // ── 알림 채널 ────────────────────────────────────────────────────────────
    /** 한 번 생성된 채널의 importance는 코드로 변경 불가 — 재설치 전까지 고정된다. */
    private static final String CHANNEL_ID   = "ingps_temp_alert";
    private static final String CHANNEL_NAME = "온도 임계 알림";

    // ── SharedPreferences 스키마 ("in_gps_prefs") ────────────────────────────
    // 문자열 키는 오타가 나도 컴파일이 통과하고 크래시도 없이 조용히 기본값으로
    // 동작한다. 리터럴을 재타이핑하지 말고 반드시 아래 상수만 쓸 것.
    private static final String PREFS_NAME = "in_gps_prefs";

    /** 코어 임계값 [°C]. 쓰기는 SettingsFragment.setupCoreThreshold(), 읽기는 여기 한 곳뿐. */
    private static final String KEY_CORE_THRESHOLD = "core_threshold_c";
    /** 표면 임계값 [°C]. 차트(LimitLine/dangerZone/마커)가 쓰는 <b>기존 키를 그대로</b> 읽는다. */
    private static final String KEY_SURF_THRESHOLD = "danger_threshold_c";

    // 상태 키는 "<prefix>_alert_active_<deviceId>" / "<prefix>_alert_last_ms_<deviceId>" 형태다.
    //  · 디바이스별 접미사 필수 — 없으면 esp_32_0이 초과 중일 때 esp_32_1 알림이 억제된다.
    //  · 센서별 접두사 필수 — 없으면 코어 초과 중일 때 표면 알림이 억제된다.
    //    (두 센서는 서로 독립적으로 임계를 넘나든다)
    private static final String SUFFIX_ALERT_ACTIVE  = "_alert_active_";
    private static final String SUFFIX_ALERT_LAST_MS = "_alert_last_ms_";

    /** 센서 구분 접두사 겸 Notification ID 시드. */
    private static final String SENSOR_CORE = "core";
    private static final String SENSOR_SURF = "surf";

    // ── 판정 상수 ────────────────────────────────────────────────────────────
    /** 코어 임계 기본값 [°C]. SettingsFragment picker 범위(20~200)와 정렬. */
    private static final float DEFAULT_CORE_THRESHOLD_C = 80f;

    /**
     * 표면 임계의 <b>알림 경로 전용</b> 기본값 [°C].
     *
     * <p>주의: 같은 {@code danger_threshold_c} 키를 읽지만 차트 경로
     * ({@code SensorDetailFragment.getDangerThresholdC()} 등)는 기본값 40f를 쓴다.
     * <b>키를 추가하거나 구조를 바꾼 것이 아니라 getFloat 기본값 인자만 다르게 준 것</b>이며,
     * 사용자가 설정에서 값을 한 번이라도 저장하면 양쪽 모두 그 저장값을 쓴다.
     *
     * <p>다르게 주는 이유: 시드 데이터의 표면온도 기준선이 45°C이고 서버의 warning 기준은
     * 70°C다. 즉 40°C는 "정상 가동 중"에도 상시 초과하는 값이라, 지금까지는 차트에
     * 위험선이 낮게 그려지는 정도였지만 알림으로 승격되는 순간 앱 첫 실행에 디바이스
     * 여러 대의 알림이 동시에 폭주한다.
     */
    private static final float DEFAULT_SURF_THRESHOLD_C = 70f;

    /** 임계 근처에서 값이 떨리며 알림이 반복되는 것을 막는 복귀 마진 [°C]. */
    private static final float HYSTERESIS_C = 2.0f;

    /**
     * 초과가 계속될 때의 리마인드 간격 [ms].
     * 리마인드가 불필요하다고 판단되면 아래 세 번째 분기만 삭제하면 된다.
     */
    private static final long MIN_RENOTIFY_MS = 10 * 60 * 1000L;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 알림 채널을 생성한다. 멱등이므로 반복 호출해도 안전하다.
     * Application 서브클래스가 없으므로 {@code MainActivity.onCreate}에서 호출한다.
     */
    public static void ensureChannel(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;   // minSdk 24

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("설정한 임계값을 넘는 온도가 감지되면 알립니다.");
        nm.createNotificationChannel(channel);
    }

    /**
     * 판정 진입점. 디바이스 목록을 순회하며 디바이스마다 코어/표면을 각각 판정한다.
     *
     * @param devices {@code GET /devices} 응답. null이거나 비어 있으면 아무것도 하지 않는다.
     */
    public static void check(Context context, List<DeviceModel> devices) {
        if (context == null || devices == null) return;

        for (DeviceModel d : devices) {
            if (d == null || d.deviceId == null) continue;
            checkCore(context, d.deviceId, d.latestCoreTemp);
            checkSurface(context, d.deviceId, d.latestTemp1);
        }
    }

    /**
     * AI 추정 코어온도 판정.
     *
     * @param coreTemp null이면(= 게이트웨이 미완으로 현재의 정상 상태, 또는 10분 내 데이터 없음)
     *                 판정을 건너뛴다. 0.0f로 대체하지 않는다.
     */
    public static void checkCore(Context context, String deviceId, Float coreTemp) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null) return;
        float threshold = prefs.getFloat(KEY_CORE_THRESHOLD, DEFAULT_CORE_THRESHOLD_C);
        evaluate(context, prefs, SENSOR_CORE, deviceId, coreTemp, threshold);
    }

    /**
     * 실측 표면온도(temp1) 판정.
     *
     * @param temp1 null이면(10분 내 유효 측정 없음 / 서버 유효범위 필터에 걸림) 판정을 건너뛴다.
     */
    public static void checkSurface(Context context, String deviceId, Float temp1) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null) return;
        // 기존 키를 그대로 읽되 기본값 인자만 알림용(70f)으로 준다 — DEFAULT_SURF_THRESHOLD_C 주석 참조.
        float threshold = prefs.getFloat(KEY_SURF_THRESHOLD, DEFAULT_SURF_THRESHOLD_C);
        evaluate(context, prefs, SENSOR_SURF, deviceId, temp1, threshold);
    }

    // ── 내부 ─────────────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context context) {
        if (context == null) return null;
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 히스테리시스 상태 전이. 코어/표면 두 센서에 동일하게 적용된다.
     *
     * <pre>
     *   !active && v >= T                        → 알림, (게시 성공 시) active=true, last=now
     *    active && v <= T - HYSTERESIS_C         → active=false (복귀)
     *    active && now-last >= RENOTIFY && v>=T  → 리마인드 알림, (게시 성공 시) last=now
     * </pre>
     *
     * <p><b>상태 기록은 알림이 실제로 게시된 경우에만 한다.</b> POST_NOTIFICATIONS 권한이
     * 없어 게시가 무음 실패했는데도 active=true를 써버리면, 나중에 사용자가 권한을
     * 허용해도 그 초과는 이미 "알린 것"으로 간주되어 리마인드 간격({@link #MIN_RENOTIFY_MS})
     * 전까지 알림이 누락된다. 실패 시 상태를 그대로 두면 다음 폴링 주기에 다시
     * "새로 초과"로 판정되어 권한 허용 직후 바로 알림이 뜬다.
     */
    private static void evaluate(Context context, SharedPreferences prefs, String sensor,
                                 String deviceId, Float value, float threshold) {
        if (deviceId == null) return;
        if (value == null) return;              // null이면 알림도 상태 변경도 없다

        final String keyActive = sensor + SUFFIX_ALERT_ACTIVE + deviceId;
        final String keyLastMs = sensor + SUFFIX_ALERT_LAST_MS + deviceId;

        final float v = value;
        final boolean active = prefs.getBoolean(keyActive, false);
        final long now = System.currentTimeMillis();

        if (!active && v >= threshold) {
            // 게시 실패(권한 없음)면 active를 갱신하지 않는다 → 다음 폴링에서 이 분기를 다시 탄다.
            if (notifyExceed(context, sensor, deviceId, v, threshold)) {
                prefs.edit()
                        .putBoolean(keyActive, true)
                        .putLong(keyLastMs, now)
                        .apply();
            }

        } else if (active && v <= threshold - HYSTERESIS_C) {
            prefs.edit().putBoolean(keyActive, false).apply();

        } else if (active
                && now - prefs.getLong(keyLastMs, 0L) >= MIN_RENOTIFY_MS
                && v >= threshold) {
            // 리마인드도 동일 — 게시 실패면 last를 갱신하지 않아 다음 폴링에서 재시도된다.
            if (notifyExceed(context, sensor, deviceId, v, threshold)) {
                prefs.edit().putLong(keyLastMs, now).apply();
            }
        }
    }

    /**
     * 알림을 실제로 게시한다.
     *
     * @return 게시에 성공하면 true. 권한이 없거나 런타임에 회수되어 무음 실패하면 false —
     *         호출부는 이 값이 false면 히스테리시스 상태를 기록하지 않는다.
     */
    // 권한 확인은 아래 hasNotificationPermission()에서 하지만, lint는 헬퍼 메서드를 넘어
    // 흐름을 추적하지 못해 MissingPermission(Error)을 낸다 → release 빌드 차단 방지.
    @SuppressLint("MissingPermission")
    private static boolean notifyExceed(Context context, String sensor, String deviceId,
                                        float value, float threshold) {
        // 권한 거부 시 무음 실패 (AC-8). 게시 불가가 확정이므로 Builder/PendingIntent를
        // 만들기 전에 빠져나간다 — 이 경로는 실패 시 폴링 주기(5초)마다 재시도된다.
        if (!hasNotificationPermission(context)) return false;

        boolean isCore = SENSOR_CORE.equals(sensor);

        String title = isCore ? "코어 온도 임계 초과" : "표면 온도 임계 초과";

        StringBuilder body = new StringBuilder();
        body.append(deviceId)
                .append(" · ")
                .append(String.format(Locale.US, "%.1f°C", value))
                .append(" (임계 ")
                .append(String.format(Locale.US, "%.1f°C", threshold))
                .append(")");
        if (isCore) {
            // 표면은 실측이라 이 문구를 붙이면 오히려 오해를 부른다 — core에만 붙인다.
            body.append("\nAI 추정치입니다. 참고용이며 과열 판정의 단독 근거로 사용하지 마세요.");
        }
        String text = body.toString();

        // 알림 탭 → MainActivity를 연다. 디바이스별 딥링크는 이번 범위 밖.
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // FLAG_IMMUTABLE 필수 (API 31+에서 mutability 미지정 시 IllegalArgumentException)
        int notificationId = (deviceId + ":" + sensor).hashCode();
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification_alert)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setAutoCancel(true)
                        .setContentIntent(contentIntent);

        // Notification ID에 센서 키를 섞지 않으면 표면 알림이 코어 알림을 덮어쓴다.
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build());
            return true;
        } catch (SecurityException ignored) {
            // 권한이 런타임에 회수된 경우. 알림만 실패하고 다른 기능에는 영향 없다.
            return false;
        }
    }

    private static boolean hasNotificationPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}
