# IN-GPS 변경 이력

최신 항목을 위에 추가한다(append). 과거 항목은 편집하지 않는다.

---

## 2026-09-01 — core_temp + 표면온도(temp1) 임계 초과 로컬 알림

`GET /devices` 응답에 디바이스별 최신 온도를 실어 보내고, 앱이 **화면과 무관하게**
포그라운드 전역으로 임계 초과를 감시해 시스템 알림을 띄운다.
FCM이 아니라 **기기 내 로컬 알림**이며, 임계값도 서버가 아니라 `SharedPreferences`에 산다.

### 파이프라인 (최종 아키텍처)

```
temperature_log (temp1, core_temp)                                      [기존 컬럼]
   ↓ 상관 서브쿼리 LEFT JOIN + 신선도 상한 10분
FastAPI  GET /devices  →  latest_temp1 / latest_core_temp / latest_temp_at  [신규]
   ↓ Retrofit (getDevices() 재사용 — 신규 엔드포인트 없음)
DeviceModel.latestTemp1 / latestCoreTemp (wrapper Float) / latestTempAt      [신규]
   ↓
TempWatchViewModel  ─ Activity 스코프 5초 전역 폴러 (Context 미보유)          [신규]
   ↓ LiveData<List<DeviceModel>>
MainActivity.observe(...)          ← Context가 등장하는 유일한 지점
   ↓
TempAlertNotifier.check()  ─ 디바이스 × {core, surf} 히스테리시스 판정        [신규]
   ↓ 게시 성공 시에만 상태 기록
NotificationCompat  채널 ingps_temp_alert / IMPORTANCE_HIGH
```

폴링 시작/정지는 `MainActivity.onStart()` / `onStop()`에 묶여 있다.
이 앱의 Activity는 `MainActivity` 하나뿐이라 **started 구간 ≡ 앱 포그라운드**가 성립하고,
`RealtimeDetailDialogFragment`는 DialogFragment이지 Activity가 아니라 다이얼로그가 떠 있어도
감시가 끊기지 않는다. 그래서 `androidx.lifecycle:lifecycle-process`(`ProcessLifecycleOwner`)
신규 의존성을 쓰지 않았다.

### 변경 파일

**서버 (`C:\in_gps_server`)**

| 파일 | 변경 |
|------|------|
| `app.py` | 모듈 상수 `LATEST_TEMP_MAX_AGE_MINUTES = 10` 신설. `list_devices()`의 `base_sql`을 `device` 단독 조회 → `temperature_log` LEFT JOIN(상관 서브쿼리 + `LIMIT 1`)으로 교체하고 `latest_temp1` / `latest_core_temp` / `latest_temp_at` 3개 별칭 추가. 기존 7개 컬럼은 테이블 별칭 `d.`만 붙었고 `INTERVAL 15 SECOND` CASE 식·`ORDER BY CAST(SUBSTRING_INDEX(...))`·`equipment_id` 필터 분기·응답 래핑 `{"items": [...]}`는 무변경 |
| `README.md` | 엔드포인트 표의 `/devices` 용도 갱신, `GET /devices` 응답 필드표 신규, `temperature_log.core_temp` 절에 "`/devices`도 이제 이 컬럼에 의존" 경고 추가 |

`mqtt_subscriber.py` / DTO / DB DDL / MQTT 토픽·페이로드는 **무변경**.
`temp2`는 알림 비대상이라 응답에 넣지 않았다.

**Android 앱 (`C:\AndroidProject\IN_GPS`)**

