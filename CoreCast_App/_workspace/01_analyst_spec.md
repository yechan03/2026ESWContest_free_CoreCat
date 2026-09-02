# 요구사항 명세: core_temp 임계온도 설정 + 임계 초과 로컬 알림

- 작성일: 2026-09-01
- **개정: 2026-09-01 (rev.2 — U1/U3 확정 반영, 서버 레이어 스코프 진입)**
- 대상 저장소: `C:\AndroidProject\IN_GPS` (Android 앱) + **`C:\in_gps_server` (FastAPI 서버) — rev.2에서 추가**
- 요청 원문: "Core_temp도 Ambient_temp처럼 임계온도 설정할 수 있도록 해줘 그리고 임계온도 넘어가면 임계온도 넘어갔다는 푸쉬알림을 넣어줘"
- 선행 산출물: `_workspace_prev/CHANGELOG.md` (2026-08-31 core_temp 파이프라인 작업)

> **rev.2 요약:** 사용자가 U1을 **(b) 앱 포그라운드 전역 감시**로, U3를 **core_temp + temp1 두 센서**로
> 확정했다. 이에 따라 (1) FastAPI 서버가 스코프에 들어오고(`GET /devices` 응답 확장),
> (2) 앱에 화면 독립적인 전역 폴러가 추가되며, (3) AC-4/AC-9가 재정의됐다.
> **원 명세의 "서버 변경 불필요"(rev.1 §2)는 무효다.**

---

## 0. 조사로 확정한 사실 (근거 파일 명시)

이 절은 명세의 전제다. 아래 항목은 모두 실제 파일을 열어 확인했다.
**F19~F26은 rev.2에서 추가 조사한 것이다.**

| # | 확정 사실 | 근거 |
|---|-----------|------|
| F1 | threshold 저장소는 `SharedPreferences("in_gps_prefs")`의 `danger_threshold_c` 키 **하나**뿐. 기본 40f, picker 범위 20~120 | `SettingsFragment.java:72-94`, `fragment_settings.xml:337-341` |
| F2 | 같은 키를 `SensorDetailFragment.getDangerThresholdC()`와 `SensorDetailTestFragment.getDangerThresholdC()`가 **각각 독립 구현**해 읽음 (중복 코드 2벌) | `SensorDetailFragment.java:524-529`, `SensorDetailTestFragment.java:418-423` |
| F3 | threshold의 현재 용도는 **시각화 전용** — LimitLine / dangerZone 음영 / exceedanceMarkers. 알림·경보 로직 0건 | `SensorDetailFragment.java:402,531-`, `SensorDetailTestFragment.java:367-373,425-` |
| F4 | **`SensorDetailTestFragment`는 레거시가 아니라 실사용 화면**이다. `SensorDetailFragment`의 `btn_realtime_detail` → `RealtimeDetailDialogFragment` → `SensorDetailTestFragment`로 열리는 "실시간 상세보기"(1초 폴링) 다이얼로그의 본문이다 | `SensorDetailFragment.java:175-183`, `RealtimeDetailDialogFragment.java:53`, `fragment_sensor_detail.xml:81` |
| F5 | 단, 그 클래스 주석(`SensorDetailTestFragment.java:37-44`)은 **낡았다** — "테스트용", "디바이스 클릭 시 열리는"이라고 적혀 있으나 디바이스 클릭은 `SensorDetailFragment`로 간다 (`DeviceListFragment.java:40`) | 위 두 파일 |
| F6 | `SensorDetailTestFragment`는 `coreTemp`를 **전혀 표시하지 않는다** (temp1/temp2/rms만). `core_temp` grep 결과 0건 | 앱 전체 grep |
| F7 | `coreTemp` 소비 지점은 앱 전체에서 **단 한 곳** — `SensorDetailFragment.java:154-158` (`tvAiTemp`, null이면 "측정 불가") | 앱 전체 grep |
| F8 | `coreTemp`가 도달하는 폴링 루프는 `SensorDetailViewModel` 5초 주기 `repository.fetchLatest()` → `temperatureData` LiveData 하나 | `SensorDetailViewModel.java:19-37,56-58` |
| F9 | `TemperatureModel.coreTemp`는 **wrapper `Float`** (null 가능). primitive 금지 규칙이 주석으로 박혀 있음. 반면 `temp1`/`temp2`는 **primitive `float`** — 이미 걸린 함정으로 주석에 명시 | `TemperatureModel.java:12-23` |
| F10 | **`GET /devices` 응답(`DeviceModel`)에는 온도 필드가 없다** — `device_id/equipment_id/status/installed_on/last_seen_at/created_at/updated_at`뿐 | `DeviceModel.java:5-20`, `app.py:167-184` |
| F11 | `AndroidManifest.xml`에 선언된 권한은 `INTERNET` **하나뿐**. Application 서브클래스 없음(`android:name` 미지정), Service/Receiver 0건 | `AndroidManifest.xml` 전문 |
| F12 | `minSdk 24 / targetSdk 36 / compileSdk 36` → `POST_NOTIFICATIONS` 런타임 요청(API 33+) **필수**, `NotificationChannel`(API 26+)은 `Build.VERSION` 분기 **필수** | `app/build.gradle:7-21` |
| F13 | 의존성에 Firebase/google-services 없음. `libs.activity`(androidx.activity) 존재 → `ActivityResultContracts.RequestPermission` 사용 가능. `NotificationCompat`는 appcompat이 끌어오는 androidx.core에 포함 | `app/build.gradle:35-53` |
| F14 | 알림 small icon으로 쓸 만한 경고 계열 벡터 드로어블 **없음** | `res/drawable/` 목록 |
| F15 | `strings.xml`은 `app_name` + placeholder 2개뿐. 화면 문자열은 레이아웃에 하드코딩하는 것이 이 앱의 기존 관행 | `res/values/strings.xml`, `fragment_settings.xml` |
| F16 | 서버는 이미 `/temperature`, `/temperature/chart`, `/sensor`, `/chart/{id}` 응답에 `core_temp`를 **포함해 내려준다** | `C:\in_gps_server\app.py:264,278,302,328,336,464,513` |
| F17 | **게이트웨이가 `mfg_data[13..14]`를 아직 파싱하지 않아 `core_temp`는 실제로 항상 NULL이다.** 앱은 "측정 불가" 표시 — 정상 동작 | `_workspace_prev/CHANGELOG.md` 미해결 #1 |
| F18 | SHF 모델 비단조 경고: Ta≈68°C 이상에서 표면이 뜨거울수록 core_temp가 **하강**. "과열 임계 판정의 단독 근거로 쓰면 안 된다" | `_workspace_prev/CHANGELOG.md` 미해결 #7, `shf_core_model.h` 주석 |
| **F19** | **`MainActivity`가 앱의 유일한 Activity다.** 매니페스트에 `<activity>` 선언이 1개뿐이며 LAUNCHER 인텐트필터를 가진다. 나머지 화면은 전부 Fragment(`fragment_container` 교체) | `AndroidManifest.xml:15-23`, `MainActivity.java:26-49` |
| **F20** | 따라서 **"앱이 포그라운드" ≡ "MainActivity가 started 상태"** 가 성립한다 → `ProcessLifecycleOwner`가 **불필요**하다. 현재 의존성에 `androidx.lifecycle:lifecycle-process`가 **없으므로**(livedata/viewmodel만 있음) 쓰려면 신규 의존성 추가가 필요한데, F19 덕분에 그 비용을 치를 이유가 없다 | `app/build.gradle:45-49`, F19 |
| **F21** | `RealtimeDetailDialogFragment`는 **DialogFragment**이지 Activity가 아니다. 다이얼로그가 떠 있어도 `MainActivity`는 started 상태를 유지한다 → Activity 스코프 폴러는 rev.1 §2-1 근거 2의 "감시 공백"을 **자동으로 해소**한다 | `RealtimeDetailDialogFragment.java`, F19 |
| **F22** | `GET /devices`는 **`device` 테이블 단독 조회**다. JOIN·서브쿼리가 전혀 없고, `last_seen_at`이 15초보다 오래되면 status를 `'Disconnected'`로 덮어쓰는 CASE 식만 있다. 최신 온도를 붙이려면 `temperature_log` 조인이 **새로 필요**하다 | `app.py:167-184` |
| **F23** | `temperature_log`에 **`INDEX idx_device_created (device_id, created_at)`** 가 존재한다. "디바이스별 최신 1행"은 이 인덱스의 역방향 range seek로 처리 가능 | `temperature_db_init.sql:30` |
| **F24** | 디바이스 개수 상한은 **10개**다. `device_id = "esp_32_{byte_id}", byte_id ∈ [0,9]` (mfg_data 계약). `/devices`는 `CAST(SUBSTRING_INDEX(device_id,'_',-1) AS UNSIGNED)` 정렬로 이 규칙을 전제한다 | `mqtt_subscriber.esp_byte_to_device_id()`, `app.py:180,183` |
| **F25** | `GET /devices`는 앱뿐 아니라 **웹 대시보드도 소비**한다(`const dev = await api("/devices")`). 다만 필드를 열거하지 않고 필요한 키만 읽으므로 **필드 추가는 하위 호환**이다 | `C:\in_gps_server\web\app.js:70` |
| **F26** | "경고=표면(temp1) 임계 기반"이 코드 주석으로 명시돼 있다. 사용자가 말한 **"Ambient_temp" = temp1(표면)** 이라는 확정과 일치한다. temp2는 "외부" 라벨이며 경고 판정에 쓰이지 않는다 | `SensorDetailFragment.java:481-482`, `fragment_sensor_detail.xml:145,153,201,238` |
| **F27** | `DeviceListViewModel`의 3초 폴링은 **Fragment onStart/onStop에 묶여 있고**, 그 이유가 주석에 명시돼 있다 — "SystemHealth와 같은 `/devices`를 쓰지만, 탭 전환 시 한쪽만 돌게 되어 중복 해소". 전역 감시용으로 **재활용하면 이 설계 의도가 깨진다** | `DeviceListViewModel.java:17,34-45` |