| 파일 | 신규/수정 | 변경 |
|------|-----------|------|
| `model/DeviceModel.java` | 수정 | `latestTemp1` / `latestCoreTemp`(**wrapper `Float`**), `latestTempAt`(`String`) 3개 추가. 기존 7필드 무변경 |
| `viewmodel/TempWatchViewModel.java` | **신규** | Activity 스코프 5초 폴러(`WATCH_INTERVAL_MS = 5_000`). 필드는 `MutableLiveData` / `DeviceRepository` / `Handler(mainLooper)` / `boolean polling` 4개뿐 — `Context`를 필드로도 인자로도 받지 않는다 |
| `util/TempAlertNotifier.java` | **신규** | 채널 생성(`ensureChannel`), 진입점 `check(Context, List<DeviceModel>)`, `checkCore()` / `checkSurface()` → 공용 `evaluate()`. 생성자 `private`, 전 메서드 `static`, 인스턴스 필드 0개 |
| `screen/MainActivity.java` | 수정 | `onCreate`: 채널 생성 + API 33+ `POST_NOTIFICATIONS` 요청 + `TempWatchViewModel` observe → `TempAlertNotifier.check()`. `onStart`/`onStop` 오버라이드 신규(폴링 제어) |
| `AndroidManifest.xml` | 수정 | `POST_NOTIFICATIONS` 1줄 추가 |
| `res/drawable/ic_notification_alert.xml` | **신규** | 알림 small icon 24dp 단색 벡터. `fillType="evenOdd"` 단일 path |
| `res/layout/fragment_settings.xml` | 수정 | 기존 `card_threshold` **뒤에 순수 추가 82줄** — `picker_core_threshold`, `btn_save_core_threshold`, 설명 TextView |
| `fragment/SettingsFragment.java` | 수정 | `setupCoreThreshold()` 신규 + `onViewCreated`에 호출 1줄. **`setupThreshold()`는 무수정** |
| `fragment/SensorDetailTestFragment.java` | 수정 | **클래스 Javadoc만** 정정("테스트용" → "실시간 상세보기 다이얼로그 본문"). 코드 0줄 |

**신규 의존성 0개.** `build.gradle` 무수정. `ApiService` / `RetrofitClient` /
`DeviceModelResponse` / `DeviceRepository` / `DeviceListViewModel` / `SystemHealthViewModel`
전부 무변경 — 응답 래핑이 그대로라 `getDevices()`를 재사용했다.

`SensorDetailFragment.java`에는 **판정을 붙이지 않았다.** 전역 폴러가 이미 모든 디바이스를
감시하므로 여기에도 걸면 같은 초과를 두 경로가 판정해 중복·경합이 생긴다. 판정 진입점은
`MainActivity` 한 곳뿐이다.

> `git status`에는 `SensorDetailFragment.java` / `TemperatureModel.java` /
> `fragment_sensor_detail.xml`도 수정으로 잡히지만, 이는 **직전 세션(2026-08-31 core_temp
> 파이프라인)의 미커밋 변경분**이다. 이번 기능으로 인한 변경은 0줄이며 차트 임계 로직
> (`getDangerThresholdC()` / LimitLine / dangerZone / exceedanceMarkers)은 diff에 등장하지 않는다.

### 경계면 계약 (신규분)

**`GET /devices` 응답 추가 필드** — 서버 SELECT 별칭 ↔ `@SerializedName` 한 글자 단위 일치 확인함.

| 서버 별칭 (`app.py`) | JSON 키 | Java 필드 | 타입 | null 조건 |
|---|---|---|---|---|
| `lt.temp1 AS latest_temp1` | `latest_temp1` | `latestTemp1` | **`Float`** (wrapper) | 최근 10분 내 행 없음 / `_valid_temp`(-20~80°C) 필터에 걸려 DB에 NULL |
| `lt.core_temp AS latest_core_temp` | `latest_core_temp` | `latestCoreTemp` | **`Float`** (wrapper) | 최근 10분 내 행 없음 / **게이트웨이 미파싱으로 현재는 상시 null** |
| `lt.created_at AS latest_temp_at` | `latest_temp_at` | `latestTempAt` | `String` | 최근 10분 내 행 없음 |

- **wrapper `Float` 필수.** primitive `float`이면 Gson이 JSON `null`을 `0.0f`로 채워
  "0°C인 정상 디바이스"가 되고, 알림 판정에서는 조용히 임계 미달로 처리돼 알림이 영원히
  뜨지 않는다. `TemperatureModel.temp1`/`temp2`가 이미 걸려 있는 함정이다.
- **`created_at`(디바이스 등록 시각)과 `latest_temp_at`(온도 측정 시각)은 다른 값이다.**
  별칭이 달라 `.mappings()` 키 충돌도 없다.
- `latest_temp_at != null` + `latest_core_temp == null` 조합이 **현재 운영 환경의 정상 상태**다.

**SharedPreferences `in_gps_prefs` 스키마 (추가분)**

| 키 | 타입 | 쓰기 | 읽기 |
|---|---|---|---|
| `core_threshold_c` | Float (기본 80f, picker 20~200) | `SettingsFragment.setupCoreThreshold()` | `TempAlertNotifier` 한 곳 |
| `core_alert_active_<deviceId>` | Boolean | `TempAlertNotifier` | `TempAlertNotifier` |
| `core_alert_last_ms_<deviceId>` | Long | `TempAlertNotifier` | `TempAlertNotifier` |
| `surf_alert_active_<deviceId>` | Boolean | `TempAlertNotifier` | `TempAlertNotifier` |
| `surf_alert_last_ms_<deviceId>` | Long | `TempAlertNotifier` | `TempAlertNotifier` |

`danger_threshold_c`는 키·타입·쓰기 지점 전부 **무변경**이며 표면 알림이 이 기존 키를
그대로 읽는다(신규 키를 만들지 않았다). 상태 키에 **디바이스별 접미사 + 센서별 접두사가
둘 다 필요한 이유**: 접미사가 없으면 `esp_32_0` 초과 중에 `esp_32_1` 알림이 억제되고,
접두사가 없으면 코어 초과 중에 표면 알림이 억제된다. 두 센서는 독립적으로 임계를 넘나든다.

**알림 채널·판정**

| 항목 | 값 |
|---|---|
| Channel ID / name / importance | `ingps_temp_alert` / `온도 임계 알림` / `IMPORTANCE_HIGH` |
| 채널 생성 | `MainActivity.onCreate` (Application 서브클래스 없음, 멱등) |
| Notification ID | `(deviceId + ":" + sensor).hashCode()` — 센서 키를 섞지 않으면 표면 알림이 코어 알림을 덮어쓴다 |
| 히스테리시스 / 리마인드 | `HYSTERESIS_C = 2.0f` / `MIN_RENOTIFY_MS = 10분` |
| PendingIntent | `MainActivity`, `FLAG_UPDATE_CURRENT \| FLAG_IMMUTABLE` |

판정 전이(코어·표면 공용 `evaluate()`):

```
value == null                          → return (알림도, 상태 변경도 없다)
!active && v >= T                      → notify(); 게시 성공 시 active=true, last=now
 active && v <= T - 2.0                → active=false (복귀)
 active && now-last >= 10분 && v >= T  → 리마인드 notify(); 게시 성공 시 last=now
```

알림 문구는 센서별로 다르다. 코어에만 "AI 추정치입니다. 참고용이며 과열 판정의 단독
근거로 사용하지 마세요."를 붙인다 — 표면은 실측이라 이 문구를 붙이면 오히려 오해를 부른다.

### 설계 결정

1. **별도 엔드포인트(`/devices/latest_temps`) 신설 대신 `GET /devices` 응답을 확장했다.**
   앱 쪽 신규 파일 2~3개와 두 번째 폴링 루프를 통째로 없앤다. 디바이스 상한이 10개
   (`esp_32_{0..9}`, mfg_data 계약)로 고정돼 있다는 점이 성능 리스크를 구조적으로 막아준다.
   웹 대시보드는 `d.device_id` 하나만 읽고 필드를 열거하지 않으므로 필드 추가는 하위 호환이다.
   **대가**: `/devices`가 `temperature_log`·`core_temp` 컬럼에 새로 의존하게 됐다(아래 O-1 / 배포 전 확인 참조).