**rev.1에서 F10과 F8이 만든 제약을 rev.2는 F22~F24(서버 확장)와 F19~F21(Activity 스코프 폴러)로 해소한다.**

---

## 1. 수용 기준

### 필수 (AC-1 ~ AC-13)

- [ ] **AC-1** 설정 화면에 "AI 예측 코어 온도" 임계값 NumberPicker와 저장 버튼이 별도 카드로 존재하며, 기존 "위험 임계온도"(temp1/temp2 공유) 카드는 **동작·값·범위 모두 그대로** 남아 있다.
- [ ] **AC-2** 코어 임계값 저장 시 `SharedPreferences("in_gps_prefs")`의 **신규 키 `core_threshold_c`**(Float)에 기록되고, `danger_threshold_c` 값은 변경되지 않는다.
- [ ] **AC-3** 설정 화면 재진입 시 저장한 코어 임계값이 picker에 복원된다. 미설정 상태에서는 기본값(§6 U2, 잠정 80°C)이 표시된다.
- [ ] **AC-4** ~~`SensorDetailFragment`가 열려 있고 폴링 중일 때~~ → **`rev.2 개정` 앱이 포그라운드에 있는 동안, 현재 표시 중인 화면과 무관하게**(디바이스 목록·시스템 상태·설정 화면, 실시간 상세 다이얼로그가 열린 상태 포함) 임계 초과가 감지되면 시스템 알림이 **1회** 표시된다.
  - 판정 대상은 **모든 디바이스**다. 특정 디바이스 화면을 열어 둘 필요가 없다.
  - "포그라운드"의 조작적 정의: `MainActivity`가 `onStart`~`onStop` 사이에 있는 구간 (F19/F20).
- [ ] **AC-5** 알림 본문에 (a) 디바이스 ID, (b) 측정값(°C, 소수 1자리), (c) 설정된 임계값, (d) **core_temp 알림에 한해** "AI 추정치 · 참고용, 과열 판정의 단독 근거로 사용 금지" 취지의 고지 문구가 포함된다. (F18 대응 / 사용자 결정 3)
- [ ] **AC-6** 초과 상태가 지속되는 동안 **같은 디바이스·같은 센서에 대해 알림이 반복 생성되지 않는다.** 값이 임계 아래로 복귀(히스테리시스 포함)한 뒤 다시 초과할 때만 재알림된다.
- [ ] **AC-7** 값이 `null`(F17 현재의 core_temp 상태)일 때 알림이 **발생하지 않고** 크래시도 없다. 기존 "측정 불가" 표시가 유지된다.
- [ ] **AC-8** Android 13(API 33) 이상 기기에서 최초 실행 시 `POST_NOTIFICATIONS` 권한을 요청하고, **거부하더라도 앱의 기존 기능(목록·차트·설정)이 정상 동작한다**(알림만 무음 실패).
- [ ] **AC-9** **`rev.2 승격` (선택 확장 → 필수)** 표면 온도(`temp1`)가 기존 `danger_threshold_c`를 초과할 때도 core_temp와 **동일한 수준의** 알림이 동작한다.
  - **`temp2`("외부")는 명시적 비대상이다.** temp2 값으로는 어떤 알림도 발생시키지 않는다. (사용자 확정 U3)
  - temp1 판정에는 **기존 `danger_threshold_c` 값을 그대로 읽어 사용한다.** 신규 prefs 키를 만들지 않는다. (사용자 결정 2 재확인)
- [ ] **AC-10** **`rev.2 신규`** `GET /devices` 응답의 각 item에 최신 표면 온도와 최신 코어 온도 필드가 포함된다(§3-A). 온도 이력이 없거나 오래된 디바이스는 해당 필드가 **JSON `null`** 이며, 이때 앱은 알림을 발생시키지 않고 목록 렌더링도 깨지지 않는다.
- [ ] **AC-11** **`rev.2 신규`** 기존 `GET /devices` 소비처가 회귀 없이 동작한다 — 디바이스 목록 화면의 status 칩/색상, `SystemHealthFragment`의 normal/warning/critical/disconnected 집계, **웹 대시보드**(F25). 기존 7개 필드의 이름·타입·의미가 모두 불변이다.
- [ ] **AC-12** **`rev.2 신규`** 전역 감시 폴러는 `MainActivity`가 `onStop`된 뒤(홈 버튼·화면 꺼짐·앱 전환) **네트워크 요청을 더 이상 보내지 않는다.** (배터리 회귀 방지 / 백그라운드 비목표와의 정합성)
- [ ] **AC-13** **`rev.2 신규`** 전역 감시 폴러의 `ViewModel`은 `Context`·`View`·`Fragment`·`Activity` 참조를 **필드로 보관하지 않는다**. 알림 발행은 `Context`를 가진 `MainActivity`가 LiveData를 observe해 수행한다. (android-map.md MVVM 누수 규칙)

### 명시적 비목표 (Non-goals)