2. **최신 1행은 상관 서브쿼리 + `LIMIT 1`, 신선도 상한 10분.**
   `WHERE device_id = ? AND created_at >= ? ORDER BY created_at DESC, id DESC LIMIT 1` 형태라
   `idx_device_created(device_id, created_at)`를 그대로 탄다. `GROUP BY` + `MAX(id)`는 인덱스
   두 번째 컬럼이 `created_at`이라 피했다. 신선도 상한의 근거는 성능보다 **의미론**이다 —
   몇 시간 전 온도로 지금 알림을 띄우면 오경보다. JOIN 조건이 `lt.id = <스칼라>` PK 등치라
   같은 초에 몇 행이 있든 디바이스당 응답 item은 구조적으로 최대 1개다.

3. **전역 감시는 Activity 스코프 `TempWatchViewModel`.**
   기존 `DeviceListViewModel`(3초)을 재사용하지 않았다 — 그 폴링은 "탭 전환 시 한쪽만 돌게
   해서 `SystemHealthViewModel`과의 중복을 해소"하려고 **의도적으로** Fragment 생명주기에
   묶여 있고, 전역용으로 바꾸면 그 설계가 깨진다. 대신 5초 주기의 별도 폴러를 두고
   `DeviceListFragment`가 보이는 동안의 폴링 중복은 **허용**했다(응답 ≤10행). 통합하려면
   `SystemHealthViewModel`까지 건드리는 별도 리팩터링이 된다.

4. **MVVM 경계**: `TempWatchViewModel`은 `Context`/`View`/`Fragment`/`Activity`를 필드로도
   인자로도 받지 않는다. 알림 발행은 `Context`를 가진 `MainActivity`의 observe 콜백에서만
   일어나고, `TempAlertNotifier`는 `Context`를 인자로만 받아 `getApplicationContext()`로
   prefs를 잡는다(Activity 참조 미보유).

5. **U10 — 표면 알림 기본 임계만 40f → 70f로 다르게 읽는다.** 아래 별도 절 참조.

6. **O-2 수정 방식으로 (a) "게시 성공 여부 boolean 반환"을 택했다.**
   대안 (b)는 권한 체크를 `evaluate()` 앞단으로 끌어올리는 것인데, `evaluate()`가 히스테리시스
   복귀 분기까지 함께 담고 있어 권한이 없으면 그 분기도 못 타게 된다. 그러면 권한 없는 동안
   온도가 정상으로 돌아와도 `active`가 true로 굳어 **다른 종류의** 상태 불일치가 생긴다.

### U10 처리 — **분석가 권고안을 오케스트레이터가 채택한 것이며, 사용자 승인 기록은 없다**

**무엇을 했는가:** `TempAlertNotifier.checkSurface()`가
`prefs.getFloat("danger_threshold_c", 70f)`를 호출한다. 차트 경로 세 곳
(`SensorDetailFragment:528`, `SensorDetailTestFragment:428`, `SettingsFragment:84` picker 복원)은
`40f` 그대로다. **표면온도 알림 판정 경로 하나만** 기본값 인자가 다르다.

- 키를 추가하거나 구조를 바꾼 것이 아니다. `getFloat`의 **두 번째 인자만** 다르다.
- 사용자가 설정에서 값을 **한 번이라도 저장하면 네 곳 모두 그 저장값을 읽는다** — 분기가 소멸한다.
- 근거: 시드 데이터의 표면온도 기준선이 45°C이고 서버 `warning` 기준은 70°C다. 즉 40°C는
  "정상 가동 중"에도 상시 초과하는 값이다. 지금까지는 threshold가 **시각화 전용**이라
  차트 위험선이 낮게 그려지는 정도였지만, 알림으로 승격되는 순간 앱 첫 실행에 디바이스
  여러 대의 알림이 동시에 폭주한다.