- 앱이 **백그라운드/종료 상태에서 알림을 받는 것**은 이번 스코프가 아니다 (사용자 결정 1: 로컬 알림 유지, FCM 아님). rev.2에서도 **변경 없음**.
- FCM/서버 푸시, 토큰 관리, `google-services.json` 도입 — **하지 않는다**.
- 기존 `danger_threshold_c`의 temp1/temp2 공유 구조를 센서별로 분리하는 리팩터링 — **하지 않는다** (사용자 결정 2, rev.2에서 재확인).
- `temp2`("외부") 기반 알림 — **하지 않는다** (U3 확정).
- `core_temp`를 차트(LimitLine/dangerZone)에 그리는 것 — 요청 범위 밖.
- `WorkManager`/포그라운드 Service/`ProcessLifecycleOwner` 도입 — F19/F20에 의해 **불필요**.

---

## 2. 영향 레이어

| 레이어 | 변경 필요 | 규모 | 대상 파일 |
|--------|----------|------|----------|
| ESP32 펌웨어 | **아니오** | — | 변경 없음. `mfg_data[13..14]` core_temp 인코딩은 2026-08-31에 이미 완료 |
| STM32 게이트웨이 | **아니오** (이번 기능 범위에서) | — | 변경 없음. 단 F17의 선행 미완료 작업이 남아 있어 **core_temp 실측 검증은 불가** — §6 U4 |
| **FastAPI 서버** | **예 `rev.2 변경`** | **S** | `C:\in_gps_server\app.py` — `list_devices()` (167-184행) SQL 및 응답 shape |
| MySQL DB | **아니오** | — | 신규 DDL 없음. **단 `in_gps_db_ver_6.sql`(core_temp 컬럼)의 EC2 적용이 이번 변경의 전제조건이 된다** — §4의 격상된 경고 참조 |
| **Android 앱** | **예** | **M~L `rev.2 상향`** | 아래 표 (전역 폴러 추가로 rev.1의 M에서 상향) |

### 2-A. FastAPI 서버 상세 (담당: `ingps-server-engineer`) — `rev.2 신규`

#### 설계 판단: **별도 엔드포인트 신설이 아니라 `GET /devices` 응답을 확장한다**

두 선택지를 실제 코드 기준으로 비교했다.

| 기준 | (A) `GET /devices` 확장 **← 채택** | (B) `GET /devices/latest_temps` 신설 |
|------|-----------------------------------|--------------------------------------|
| 앱 추가 작업 | `DeviceModel`에 필드 3개 추가만 | `ApiService` 메서드 + 응답 래퍼 모델 + `DeviceModel` 대응 모델 + Repository 메서드 = **신규 파일 2~3개** |
| 네트워크 요청 수 | 폴링 1종 | 폴링 2종(목록 + 온도) — 모바일에서 요청 수가 배터리에 직결 |
| 페이로드 | 디바이스 ≤10개(F24) × 3필드 추가 = 수백 바이트. GZip 임계(1024B) 아래라 압축도 안 걸림 | 별도 응답의 헤더/커넥션 오버헤드가 본문보다 큼 |
| 기존 소비처 영향 | **필드 추가는 하위 호환** — 웹 대시보드는 키를 열거하지 않고 필요한 것만 읽는다(F25). Gson도 모르는 키는 무시 | 없음 |
| 의미론 | `/devices`는 이미 "디바이스의 **현재 상태**"를 돌려준다(15초 룰로 status를 실시간 계산, F22). 최신 온도는 같은 성격의 데이터다 | 상태가 두 엔드포인트로 쪼개져 시점 불일치가 생길 수 있음 |
| 위험 | `/devices`가 `temperature_log`·`core_temp` 컬럼에 **새로 의존**하게 됨 → §4 경고 격상 | `/devices`는 무변경으로 남음 |

**결론: (A) 채택.** (B)의 유일한 실질 이점은 마지막 행의 "위험"인데, 이는 §4에서 이미
사용자 확인이 필요한 선행 항목(`ver_6` EC2 적용)으로 잡혀 있고, 어차피 앱의 온도·차트
전체가 같은 컬럼에 의존한다. 반면 (A)는 앱 쪽 신규 파일 2~3개와 두 번째 폴링 루프를
통째로 없앤다. **디바이스 상한이 10개(F24)로 고정돼 있다는 점이 (A)의 성능 리스크를
구조적으로 막아준다** — 이것이 판단의 핵심 근거다.

#### 성능 계약 (F10 근방 "3초 폴링" 전제와의 충돌 검토)

`/devices`는 `DeviceListFragment`·`SystemHealthFragment`에서 3초 주기로 폴링된다(F27).
여기에 `temperature_log` 조회가 붙으므로 아래를 **하드 요구사항**으로 명시한다.

1. **단일 SQL 문으로 처리할 것. 파이썬 루프로 디바이스마다 쿼리하지 말 것(N+1 금지).**
   `list_devices()`는 현재 `db.execute(...)` 1회다(F22). 이 성질을 유지한다.
2. **최신 1행 조회는 `idx_device_created`(F23)를 타야 한다.** 즉 조건은 반드시
   `WHERE device_id = ? ... ORDER BY created_at DESC` 형태여야 한다.
   `GROUP BY device_id` 후 `MAX(id)`로 되짚는 방식은 인덱스 두 번째 컬럼이 `created_at`이라
   최적화가 보장되지 않으므로 피한다.
3. **최신 온도에 신선도 상한(recency bound)을 건다.** 상한 밖이면 `NULL`.
   - 성능 근거: 상한이 없으면 1년치 이력(디바이스당 수만 행)이 후보가 된다.
   - **의미론 근거가 더 중요하다** — 몇 시간 전 온도로 지금 알림을 띄우면 오경보다.
     `/devices`의 status가 이미 `15 SECOND` 룰로 신선도를 판정하고 있으므로(F22) 같은 사상이다.
   - 잠정값 **10분**(`LATEST_TEMP_MAX_AGE`). §6 U9.
4. **동률 타이브레이크**: `created_at`은 `DATETIME`(초 해상도)이고 실측 주기는 1초라
   같은 초에 2행이 들어올 수 있다. 정렬은 `ORDER BY created_at DESC, id DESC`로 고정해
   **디바이스당 정확히 1행**만 나오게 한다. (JOIN 형태로 구현할 경우 이 누락이 곧
   응답 item 중복 → 앱 목록 중복 표시로 이어진다.)

권장 SQL 형태(server-engineer가 EXPLAIN으로 확정할 것):

```sql
SELECT d.device_id, d.equipment_id,
       CASE WHEN d.last_seen_at > NOW() - INTERVAL 15 SECOND THEN d.status
            ELSE 'Disconnected' END AS status,
       d.installed_on, d.last_seen_at, d.created_at, d.updated_at,
       lt.temp1      AS latest_temp1,
       lt.core_temp  AS latest_core_temp,
       lt.created_at AS latest_temp_at
FROM device d
LEFT JOIN temperature_log lt
       ON lt.id = (SELECT t.id
                     FROM temperature_log t
                    WHERE t.device_id = d.device_id
                      AND t.created_at >= NOW() - INTERVAL 10 MINUTE
                    ORDER BY t.created_at DESC, t.id DESC
                    LIMIT 1)
ORDER BY CAST(SUBSTRING_INDEX(d.device_id, '_', -1) AS UNSIGNED)
```

- 상관 서브쿼리가 디바이스당 1회 = **최대 10회 인덱스 seek**(F24), 왕복은 1회.
- `equipment_id` 필터 분기(현행 177-183행)는 그대로 유지한다.
- MySQL 버전에 따라 윈도우 함수(`ROW_NUMBER() OVER (PARTITION BY ...)`)가 더 나을 수
  있으나, EC2의 MySQL 버전이 **미확인**이라 5.7에서도 도는 위 형태를 기본안으로 둔다. §6 U11.

| 파일 | 신규/수정 | 규모 | 변경 내용 |
|------|-----------|------|-----------|
| `C:\in_gps_server\app.py` | 수정 | **S** | `list_devices()`(167-184행)의 `base_sql`에 LEFT JOIN + 3개 컬럼 추가. 라우트 시그니처·응답 래핑(`{"items": [...]}`)은 무변경 |
| `C:\in_gps_server\README.md` | 수정 | S | `/devices` 응답 필드표 갱신 (담당: `ingps-doc-writer`) |

**서버에서 하지 않는 것:** 임계 판정·알림 생성은 **전부 앱**에서 한다. 서버는 값만 내려준다.
threshold를 서버에 저장하지 않으므로 DB 변경도 없다(사용자 결정 1: 로컬 알림).

### 2-B. Android 앱 상세 (담당: `ingps-android-engineer`)

#### 설계 판단: 전역 감시 아키텍처 — **Activity 스코프 폴러**

세 후보를 실제 코드 기준으로 비교했다.

| 후보 | 판정 | 근거 |
|------|------|------|
| `ProcessLifecycleOwner` + `Application` 서브클래스 | **불채택** | `androidx.lifecycle:lifecycle-process` **신규 의존성**이 필요하다(현재 livedata/viewmodel만 있음, F20). 그런데 Activity가 `MainActivity` 하나뿐이라(F19) 이 라이브러리가 주는 "여러 Activity를 가로지르는 포그라운드 판정"이 **이 앱에는 존재하지 않는 문제**다. 비용만 있고 이득이 없다 |
| 기존 `DeviceListViewModel`(3초) 확장·재사용 | **불채택** | 그 폴링은 **의도적으로** Fragment 생명주기에 묶여 있다 — "탭 전환 시 한쪽만 돌게 되어 중복 해소"라고 주석에 명시(F27). 전역용으로 바꾸면 `SystemHealthFragment`와의 중복 제거 설계가 깨지고, 목록 화면을 떠나면 감시가 멈추는 원래 문제도 해결되지 않는다 |
| **`MainActivity` 스코프 신규 `TempWatchViewModel`** | **채택** | F19/F20에 의해 `MainActivity` started 구간 ≡ 앱 포그라운드. `onStart`/`onStop`에서 폴링을 켜고 끄면 AC-4와 AC-12를 동시에 만족한다. 신규 의존성 0개. `RealtimeDetailDialogFragment`는 Activity를 stop시키지 않으므로(F21) rev.1의 "다이얼로그 중 감시 공백" 한계도 자동 해소된다 |

**MVVM 경계 준수 방식(AC-13):**

```
MainActivity.onStart()
  └─ tempWatchViewModel.startPolling()            // Context 전달 안 함
        └─ DeviceRepository.fetchDevices(...)     // 기존 Repository 재사용
              └─ LiveData<List<DeviceModel>> devices
                    └─ MainActivity.observe(this, list -> {   ← Context는 여기서만
                          TempAlertNotifier.check(this, list);
                       })
MainActivity.onStop()
  └─ tempWatchViewModel.stopPolling()
```

`TempWatchViewModel`은 `Context`를 **인자로도 받지 않는다.** 알림 발행은 전적으로
`MainActivity`(=`Context`)의 observe 콜백에서 일어난다. `TempAlertNotifier`는 rev.1대로
static 메서드이며 `Context`를 보관하지 않는다.

| 파일 | 신규/수정 | 규모 | 변경 내용 |
|------|-----------|------|-----------|
| `AndroidManifest.xml` | 수정 | S | `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` 1줄 추가 |
| `res/drawable/ic_notification_alert.xml` | **신규** | S | 알림 small icon용 단색 벡터(F14). 24dp, 알파 채널만 사용 |
| `util/TempAlertNotifier.java` | **신규** | **M** | 채널 생성 / 임계 판정 / 히스테리시스 상태 기록 / `NotificationCompat` 발행. **rev.2: `checkCore()` + `checkSurface()` 2종**, 그리고 목록 전체를 한 번에 도는 `check(Context, List<DeviceModel>)` 진입점. §3-B/§3-C/§3-D 계약 참조 |
| `viewmodel/TempWatchViewModel.java` | **신규 `rev.2`** | **S~M** | Activity 스코프 전역 폴러. `DeviceRepository.fetchDevices()`를 `WATCH_INTERVAL_MS` 주기로 호출해 `LiveData<List<DeviceModel>>` 노출. `startPolling()`/`stopPolling()`/`onCleared()` 구조는 `DeviceListViewModel`(17-55행)을 그대로 따른다. **Context 미보유** |
| `screen/MainActivity.java` | 수정 | **M `rev.2 상향`** | (a) `onCreate`: `TempAlertNotifier.ensureChannel(this)`, API 33+ `POST_NOTIFICATIONS` 런타임 요청, `TempWatchViewModel` 획득 + observe → `TempAlertNotifier.check(this, list)`. (b) `onStart`/`onStop` 오버라이드 신규 — 폴링 시작/정지 |
| `model/DeviceModel.java` | 수정 `rev.2` | S | `latestTemp1`/`latestCoreTemp`(**wrapper `Float`**) + `latestTempAt`(String) 필드 3개 추가. `@SerializedName` 필수 (§3-A) |
| `res/layout/fragment_settings.xml` | 수정 | **M** | "위험 온도" 섹션 아래에 코어 임계 카드 추가 — `picker_core_threshold`, `btn_save_core_threshold`, 설명 TextView. 기존 `card_threshold` 블록(299-360행) 구조 복제 |
| `fragment/SettingsFragment.java` | 수정 | S | `setupCoreThreshold(view)` 추가 — 기존 `setupThreshold()`(72-94행)와 동일 패턴, 키만 `core_threshold_c` |
| `fragment/SensorDetailFragment.java` | **수정 → `rev.2` 변경 없음** | — | rev.1은 여기 observe 콜백에 판정을 붙이려 했다. **rev.2에서는 붙이지 않는다.** 전역 폴러가 이미 모든 디바이스를 감시하므로, 여기에도 걸면 같은 초과에 대해 두 경로가 동시에 판정해 중복·경합이 생긴다. 판정 지점은 **`MainActivity` 한 곳**으로 유지한다 |
| `fragment/SensorDetailTestFragment.java` | 수정 | S | 클래스 주석(37-44행) 정정만. 코드 무변경 (§2-1) |

#### 폴링 중복에 대한 판단

`DeviceListFragment`가 보이는 동안에는 `DeviceListViewModel`(3초)과 `TempWatchViewModel`이
같은 `/devices`를 각각 폴링한다.

- **이번 스코프에서는 중복을 허용한다.** 응답이 ≤10행(F24)으로 작고, 두 ViewModel을 합치려면
  F27의 기존 중복 제거 설계와 `SystemHealthViewModel`까지 건드리는 리팩터링(M~L)이 된다.
- 대신 `WATCH_INTERVAL_MS`를 **5초**로 둔다(목록의 3초와 다르게). 알림 지연 5초는 요구사항상
  충분하고, 목록 폴링보다 느슨해 서버 부하 증가를 억제한다. → §6 U8.

### §2-1. `SensorDetailTestFragment` 판정 — **이번 스코프에서 코드 변경 제외 (rev.1 유지)**

**F4로 레거시가 아님이 확정됐다.** 다만 rev.2에서 제외 근거 하나가 **사라졌다**:

1. `coreTemp`를 표시하지도 사용하지도 않는다(F6) — **유효**. 여기에 판정을 붙일 이유가 없다.
2. ~~폴링 상호 배타로 감시 공백이 생긴다~~ — **rev.2에서 무효.** 전역 폴러는 Activity 스코프라
   다이얼로그가 열려 있어도 계속 돈다(F21). **이 한계는 해소됐다.**
3. 이 화면에 core_temp 표시까지 붙이는 것은 별도 요청으로 다룬다 — **유효**.