**의사결정 경로 (후속 세션이 오독하지 않도록 정확히 기록):**

| 단계 | 사실 |
|---|---|
| 명세 §3-D 의사코드 | `surf: prefs(danger_threshold_c, **40f**)` |
| 같은 명세 §6 U10 | "**사용자 판단 필요**"로 열어두고 선택지 (가)40f 유지 / (나)알림 경로만 70f / (다)키 기본값 자체 상향 을 제시. **분석가 권고는 (나)** |
| 구현 | **(나) = 70f** 채택 |
| QA | **명세 내부가 모순**임을 지적하고, "사용자가 (나)를 선택했다는 기록을 이번 세션 입력물에서 찾지 못했다"고 명시. 임의로 "통과"로 닫지 않고 판정 요청으로 남김 |

> ⚠ **이 값 선택에 대한 사용자의 직접 승인 기록은 존재하지 않는다.** 분석가가 권고하고
> 오케스트레이터가 채택한 것이다. 후속 세션은 이것을 "사용자가 승인함"으로 읽으면 안 된다.
> **되돌리는 비용은 상수 1개(`TempAlertNotifier.DEFAULT_SURF_THRESHOLD_C`) 수정이다.**
> 사용자가 (가) 또는 (다)를 원하면 그렇게 바꾸면 된다.

### 알려진 관찰 항목 (O-1 ~ O-5)

| # | 내용 | 상태 |
|---|------|------|
| **O-1** | **`DeviceRepository`가 비2xx 응답과 `onFailure`를 통째로 무시한다.** `onResponse`에 `else` 분기가 없고 `onFailure` 본문은 주석 한 줄뿐이라 **Logcat에도 흔적이 남지 않는다.** 이 코드 자체는 기존 코드지만, 이번 변경으로 `/devices`가 `core_temp` 컬럼에 새로 의존하게 되면서 **500이 될 수 있는 경로가 처음 생겨 위험도가 올라갔다.** 증상은 "목록 빈 화면 + 알림 미발생 + 아무 로그 없음 + 3초/5초 폴링이 조용히 반복"이다 | **수정 안 함** — **다음 세션 후속 작업 후보** |
| **O-2** | 알림 권한 거부 상태에서도 `active=true`가 기록되어, 이후 권한을 허용해도 그 초과에 대한 알림이 최대 10분 지연되거나(리마인드 분기 삭제 시) 영구 누락되던 상태 불일치 | **수정 완료** — `notifyExceed()` 반환형 `void`→`boolean`, 호출부 2곳이 게시 성공 시에만 prefs 기록 |
| O-3 | 코어 임계 기본값 `80f`가 두 곳에 리터럴로 중복 — `SettingsFragment:111`(picker 복원)과 `TempAlertNotifier.DEFAULT_CORE_THRESHOLD_C`. 현재 값은 일치하나 한쪽만 바꾸면 "설정에는 80이 보이는데 판정은 다른 값"이 되고 **컴파일도 통과한다.** 키 문자열은 상수화했는데 기본값은 안 한 비대칭 | 미수정 (심각도 하) |
| O-4 | `latestTempAt`은 모델에만 있고 앱 전체에 읽는 코드 0건. 명세는 "신선도 표시·디버깅용"이라고만 적었으므로 계약 위반은 아니다 | 의도된 미사용 (정보) |
| O-5 | 앱 보고서의 "`SensorDetailFragment` 무변경" 표기가 `git diff` 기준으로는 부정확 — diff 9줄이 잡히나 전부 직전 세션 미커밋분(`tvAiTemp` 표시)이고 이번 기능 변경은 0줄 | 문서 정확도만 (코드 정상) |

### 시도했다가 채택하지 않은 접근 (같은 검토를 반복하지 않기 위해)