**포함할 것:** 클래스 주석(37-44행)의 낡은 서술("테스트용", "디바이스 클릭 시 열리는")을
현행("실시간 상세보기 다이얼로그 본문")으로 정정(F5).
**포함하지 않을 것:** `getDangerThresholdC()` 중복 2벌(F2) 통합. `core_threshold_c`를 여기에
복제하지도 않는다 — 신규 키의 읽기 지점은 `TempAlertNotifier` **한 곳**으로 유지한다.

---

## 3. 경계면 계약 변경

### 3-A. 네트워크 경계면 — **`rev.2` `GET /devices`가 변경된다**

> **rev.1의 "3-A: 변경 없음"은 무효다.** U1=(b) 확정으로 서버 응답 shape이 바뀐다.

#### 3-A-1. `GET /devices` 응답 (FastAPI `list_devices` → Retrofit `DeviceModel`)

**변경 전** (`app.py:170-176`, `DeviceModel.java:5-20`):

| JSON 키 | 타입 | Java 필드 | Java 타입 |
|---------|------|-----------|-----------|
| `device_id` | string | `deviceId` | `String` |
| `equipment_id` | string | `equipmentId` | `String` |
| `status` | string | `status` | `String` |
| `installed_on` | string? | `installedOn` | `String` |
| `last_seen_at` | string? | `lastSeenAt` | `String` |
| `created_at` | string | `createdAt` | `String` |
| `updated_at` | string | `updatedAt` | `String` |

**변경 후 (추가분만 — 위 7개 필드는 이름·타입·의미 전부 불변, AC-11):**

| JSON 키 | 타입 | Java 필드 | Java 타입 | 의미 |
|---------|------|-----------|-----------|------|
| `latest_temp1` | **float 또는 null** | `latestTemp1` | **`Float`** (wrapper 필수) | 최신 표면 온도 °C. 최근 `LATEST_TEMP_MAX_AGE`(잠정 10분) 내 행이 없으면 `null` |
| `latest_core_temp` | **float 또는 null** | `latestCoreTemp` | **`Float`** (wrapper 필수) | 최신 AI 추정 코어 온도 °C. F17에 의해 **현재는 항상 `null`** |
| `latest_temp_at` | string 또는 null | `latestTempAt` | `String` | 위 두 값의 `created_at`. 신선도 표시·디버깅용 |

- **응답 래핑은 `{"items": [...]}` 그대로**다. `DeviceModelResponse.items` 무변경.
- **`temp2`는 응답에 추가하지 않는다.** 알림 비대상(U3 확정)이고, 목록 화면도 쓰지 않는다.
  불필요한 필드를 3초 폴링 응답에 넣지 않는다.
- **wrapper `Float` 필수 근거:** primitive `float`이면 Gson이 JSON `null`을 **0.0f로 채운다.**
  이 프로젝트에 이미 걸린 함정이며 `TemperatureModel.java:19-23`에 경고가 박혀 있다(F9).
  0.0f가 되면 "온도 0°C인 정상 디바이스"로 오인되고, 알림 판정에서는 임계 미만으로 처리돼
  **조용히 알림이 안 뜨는** 실패가 된다.
- **`@SerializedName` 누락 시 증상:** 컴파일 통과, 크래시 없음, 값만 항상 null →
  알림이 영원히 안 뜬다. 이 시스템에서 가장 잡기 어려운 버그 유형이다.

**영향받는 파일 (양쪽):**
- 서버: `C:\in_gps_server\app.py` `list_devices()` (167-184행)
- 앱: `C:\AndroidProject\IN_GPS\app\src\main\java\com\example\in_gps\model\DeviceModel.java`
- 검증: `C:\in_gps_server\web\app.js:70` (필드 추가에 영향 없음을 확인만, F25)

#### 3-A-2. 그 외 경계면 — 변경 없음

| 경계면 | 변경 | 근거 |
|--------|------|------|
| ESP mfg_data[13..14] → 게이트웨이 | **없음** | 페이로드를 건드리지 않는다 |
| MQTT `ingps/sensor` 페이로드 | **없음** | `core_temp` 키 이미 존재 |
| FastAPI `SensorLogIn` DTO | **없음** | 쓰기 경로 무변경 |
| `GET /temperature`, `/temperature/chart`, `/sensor`, `/chart/{id}` | **없음** | `core_temp` 이미 포함(F16) |
| `TemperatureResponse` / `TemperatureModel` | **없음** | `coreTemp` 필드 이미 존재, `@SerializedName` 정합(F9) |
| `ApiService` 메서드 목록 | **없음** | `getDevices()` 재사용 — **신규 엔드포인트를 만들지 않기로 한 §2-A 결정의 직접 효과** |
| DB `temperature_log` 스키마 | **없음** | §4 참조 |

> **integration-qa 지침 (rev.2 갱신):**
> 1. **3-A-1을 최우선 체크리스트로 변환하라.** `app.py`의 SELECT 별칭과 `DeviceModel`의
>    `@SerializedName` 문자열을 **양쪽 파일을 동시에 열어** 한 글자씩 대조할 것.
>    `latest_temp1` / `latest_core_temp` / `latest_temp_at` 3개다.
> 2. `latestTemp1`·`latestCoreTemp`가 **wrapper `Float`인지** 확인하라. primitive면 즉시 결함이다.
> 3. **회귀 항목(필수):** ① 기존 7개 필드 무변경(AC-11), ② `SystemHealthViewModel`의
>    status 집계(43-50행)가 그대로 동작, ③ 웹 대시보드(F25), ④ 기존 `danger_threshold_c`
>    기반 차트 위험선/음영/마커 무변경(F3).
> 4. **디바이스당 응답 item이 정확히 1개인지** 확인하라 — §2-A 성능계약 4의 타이브레이크
>    누락 시 중복 item이 나온다.
> 5. 3-B(앱 내부 계약) 키 스키마 오타 대조.

### 3-B. 앱 내부 계약: SharedPreferences `in_gps_prefs` 스키마

여러 파일이 문자열 키로 결합되므로 오타 시 조용히 기본값으로 동작한다(컴파일 통과, 크래시 없음).

**변경 전:**

| 키 | 타입 | 기본값 | 쓰기 | 읽기 |
|----|------|--------|------|------|
| `danger_threshold_c` | Float | 40f | `SettingsFragment:88` | `SensorDetailFragment:528`, `SensorDetailTestFragment:422` |
| (측정 주기 관련 키) | — | — | `SettingsViewModel` | `SettingsViewModel` |

**변경 후 (추가분만):**

| 키 | 타입 | 기본값 | 범위 | 쓰기 | 읽기 |
|----|------|--------|------|------|------|
| `core_threshold_c` | Float | **80f** (§6 U2 잠정) | 20~200 | `SettingsFragment.setupCoreThreshold()` | `TempAlertNotifier` **단 한 곳** |
| `core_alert_active_<deviceId>` | Boolean | false | — | `TempAlertNotifier` | `TempAlertNotifier` |
| `core_alert_last_ms_<deviceId>` | Long | 0 | — | `TempAlertNotifier` | `TempAlertNotifier` |
| **`surf_alert_active_<deviceId>`** `rev.2` | Boolean | false | — | `TempAlertNotifier` | `TempAlertNotifier` |
| **`surf_alert_last_ms_<deviceId>`** `rev.2` | Long | 0 | — | `TempAlertNotifier` | `TempAlertNotifier` |

- **`danger_threshold_c`는 키·타입·기본값·범위·기존 읽기 지점 전부 무변경**이다.
  AC-9(temp1 알림)는 이 **기존 키를 그대로 읽는다** — 신규 키를 만들지 않는다(사용자 결정 2 재확인).
  `TempAlertNotifier`가 세 번째 읽기 지점이 되지만, F2의 중복 2벌에 3벌째를 더하지 않도록
  **`TempAlertNotifier` 내부에 private 헬퍼 하나**로만 둔다.
- **알림 상태 키를 센서별(`core_` / `surf_`)로 분리한다.** 하나로 합치면 코어 초과 중일 때
  표면 초과 알림이 억제된다 — 두 센서는 독립적으로 임계를 넘나든다.
- 알림 상태 키는 **디바이스별 접미사**를 붙인다. 전역 상태 키를 쓰면 esp_32_0이 초과 중일 때
  esp_32_1의 알림이 억제된다.
- 키 문자열은 `TempAlertNotifier`의 `private static final String` 상수로만 정의한다.

### 3-C. 알림 채널 계약 (신설)

| 항목 | 값 | 비고 |
|------|-----|------|
| Channel ID | `ingps_temp_alert` | 상수화. **한 번 생성된 채널의 importance는 코드로 변경 불가** — 재설치 전까지 고정 |
| Channel name | `온도 임계 알림` | 시스템 설정 화면에 노출 |
| Importance | `IMPORTANCE_HIGH` | 헤드업 알림. 화재 예방 목적상 타당 |
| Notification ID | **`(deviceId + ":" + sensor).hashCode()`** `rev.2` | rev.1은 `deviceId.hashCode()`였다. 센서 2종이 되었으므로 센서 키를 섞지 않으면 표면 알림이 코어 알림을 덮어쓴다 |
| 생성 시점 | `MainActivity.onCreate` | Application 서브클래스 없음(F11). 채널 생성은 멱등이라 반복 호출 안전 |
| API 분기 | `Build.VERSION.SDK_INT >= O`에서만 채널 생성 | minSdk 24 (F12) |

### 3-D. 알림 트리거 계약 (`rev.2` 전면 개정)

```
MainActivity.onStart()
   └─ TempWatchViewModel.startPolling()          (WATCH_INTERVAL_MS = 5초)
        └─ DeviceRepository.fetchDevices()  →  GET /devices
             └─ LiveData<List<DeviceModel>>
                  └─ MainActivity.observe(...)           ← Context는 여기서만
                       └─ TempAlertNotifier.check(this, list)
                            └─ for each DeviceModel d:
                                 checkCore(ctx, d.deviceId, d.latestCoreTemp)
                                 checkSurface(ctx, d.deviceId, d.latestTemp1)
MainActivity.onStop()
   └─ TempWatchViewModel.stopPolling()           (AC-12: 요청 완전 정지)
```

**판정 로직 (센서별 동일, 히스테리시스 — AC-6):**

```
입력: value(Float, null 가능), threshold, prefix ∈ {"core", "surf"}
  threshold = core: prefs(core_threshold_c, 80f)
              surf: prefs(danger_threshold_c, 40f)     ← 기존 키 그대로
HYSTERESIS_C   = 2.0f
MIN_RENOTIFY_MS = 10 * 60 * 1000

if (value == null) return;                                  // AC-7 / AC-10
active = prefs(<prefix>_alert_active_<dev>, false)

if (!active && value >= threshold) {
    notify(); active = true; last_ms = now;
} else if (active && value <= threshold - HYSTERESIS_C) {
    active = false;                                          // 복귀
} else if (active && now - last_ms >= MIN_RENOTIFY_MS && value >= threshold) {
    notify(); last_ms = now;                                 // 장기 지속 리마인드
}
```

- `MIN_RENOTIFY_MS` 리마인드는 §6 U5. 불필요하면 분기 제거(비용 S).
- **알림 문구는 센서별로 다르다.** core_temp 알림에만 AC-5(d)의 "AI 추정치 · 참고용" 고지를
  넣는다. 표면 온도는 실측이므로 그 문구를 붙이면 오히려 오해를 부른다.
- **`SensorDetailFragment`의 observe 콜백에는 판정을 붙이지 않는다**(§2-B 표 참조).
  판정 진입점은 `MainActivity` 하나뿐이다.

---

## 4. DB 스키마 변경

**신규 DDL 필요 없음.**

- DDL: 없음
- 신규 EC2 적용: 없음
- 근거: threshold는 기기 로컬 사용자 설정이며 서버로 전송하지 않는다. 알림도 기기 내에서
  생성된다(사용자 결정 1). `latest_*` 필드는 기존 `temperature_log` 컬럼을 읽어 파생할 뿐이다.

> ### `rev.2` 격상된 경고 — 선행 조건이 됨
>
> `in_gps_db_ver_6.sql`(= `temperature_log.core_temp` 컬럼 추가)의 **EC2 적용 여부는 여전히
> 미확인**이다 (`_workspace_prev/CHANGELOG.md` 미해결 #2).
>
> **rev.1에서 이것은 "온도·차트가 멈춘다"는 문제였다. rev.2에서는 더 커진다** —
> `GET /devices`가 `core_temp`를 SELECT하게 되므로, 컬럼이 없으면 **디바이스 목록 화면과
> 시스템 상태 화면까지 통째로 500**이 된다. 즉 앱의 첫 화면이 뜨지 않는다.
>
> **→ 서버 변경 배포 전에 사용자가 반드시 확인해야 한다:**
> ```sql
> SHOW COLUMNS FROM temperature_log LIKE 'core_temp';
> ```
> 결과가 비어 있으면 `in_gps_db_ver_6.sql`을 먼저 적용한 뒤 서버를 배포한다.
> (적용·배포 모두 **사용자 수행.** §6 U7)

---

## 5. 구현 순서

의존 방향: **서버(데이터 생산자) → 앱 모델 → 앱 인프라 → 설정 저장 → 판정 유틸 → 전역 폴러 연결 → 검증.**

**`rev.2` 서버가 1번으로 올라온 이유:** 앱의 전역 폴러는 `/devices` 응답에 온도가 실려 와야
동작한다. 응답 shape이 확정되기 전에 앱 쪽 판정을 구현하면 필드명 추측이 들어가고,
그 추측이 틀려도 **Gson은 조용히 null을 넣어 컴파일도 통과한다**(3-A-1 경고).
"데이터 생산자가 소비자보다 앞선다"는 원칙이 이 시스템에서 특히 강하게 적용되는 지점이다.

| # | 레이어 | 작업 | 담당 |
|---|--------|------|------|
| **0** | **사용자** | **선행 확인: EC2에 `temperature_log.core_temp` 존재 여부(§4 SQL). 없으면 `in_gps_db_ver_6.sql` 적용** | **사용자** |
| **1** | **서버** | `app.py list_devices()`에 최신 온도 LEFT JOIN + `latest_temp1`/`latest_core_temp`/`latest_temp_at` 추가. §2-A 성능계약 4개 항목 준수. `equipment_id` 필터 분기 유지 | `ingps-server-engineer` |
| **2** | **서버** | 로컬에서 `EXPLAIN` 확인 — 서브쿼리가 `idx_device_created`를 타는지, 디바이스당 1행인지 | `ingps-server-engineer` |
| **3** | **앱** | `DeviceModel.java`에 필드 3개 추가 (**wrapper `Float`**, `@SerializedName`) — 1번의 SELECT 별칭과 문자 단위 일치시킬 것 | `ingps-android-engineer` |
| 4 | 앱 | `AndroidManifest.xml`에 `POST_NOTIFICATIONS` 추가 + `res/drawable/ic_notification_alert.xml` 신규 | `ingps-android-engineer` |
| 5 | 앱 | `fragment_settings.xml`에 코어 임계 카드 추가 | `ingps-android-engineer` |
| 6 | 앱 | `SettingsFragment.setupCoreThreshold()` — `core_threshold_c` 로드/저장. 기존 `setupThreshold()`는 **손대지 않는다** | `ingps-android-engineer` |
| 7 | 앱 | `util/TempAlertNotifier.java` 신규 — `ensureChannel()` / `check(Context, List<DeviceModel>)` / `checkCore()` / `checkSurface()`. §3-B·3-C·3-D 계약대로 | `ingps-android-engineer` |
| **8** | **앱** | `viewmodel/TempWatchViewModel.java` 신규 — Activity 스코프 5초 폴러. `DeviceListViewModel` 구조 답습, **Context 미보유** | `ingps-android-engineer` |
| **9** | **앱** | `MainActivity` — 채널 생성 + API 33+ 권한 요청 + `TempWatchViewModel` observe → `TempAlertNotifier.check(...)` + `onStart`/`onStop` 폴링 제어 | `ingps-android-engineer` |
| 10 | 앱 | `SensorDetailTestFragment` 클래스 주석 정정만 (§2-1). 코드 무변경 | `ingps-android-engineer` |
| **11** | **QA** | **§3-A-1 필드 대조(서버 SELECT 별칭 ↔ `@SerializedName` ↔ Java 타입)**, §3-B 키 스키마 오타 대조, §3-D 상태 전이 검증, **회귀: 기존 7필드·SystemHealth 집계·웹 대시보드·`danger_threshold_c` 차트 경로**, null 안전성(AC-7/AC-10), 응답 item 중복 없음 | `ingps-integration-qa` |
| 12 | 문서 | `_workspace/CHANGELOG.md` + `C:\in_gps_server\README.md` 갱신 — `/devices` 응답 필드 3종, prefs 키 5종, 채널 ID, 알림 트리거 지점, §6 미확정 항목 | `ingps-doc-writer` |

**사용자 수행 항목(에이전트가 하지 않음):** EC2 DB 컬럼 확인/DDL 적용, 서버 배포·재시작,
Gradle 빌드, 실기기 설치, 알림 권한 허용, 알림 육안 확인, git commit/push.

---

## 6. 미확인 사항 / 사용자 확인 필요

### U1. 알림 커버리지 — ✅ **확정 (2026-09-01)**

**결정: (b) 앱 포그라운드 전역 감시.** (a) "해당 디바이스 상세 화면을 열어둘 때만"은 **거부됨.**

사용자 요구사항 원문: *"앱이 포그라운드에 있는 동안은 어느 화면에서든(다른 디바이스 목록·설정
화면이라도) 알림이 떠야 한다."*