- **`GET /devices/latest_temps` 별도 엔드포인트 신설** — 앱에 신규 파일 2~3개와 두 번째
  폴링 루프가 생기고, 상태가 두 엔드포인트로 쪼개져 시점 불일치가 난다. 설계 결정 1 참조.
- **`ProcessLifecycleOwner` + `Application` 서브클래스** — `androidx.lifecycle:lifecycle-process`
  신규 의존성이 필요한데, 이 앱은 **Activity가 `MainActivity` 하나뿐**이라 이 라이브러리가
  주는 "여러 Activity를 가로지르는 포그라운드 판정"이 **존재하지 않는 문제**다. 비용만 있고
  이득이 없다. 매니페스트 실물을 확인해 기각했다.
- **기존 `DeviceListViewModel`(3초) 확장·재사용** — 그 폴링이 의도적으로 Fragment 생명주기에
  묶여 있는 이유(`SystemHealthViewModel`과의 중복 해소)가 주석에 명시돼 있다. 설계 결정 3 참조.
- **`SensorDetailFragment` observe 콜백에 판정 추가** (rev.1 초안) — 전역 폴러 도입으로
  불필요해졌고, 붙이면 같은 초과를 두 경로가 판정해 중복·경합이 생긴다.
- **O-2를 "권한 체크를 `evaluate()` 앞단으로 이동"으로 고치기** — 설계 결정 6 참조.
  히스테리시스 복귀 분기까지 막혀 다른 상태 불일치를 만든다.

### 알려진 미해결 사항

| # | 내용 | 담당 | 상태 |
|---|------|------|------|
| 1 | **게이트웨이가 `mfg_data[13..14]`를 파싱하지 않아 `core_temp`가 실제로는 흐르지 않는다** (2026-08-31 승계). `latest_core_temp`는 항상 `null`이고 **코어 알림 경로는 실기 검증이 구조적으로 불가능하다.** 단, 그 상태에서 앱이 안전한지(null이면 알림도 상태 변경도 없고 크래시 없음)는 **이번 기능에서 정적으로 검증됐다** — 이 두 가지를 섞지 말 것 | 게이트웨이 담당자 (세션 밖) | **미완료** |
| 2 | EC2에 `in_gps_db_ver_6.sql`(`temperature_log.core_temp`) 미적용 (2026-08-31 승계). **이번 변경으로 위험도 격상** — 아래 배포 전 확인 참조 | 사용자 | **미확인** |
| 3 | 서버 미배포·미재시작, 앱 미빌드·미설치, git 미커밋 | 사용자 | 미수행 |
| 4 | O-1 — `DeviceRepository`의 무음 실패 | android-engineer | **다음 세션 후속 작업 후보** |
| 5 | U10 값 선택(70f)에 대한 사용자 확인 | 사용자 | **미확인** (위 U10 절) |
| 6 | EC2에 `idx_device_created` 인덱스 실존 여부. 없으면 3초/5초 폴링마다 풀스캔 | 사용자 | 미확인 |
| 7 | EC2 MySQL 버전. 8.x면 `ROW_NUMBER() OVER (PARTITION BY ...)`가 더 나을 수 있으나 5.7 호환 형태로 구현함 | 사용자 | 미확인 |
| 8 | `device.status` 도메인 불일치 — `SystemHealthViewModel`은 `normal/warning/critical/disconnected`를 세는데 서버 CASE 식은 `Connected`/`Disconnected`를 반환한다. **이번 변경과 무관한 기존 사항**(CASE 식 바이트 단위 무변경) | — | 기존 미해결 |
| 9 | SHF 모델 비단조 — Ta≈68°C 위로는 표면이 뜨거울수록 `core_temp`가 내려간다. **이번 기능으로 core_temp가 처음 임계 판정에 쓰이게 됐다.** 알림 문구에 "단독 근거 사용 금지" 고지를 넣은 것이 현재의 유일한 완화책이다 | — | 제품 semantics |
| 10 | `/devices` 폴링이 3종으로 늘었다 — `DeviceListViewModel`(3초, Fragment 스코프), `SystemHealthViewModel`(3초, Fragment 스코프), `TempWatchViewModel`(5초, Activity 스코프). 이번 스코프에서 중복 허용으로 판단 | — | 의도적 허용 |
| 11 | 앱을 **완전히 종료하거나 백그라운드로 내리면 알림이 오지 않는다.** 로컬 알림 방식의 구조적 한계이며 백그라운드 감시는 명시적 비목표 | — | 스코프 밖 (사용자에게 계속 명시) |