| 선택지 | 상태 |
|--------|------|
| (a) 상세 화면 열람 중에만 | ❌ 거부 |
| **(b) 포그라운드 전역 감시** | ✅ **채택** |
| (c) 백그라운드 감시 (`WorkManager`/포그라운드 Service) | ❌ 스코프 밖 (유지) |
| (d) 서버 푸시 (FCM) | ❌ 사용자가 이미 배제 (유지) |

**이 결정의 파급 (rev.2가 반영한 것):**
- rev.1 U1 표가 예고한 대로 **"서버 `GET /devices` 응답에 최신 온도 추가 → 서버 레이어 변경 발생"** 이 현실화됐다. §2-A.
- 앱에 화면 독립적인 Activity 스코프 폴러가 추가된다. §2-B.
- rev.1이 "알려진 한계"로 기록했던 **실시간 다이얼로그 중 감시 공백이 해소**된다(F21).
- **백그라운드/앱 종료 상태는 여전히 스코프 밖**이다. 로컬 알림 유지, FCM 아님(사용자 결정 1).
  → 사용자에게 이 잔여 한계는 **계속 명시 보고**해야 한다: 앱을 완전히 내리면 알림은 오지 않는다.

### U2. 코어 임계값의 기본값과 picker 범위 — 잠정값으로 진행 (변경 없음)

기존 `danger_threshold_c`는 기본 40°C / 범위 20~120이다. `core_temp`는 성격이 다르다:
- 서버는 core_temp에 `-20~80°C` 유효범위 필터를 **의도적으로 적용하지 않는다**
  (`in_gps_db_ver_6.sql:22-25` — 80°C 초과가 설계상 정상).
- SHF 모델의 이론적 출력 범위는 -141.5 ~ 138.1°C.

→ **잠정 채택: 기본 80f, 범위 20~200.** 상수 2개 수정으로 변경 가능(구현 차단 없음).
`NumberPicker`는 int만 다루므로 기존과 동일하게 int 표시 / Float 저장한다.

### U3. 알림 대상 센서 — ✅ **확정 (2026-09-01)**

**결정: `core_temp` + `temp1`("표면") 두 센서만. `temp2`("외부")는 제외.**

- 사용자가 말한 **"Ambient_temp" = `temp1`(표면)** 으로 확정됐다.
  근거로 든 문구: *"기존 이벤트/경고 로직이 이미 쓰는 센서"*.
  이는 코드와 일치한다 — `SensorDetailFragment.java:481-482`에
  **"경고=표면(temp1) 임계 기반이고 마커 y좌표도 표면 평균"** 이라고 적혀 있다(F26).
- **AC-9가 선택 확장에서 필수(core_temp와 동급)로 승격**됐다. §1.
- **threshold 구조는 기존 결정(사용자 결정 2) 유지 — 재확인됨.**
  temp1/temp2가 공유하는 `danger_threshold_c`를 센서별로 분리하는 리팩터링은 **하지 않는다.**
  temp1 알림 판정에는 **기존 `danger_threshold_c` 값을 그대로 사용**한다(신규 키 불필요).
  core_temp에는 신규 `core_threshold_c`를 사용한다(기존 계획대로).
- temp2는 `/devices` 응답에도 넣지 않는다(§3-A-1) — 쓰지 않는 값을 3초 폴링에 싣지 않는다.

### U4. 실동작 검증 경로 — **core_temp는 여전히 검증 불가**

F17에 따라 **`core_temp`는 현재 항상 NULL**이므로, 구현을 마쳐도 코어 알림이 실제로 뜨는 것을
볼 수 없다(AC-4의 core 경로를 실기로 확인 불가). AC-7/AC-10(null 안전)만 관측 가능하다.

**`rev.2` 변화:** **AC-9(표면 온도 알림)는 실측값이 흐르고 있으므로 실기 검증이 가능하다.**
따라서 rev.2에서는 전역 감시 아키텍처(AC-4/AC-12/AC-13) 자체를 표면 온도로 end-to-end 검증할 수 있다.
core_temp 경로만 "미검증"으로 남는다.

core_temp 검증 수단(모두 사용자 수행):
1. 게이트웨이의 `mfg_data[13..14]` 파싱 완료를 기다린다 — 세션 밖 담당자.
2. 서버에 `POST /sensor`로 `core_temp` 값을 넣은 테스트 행을 1건 삽입하고 임계값을 그보다
   낮게 설정한다. **주의: §2-A의 10분 신선도 상한 안에 들어와야 `/devices`에 실린다.**

→ 어느 경로든 **"AC-4의 core_temp 경로는 미검증"으로 보고**한다. 통과로 뭉뚱그리지 않는다.

### U5. 지속 초과 시 리마인드 알림 여부 — 잠정 (변경 없음)

AC-6은 "반복 알림 금지"만 요구한다. §3-D의 `MIN_RENOTIFY_MS`(10분 리마인드)는 화재 예방
목적상 유용할 수 있으나 요청에 없다.
→ **잠정: 포함하되 상수 하나로 끌 수 있게 구현.** 불필요 판정 시 분기 삭제(비용 S).

### U6. 알림 탭 시 이동 대상 — 잠정 (변경 없음)

→ **잠정: `MainActivity`를 여는 `PendingIntent`(`FLAG_IMMUTABLE` 필수, API 31+)만 붙인다.**
해당 디바이스의 `SensorDetailFragment`로 딥링크하려면 `MainActivity`에 인텐트 extra 처리
분기가 추가로 필요하다(규모 S~M) — 요청 범위 밖으로 판단해 제외.

### `rev.2` U7. **`/devices`가 `core_temp` 컬럼에 의존하게 되는 위험 — 배포 전 확인 필수**

§4의 격상된 경고와 동일 항목. **이번 개정이 새로 만든 위험**이므로 U 항목으로 승격한다.

**막히는 것:** 서버 배포. EC2에 `temperature_log.core_temp`가 없는 상태에서 1번 작업을
배포하면 **디바이스 목록·시스템 상태 화면이 500으로 죽는다**(rev.1에서는 차트만 죽었다).

→ 사용자가 `SHOW COLUMNS FROM temperature_log LIKE 'core_temp';`로 확인하고 결과를 알려줘야
구현 순서 1번을 배포할 수 있다. 미적용이면 `in_gps_db_ver_6.sql` 선적용.

### `rev.2` U8. `/devices` 폴링 중복 허용 여부 — 잠정 진행 가능

`DeviceListFragment`가 보이는 동안 `DeviceListViewModel`(3초)과 `TempWatchViewModel`(5초)이
같은 엔드포인트를 각각 폴링한다. §2-B에서 **허용**으로 판단했다(응답 ≤10행, 통합 시 F27의
기존 설계와 `SystemHealthViewModel`까지 건드리는 M~L 리팩터링).

→ **막히는 것: 없음.** 다만 사용자가 서버 부하나 배터리를 우려하면 후속 작업으로
"Activity 스코프 단일 폴러로 통합"을 별도 요청하면 된다.

### `rev.2` U9. 최신 온도 신선도 상한(`LATEST_TEMP_MAX_AGE`) 값 — 잠정 10분

`/devices`가 얼마나 오래된 온도까지 최신값으로 인정할지. 잠정 10분.
- 너무 길면 정지된 설비의 과거 온도로 오경보가 난다.
- 너무 짧으면 통신이 잠깐 끊긴 디바이스가 계속 `null`이 되어 감시 공백이 생긴다.
- 참고: `/devices`의 status는 `15 SECOND` 룰을 쓴다(F22). 알림용으로 15초는 너무 빡빡하다.

→ **잠정 10분으로 진행.** 상수 1개 수정. 사용자가 다른 값을 원하면 알려주면 된다.

### `rev.2` U10. **표면 온도 알림이 기본값 40°C에서 즉시 폭주할 가능성 — 사용자 판단 필요**

AC-9는 기존 `danger_threshold_c`(기본 **40°C**)를 그대로 쓴다. 그런데:
- 시드 데이터 생성 로직의 표면 온도 기준선은 **45°C**이고, `warning` 이벤트는 **70°C**부터다
  (`temperature_db_init.sql:81-85,97`). 즉 **40°C는 "정상 가동 중"에도 상시 초과할 수 있는 값**이다.
- 이 값이 지금까지 문제가 되지 않은 이유는 threshold가 **시각화 전용**이었기 때문이다(F3).
  차트에 위험선이 낮게 그려질 뿐이었다. **알림으로 승격되는 순간 성격이 완전히 달라진다.**
- 최악의 경우 앱 첫 실행 시 **디바이스 10개에 대해 알림 10개가 동시에** 뜬다.

**막히는 것:** 없음(구현은 가능). 하지만 **사용자 경험이 즉시 나빠질 것이 거의 확실**하다.

→ 사용자에게 확인 필요: **(가)** 그대로 40°C로 두고 사용자가 설정에서 직접 올린다,
**(나)** 표면 알림용 기본값만 별도 상수(예: 70°C — 서버 `warning` 기준과 정렬)로 두되
**prefs 키는 여전히 `danger_threshold_c` 하나만 쓴다**(= 사용자가 값을 저장한 적 없을 때의
`getFloat` 기본값 인자만 알림 경로에서 다르게 준다 — 리팩터링 아님, 키 추가 아님),
**(다)** 알림 도입에 맞춰 `danger_threshold_c` 기본값 자체를 올린다(차트 위험선도 함께 이동).

**분석가 권고: (나).** 사용자 결정 2(구조 무변경)를 위반하지 않으면서 알림 폭주만 막는다.
다만 "같은 키인데 읽는 곳마다 기본값이 다르다"는 미묘함이 생기므로 주석 필수.

### `rev.2` U11. EC2 MySQL 버전 — 미확인 (진행 차단 아님)

§2-A의 권장 SQL은 MySQL 5.7에서도 도는 상관 서브쿼리 형태다. EC2가 MySQL 8.x라면
`ROW_NUMBER() OVER (PARTITION BY device_id ORDER BY created_at DESC, id DESC)`가
더 깔끔하고 빠를 수 있다.
→ **막히는 것: 없음.** 기본안으로 진행하고, server-engineer가 `SELECT VERSION();` 결과를
사용자에게 요청해 확인되면 최적화할 수 있다.

---

## 7. 참고: 이 명세가 반복하지 않는 과거 실패

`_workspace_prev/`의 "스코프 정정 이력"에 따르면, 직전 세션의 초기 분석은 **"Android가 BLE를
직접 스캔한다"**고 오판해 앱에 BLE 스캐너·권한·파서 7파일을 만들었다가 전량 되돌렸다.

이번 명세는 그 교훈을 반영해:
- 데이터 도달 경로를 **추정하지 않고 실제 폴링 코드로 확인**했다 (F8, F10, F22, F27).
- 신규 권한은 `POST_NOTIFICATIONS` **1개**뿐이다. BLE/LOCATION 권한은 **추가하지 않는다**.
- **신규 라이브러리 의존성 0개.** `ProcessLifecycleOwner`를 쓰고 싶은 반사적 충동을
  매니페스트 실물 확인(F19)으로 기각했다 — 이 앱은 Activity가 하나다.
- 펌웨어·게이트웨이 "변경 불필요"를 근거와 함께 명시했다(§2).
- **`rev.2`에서 서버를 "변경 불필요"에서 "변경 필요"로 뒤집었다.** rev.1의 판단이 틀린 것이
  아니라 **요구사항(U1)이 바뀌어서 스코프가 늘어난 것**이다. 이 구분을 문서에 남겨,
  하류 엔지니어가 rev.1을 참조하다 "서버 변경 없음"으로 오독하는 것을 막는다.

---

## 변경 이력

| 일시 | 변경 | 사유 |
|------|------|------|
| 2026-09-01 | 최초 작성 | core_temp 임계 설정 + 로컬 알림 요청 분석 |
| 2026-09-01 | **rev.2 개정** — U1=(b) 포그라운드 전역 감시 / U3=core_temp+temp1 확정 반영. §2에 FastAPI 서버 추가(`GET /devices` 응답 확장, 별도 엔드포인트 신설은 트레이드오프 비교 후 기각), §2-B에 Activity 스코프 `TempWatchViewModel` 아키텍처 신설(`ProcessLifecycleOwner` 불채택 — F19/F20), AC-4 재정의·AC-9 필수 승격·AC-10~13 신설, §3-A를 "변경 없음"에서 `/devices` shape 변경으로 개정, §5에 server-engineer를 1순위로 편입, U7~U11 신규 | 사용자가 U1·U3에 대해 결정을 내림. U1=(b) 채택으로 rev.1이 예고했던 "서버 레이어 진입"이 현실화되어 4레이어 중 2개 레이어 명세로 확대됨 |