### 사용자 수행 항목 (이 세션에서 하지 않았다)

이 세션은 **git commit/push, 서버 배포, EC2 접속을 전혀 하지 않았다.** 아래는 전부 사용자 몫이다.

1. **⚠ 배포 전 필수 — EC2에 `core_temp` 컬럼 존재 확인:**
   ```sql
   SHOW COLUMNS FROM temperature_log LIKE 'core_temp';
   ```
   - **1행이 나오면** 그대로 배포 가능.
   - **0행이면** `in_gps_db_ver_6.sql`을 **먼저** 적용하고 재확인한 뒤 서버를 배포한다.
     **역순 금지.** 컬럼 없이 이 `app.py`를 배포하면 `GET /devices`가
     `Unknown column 'lt.core_temp'`로 500이 되어 **디바이스 목록·시스템 상태 화면이
     통째로 빈 화면**이 된다. 게다가 O-1 때문에 **Logcat에도 아무 흔적이 남지 않는다.**
2. 함께 확인 권장: `SHOW INDEX FROM temperature_log WHERE Key_name = 'idx_device_created';`
3. `EXPLAIN`으로 서브쿼리 인덱스 사용 확인 (`key = idx_device_created`, `Extra`에 `Using filesort` 없을 것).
4. 서버 배포·재시작 → `curl -s http://13.209.92.219:8000/devices | head -c 600`로
   `latest_temp1` / `latest_core_temp` / `latest_temp_at` 3개 키 확인.
5. `gradlew assembleDebug` 후 실기기 설치, 알림 권한 허용, 알림 육안 확인.
6. git commit / push.

### 검증 상태

**정적 코드 대조만 수행했다. 실기 검증은 전부 미검증이다.**

- 수행한 것: 서버 SELECT 별칭 ↔ `@SerializedName` 한 글자 단위 대조(3필드 일치),
  wrapper `Float` 확인, prefs 키 문자열 조립 결과 대조(오타 0건), null 방어 순서 코드 추적,
  MVVM 경계 확인, `git diff HEAD` 원문으로 회귀 4항목(기존 7필드 / `SystemHealthViewModel`
  집계 / 웹 대시보드 / `danger_threshold_c` 차트 경로) 무변경 확인,
  `python -m py_compile app.py` 통과.
  → 경계면 14건 검증 / 통과 14 / 실패 0 / 관찰 5 / 미검증 11.
- **미검증(실행·실물 필요):** EC2 `core_temp` 컬럼 존재, `idx_device_created` 실존,
  `EXPLAIN` 인덱스 사용, 디바이스당 1행 실측, 실제 HTTP 응답 JSON, Gradle 컴파일,
  **코어 알림 end-to-end(구조적으로 불가)**, 표면 알림 실기 확인, 권한 다이얼로그 및 거부 후
  기존 기능 정상, `onStop` 후 요청 정지 실측, 알림 아이콘 렌더링,
  **O-2 수정 후 "권한 거부 → 초과 → 나중 허용 → 5초 내 알림" 시나리오**.
- **"통과"는 어느 것도 실기 동작을 확인한 것이 아니다.** 배포·빌드 후 위 미검증 항목을
  하나씩 닫아야 한다.
