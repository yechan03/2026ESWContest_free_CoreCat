# 앱 구현 보고: core_temp + 표면온도(temp1) 임계 초과 로컬 알림

작성 2026-09-01 · 담당 `ingps-android-engineer`
상류: `_workspace/01_analyst_spec.md` (rev.2) §2-B / §3-B~3-D, `_workspace/02_server_report.md`

---

## 변경 파일

| 파일 | 계층 | 변경 내용 |
|------|------|-----------|
| `model/DeviceModel.java` | Model | 필드 3개 추가 — `latestTemp1`/`latestCoreTemp`(**wrapper `Float`**), `latestTempAt`(`String`). 기존 7개 필드 무변경 |
| `AndroidManifest.xml` | Manifest | `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>` 1줄 추가. 그 외 무변경 |
| `res/drawable/ic_notification_alert.xml` | Res (신규) | 알림 small icon용 24dp 단색 벡터. 경고 삼각형 + 느낌표, `fillType="evenOdd"` |
| `res/layout/fragment_settings.xml` | Res | `card_threshold` 카드 **아래에** `card_core_threshold` 카드 신규 추가(`picker_core_threshold`, `btn_save_core_threshold`, 고지 TextView). 기존 카드 블록 무수정 |
| `fragment/SettingsFragment.java` | Fragment | `setupCoreThreshold(view)` 신규 + `onViewCreated`에서 1줄 호출. **기존 `setupThreshold()` 무수정** |
| `util/TempAlertNotifier.java` | Util (신규) | 채널 생성 / 히스테리시스 판정 / 알림 발행. static 메서드만, Context 미보유 |
| `viewmodel/TempWatchViewModel.java` | ViewModel (신규) | Activity 스코프 5초 전역 폴러. `DeviceRepository.fetchDevices()` 재사용, **Context 미보유** |
| `screen/MainActivity.java` | Activity | 채널 생성 + API 33+ 권한 요청 + `TempWatchViewModel` observe → `TempAlertNotifier.check()` + `onStart`/`onStop` 폴링 제어 |
| `fragment/SensorDetailTestFragment.java` | Fragment | **클래스 Javadoc만** 정정. 코드 0줄 변경 |

### 명시적으로 건드리지 않은 파일 (회귀 검증 대상)

- `fragment/SensorDetailFragment.java` — **무변경.** observe 콜백에 판정을 붙이지 않았다(전역 폴러와 중복 판정 방지). `getDangerThresholdC()`(528행)의 `getFloat("danger_threshold_c", 40f)` 그대로.
- `fragment/SensorDetailTestFragment.java`의 `getDangerThresholdC()`(428행) — `40f` 그대로.
- `SettingsFragment.setupThreshold()` — 키/기본값 40f/범위 20~120 그대로.
- `viewmodel/DeviceListViewModel.java`, `SystemHealthViewModel`, `DeviceRepository`, `ApiService`, `build.gradle` — **전부 무변경.** 신규 의존성 0개.

---

## API 연동

| 엔드포인트 | ApiService 메서드 | 응답 모델 클래스 | 필드 매핑 확인 |
|---|---|---|---|
| `GET /devices` | `getDevices()` (**기존 재사용, 신규 메서드 없음**) | `DeviceModelResponse.items` → `List<DeviceModel>` | 아래 표에서 02_server_report.md와 문자 단위 대조 완료 |

`ApiService`·`DeviceModelResponse`·`DeviceRepository` 모두 무변경 — 응답 래핑 `{"items":[...]}`이 그대로이기 때문.

## 모델 필드 매핑

### `DeviceModel`

| 서버 필드 (02_server_report.md §필드계약) | 모델 필드 | `@SerializedName` | 타입 | 상태 |
|---|---|---|---|---|
| `device_id` | `deviceId` | `"device_id"` | `String` | 기존 |
| `equipment_id` | `equipmentId` | `"equipment_id"` | `String` | 기존 |
| `status` | `status` | `"status"` | `String` | 기존 |
| `installed_on` | `installedOn` | `"installed_on"` | `String` | 기존 |
| `last_seen_at` | `lastSeenAt` | `"last_seen_at"` | `String` | 기존 |
| `created_at` | `createdAt` | `"created_at"` | `String` | 기존 (디바이스 등록 시각) |
| `updated_at` | `updatedAt` | `"updated_at"` | `String` | 기존 |
| **`latest_temp1`** | `latestTemp1` | `"latest_temp1"` | **`Float`** (wrapper) | **신규** |
| **`latest_core_temp`** | `latestCoreTemp` | `"latest_core_temp"` | **`Float`** (wrapper) | **신규** |
| **`latest_temp_at`** | `latestTempAt` | `"latest_temp_at"` | `String` | **신규** |

- **wrapper 사용 이유:** primitive `float`면 Gson이 JSON `null`을 `0.0f`로 채운다 → "0°C 정상"으로 오판되고 알림 판정에서 조용히 임계 미달 처리 → 알림이 영원히 안 뜬다. 모델에 경고 주석을 남겼다.
- 02_server_report.md는 `Double`을 제안했으나 **`Float`로 구현**했다(01_analyst_spec §3-A-1 및 사용자 지시). Gson은 JSON number를 대상 필드 타입으로 변환하므로 `Float`/`Double` 모두 동작하며, 기존 `TemperatureModel.coreTemp`(`Float`)와 타입이 일치하는 쪽을 택했다. 온도 값 정밀도상 `float`로 충분하다.
- `temp2`는 서버가 내려주지 않고 앱도 요구하지 않는다 — 필드 추가 안 함.
- **`latestTempAt != null` + `latestCoreTemp == null` 조합이 현재 정상 상태**(게이트웨이 core_temp 파싱 미완). 이 경우 코어 판정을 건너뛴다.

---

## SharedPreferences 스키마 (`in_gps_prefs`)

| 키 | 타입 | 기본값 | 쓰기 | 읽기 |
|---|---|---|---|---|
| `danger_threshold_c` | Float | **차트: 40f / 알림: 70f** | `SettingsFragment.setupThreshold()` (무변경) | `SensorDetailFragment:528`, `SensorDetailTestFragment:428`, **`TempAlertNotifier`(신규 3번째)** |
| `core_threshold_c` | Float | 80f (범위 20~200) | `SettingsFragment.setupCoreThreshold()` | `TempAlertNotifier` **단 한 곳** |
| `core_alert_active_<deviceId>` | Boolean | false | `TempAlertNotifier` | `TempAlertNotifier` |
| `core_alert_last_ms_<deviceId>` | Long | 0 | `TempAlertNotifier` | `TempAlertNotifier` |
| `surf_alert_active_<deviceId>` | Boolean | false | `TempAlertNotifier` | `TempAlertNotifier` |
| `surf_alert_last_ms_<deviceId>` | Long | 0 | `TempAlertNotifier` | `TempAlertNotifier` |

### U10 채택 (권고안 나) — 같은 키, 읽는 곳마다 다른 기본값

`TempAlertNotifier.checkSurface()`는 `prefs.getFloat("danger_threshold_c", 70f)`를 호출한다.
차트 경로 두 곳은 `40f` 그대로다.

- **키 추가도 구조 변경도 아니다.** `getFloat`의 기본값 인자만 다르다.
- 사용자가 설정에서 값을 **한 번이라도 저장하면 양쪽 모두 그 저장값을 읽는다** — 분기가 사라진다.
- 이유: 시드 데이터 표면온도 기준선 45°C / 서버 warning 기준 70°C. 40°C는 정상 가동 중에도 상시 초과라 앱 첫 실행 시 디바이스 여러 대의 알림이 동시에 폭주한다.
- `TempAlertNotifier.DEFAULT_SURF_THRESHOLD_C` 상수의 Javadoc에 위 내용을 전부 기록했다.

키 문자열은 전부 `private static final String` 상수(`KEY_CORE_THRESHOLD`, `KEY_SURF_THRESHOLD`, `SUFFIX_ALERT_ACTIVE`, `SUFFIX_ALERT_LAST_MS`, `SENSOR_CORE`, `SENSOR_SURF`)로만 정의했고 리터럴 재타이핑은 없다.

---

## 알림 채널·판정 계약 구현

| 항목 | 구현값 |
|---|---|
| Channel ID | `ingps_temp_alert` |
| Channel name | `온도 임계 알림` |
| Importance | `IMPORTANCE_HIGH` |
| 생성 시점 | `MainActivity.onCreate` → `TempAlertNotifier.ensureChannel(this)` (멱등) |
| API 분기 | `Build.VERSION.SDK_INT >= O`에서만 채널 생성 |
| Notification ID | `(deviceId + ":" + sensor).hashCode()` — 센서 키를 섞어 표면 알림이 코어 알림을 덮어쓰지 않게 함 |
| 상수 | `HYSTERESIS_C = 2.0f`, `MIN_RENOTIFY_MS = 10 * 60 * 1000L` |
| PendingIntent | `MainActivity`, `FLAG_UPDATE_CURRENT \| FLAG_IMMUTABLE`, requestCode = notificationId |

판정 흐름(`evaluate()`, 코어/표면 공통):

```
value == null                               → return (알림·상태 변경 모두 없음)
!active && v >= T                           → notify(), active=true, last=now
 active && v <= T - 2.0                     → active=false (복귀)
 active && now-last >= 10분 && v >= T       → notify() 리마인드, last=now
```

리마인드가 불필요하면 세 번째 분기만 삭제하면 된다(다른 분기 영향 없음).

**알림 문구 (센서별로 다름):**
- 코어: `"코어 온도 임계 초과"` / `esp_32_0 · 85.3°C (임계 80.0°C)` + `"AI 추정치입니다. 참고용이며 과열 판정의 단독 근거로 사용하지 마세요."`
- 표면: `"표면 온도 임계 초과"` / `esp_32_0 · 72.4°C (임계 70.0°C)` — **AI 고지 문구 없음**(실측이므로 붙이면 오해를 부른다)

측정값·임계값 모두 `Locale.US`, 소수 1자리. `BigTextStyle`로 코어 알림의 2줄 본문이 잘리지 않게 했다.

---

## MVVM 경계 준수

```
MainActivity.onStart()
  └─ tempWatchViewModel.startPolling()          // Context 전달 없음
       └─ DeviceRepository.fetchDevices(...)    // 기존 Repository 재사용
            └─ LiveData<List<DeviceModel>>
                 └─ MainActivity.observe(this, list -> {      ← Context는 여기서만
                        TempAlertNotifier.check(MainActivity.this, list);
                    })
MainActivity.onStop()
  └─ tempWatchViewModel.stopPolling()
```

- `TempWatchViewModel`: `Context`/`View`/`Fragment`/`Activity`를 **필드로도 인자로도** 받지 않는다. 필드는 `MutableLiveData` / `DeviceRepository` / `Handler(Looper.getMainLooper())` / `boolean polling`뿐이다.
- `TempAlertNotifier`: 생성자 `private`, 모든 메서드 `static`, `Context`는 인자로만. 내부에서 `getApplicationContext()`로 prefs를 잡아 Activity 참조를 붙들지 않는다.
- 알림 발행 지점은 `MainActivity` observe 콜백 **한 곳**. `SensorDetailFragment`에는 붙이지 않았다.
- 폴링 주기 5초(`WATCH_INTERVAL_MS`) — `DeviceListViewModel`의 3초보다 느슨. 중복 폴링은 명세대로 허용.

---

## 차트 변경

**없음.** 이번 기능은 MPAndroidChart를 전혀 건드리지 않는다.

- `LimitLine` / `dangerZone` 음영 / `exceedanceMarkers` / `RangeOverlay` 관련 코드 **0줄 변경**.
- `chart.setLayerType(...)` 추가 없음, 동일 X 다중 entry 없음, `getTransformer` 호출 없음 — 축적된 차트 제약과 접점이 없다.
- `core_temp`를 차트에 그리는 것은 명세상 범위 밖.

---

## 정적 자체 점검 (Gradle 빌드 없이 수행)

| 항목 | 결과 |
|---|---|
| 신규 import 전부 사용 / 미사용 import 없음 | 확인 |
| `Context.getSystemService(Class)` (API 23+), `NotificationChannel`(26+), `TIRAMISU` 분기 | minSdk 24 대비 분기 적정 |
| `registerForActivityResult`를 Activity **필드 초기화**에서 호출 | 생성자 시점 실행 → `CREATED` 이전 요구 충족 (표준 패턴) |
| `PendingIntent.FLAG_IMMUTABLE` | 지정함 (API 31+ 미지정 시 `IllegalArgumentException`) |
| `NotificationManagerCompat.notify()` lint `MissingPermission` | 권한 체크를 헬퍼 메서드로 뺀 탓에 lint가 흐름 추적 실패 → `@SuppressLint("MissingPermission")` + 런타임 체크 + `try/catch SecurityException` 3중 방어 |
| `ic_notification_alert.xml` 감기 방향 | 초안의 nonZero 기본값에서는 느낌표 구멍이 안 뚫려 삼각형이 통째로 메워짐 → `fillType="evenOdd"` 단일 path로 수정 |
| 레이아웃 참조 색상 (`color_primary`/`color_card_bg`/`color_text_primary`/`color_text_secondary`) | 기존 `card_threshold`가 쓰는 것과 동일 — 신규 리소스 없음 |
| `build.gradle` | **무수정**. 신규 의존성 0개 |
| 기존 `danger_threshold_c` 읽기 지점 2곳 `40f` 유지 | grep으로 확인 (`SensorDetailFragment:528`, `SensorDetailTestFragment:428`) |

---

## 디바이스 확인 필요 항목

- [ ] **AC-8 권한 요청** — API 33+ 기기 최초 실행 시 `POST_NOTIFICATIONS` 다이얼로그가 뜨는가. **거부한 뒤** 디바이스 목록·차트·설정이 정상 동작하고 크래시가 없는가.
- [ ] **AC-9 표면 알림 end-to-end** (실측값이 흐르므로 **검증 가능**) — 설정에서 위험 온도를 현재 표면온도보다 낮게 저장 → 5초 내 헤드업 알림. 본문에 디바이스 ID·측정값(소수 1자리)·임계값이 찍히는가.
- [ ] **AC-4 화면 독립성** — 설정 화면·시스템 상태 화면·**실시간 상세보기 다이얼로그가 열린 상태**에서도 알림이 뜨는가.
- [ ] **AC-6 히스테리시스** — 초과 지속 중 알림이 반복되지 않는가. 임계 -2°C 아래로 내려갔다가 다시 넘을 때만 재알림되는가.
- [ ] **AC-12 배터리** — 홈 버튼으로 앱을 내린 뒤 `/devices` 요청이 실제로 멈추는가 (서버 access log 또는 Logcat/네트워크 프로파일러).
- [ ] **알림 아이콘 육안 확인** — 상태바에서 흰 사각형이 아니라 경고 삼각형 실루엣으로 보이는가. `evenOdd` 구멍이 제대로 뚫렸는가. (코드만으로 보장 불가)
- [ ] **다중 디바이스 알림 독립성** — 두 디바이스가 동시 초과할 때 알림이 2개 각각 뜨는가(덮어쓰지 않는가). 코어·표면 동시 초과 시에도 2개인가.
- [ ] **설정 카드 레이아웃** — 코어 임계 카드가 위험 온도 카드 아래에 잘리지 않고 표시되는가. picker 20~200 스크롤, 재진입 시 값 복원.
- [ ] **U10 실효 확인** — 앱 첫 실행(prefs 미저장 상태)에서 표면 알림이 폭주하지 않는가. 동시에 차트 위험선은 여전히 40°C에 그려지는가(의도된 동작).

## 검증 불가 항목 (구조적)

- [ ] **AC-4/AC-5의 core_temp 경로 — 미검증.** 게이트웨이가 `mfg_data[13..14]`를 파싱하지 않아 `latest_core_temp`가 항상 `null`이다. 코어 알림이 실제로 뜨는 것을 볼 수 없다. **통과로 뭉뚱그리면 안 된다.**
  - 관측 가능한 것: AC-7/AC-10(null 안전 — 코어 알림이 뜨지 **않고** 크래시도 없음).
  - 임시 검증 수단(사용자 수행): `POST /sensor`로 `core_temp` 값이 있는 행을 1건 삽입하고 코어 임계를 그보다 낮게 설정. **주의: 서버의 10분 신선도 상한 안에 들어와야 `/devices`에 실린다.**

## 선행 조건 (앱과 무관하나 이 기능 전체를 막음)

- [ ] EC2에 `temperature_log.core_temp` 컬럼 존재 확인 → 없으면 `in_gps_db_ver_6.sql` 선적용 후 서버 배포. 미적용 상태로 서버를 배포하면 `GET /devices`가 500이 되어 **디바이스 목록·시스템 상태 화면이 통째로 빈 화면**이 된다.
  ```sql
  SHOW COLUMNS FROM temperature_log LIKE 'core_temp';
  ```

## 잔여 한계 (스코프 밖, 사용자에게 계속 명시)

**앱을 완전히 종료하거나 백그라운드로 내리면 알림이 오지 않는다.** 로컬 알림 방식의 구조적 한계이며(FCM 아님), 백그라운드 감시는 명시적 비목표다.

---

## 후속 수정 (O-2)

QA 보고서 `04_qa_report.md`의 O-2 — 알림 권한 거부 상태에서도 `active=true`가 기록되어,
이후 권한을 허용해도 그 초과에 대한 알림이 누락(또는 최대 10분 지연)되는 상태 불일치.

**택한 방식: (a) 게시 성공 여부를 boolean으로 반환**

권한 체크를 판정 로직 앞단으로 끌어올리는 (b)는, `evaluate()`가 히스테리시스 복귀 분기
(`active && v <= T - HYSTERESIS_C`)까지 함께 담고 있어 권한 없을 때 이 분기도 못 타게 된다.
그러면 권한이 없는 동안 온도가 정상으로 돌아와도 `active`가 true로 굳어버려 다른 종류의
상태 불일치가 생긴다. 그래서 게시 실패를 호출부로 전달하는 (a)를 택했다.

### 변경 파일
| 파일 | 계층 | 변경 내용 |
|------|------|-----------|
| `util/TempAlertNotifier.java` | util | `notifyExceed()` 반환형 `void` → `boolean`, 호출부 2곳이 반환값으로 prefs 기록 여부 결정 |

### 변경 줄 범위 (수정 후 파일 기준)

| 줄 | 내용 |
|----|------|
| 164–174 | `evaluate()` javadoc — 상태 전이표에 "(게시 성공 시)" 명시, 실패 시 상태를 두는 이유 문단 추가 |
| 188–195 | 신규 초과 분기 — `if (notifyExceed(...)) { active=true, last=now }` 로 감쌈 |
| 200–207 | 리마인드 분기 — `if (notifyExceed(...)) { last=now }` 로 감쌈 |
| 210–223 | `notifyExceed()` 시그니처 `boolean`화 + `@return` javadoc, 권한 체크를 메서드 선두로 이동 후 `return false` |
| 265–272 | `notify()` 성공 시 `return true`, `SecurityException` catch에서 `return false` |

권한 체크(`hasNotificationPermission`)를 기존 위치(notify 직전)에서 메서드 선두로 옮긴 이유:
이제 권한 거부 상태에서 이 경로가 폴링 주기(5초)마다 재진입하므로, 버릴 것이 확정된
`PendingIntent`/`NotificationCompat.Builder`를 매번 만들지 않게 했다. 판정 의미는 동일하다.

### core/surf 양쪽 적용 확인

`checkCore()`와 `checkSurface()`는 임계값을 읽는 prefs 키만 다르고 판정은 모두 공용
`evaluate()`에 위임한다(중복 구조가 아님). 따라서 `evaluate()` 한 곳 수정으로 두 경로에
동일하게 적용된다. `notifyExceed()` 호출부도 이 두 분기가 전부다(파일 내 다른 호출 없음).

### 미변경 확인 (동작 보존)

- 히스테리시스: `HYSTERESIS_C`(2.0f), 복귀 분기 조건·동작 그대로. 복귀 분기는 게시와
  무관하므로 반환값 검사를 넣지 않았다.
- 리마인드: `MIN_RENOTIFY_MS`(10분) 값·비교식 그대로. 게시 성공 시에만 `last`를 갱신하도록
  바뀐 것이 유일한 차이.
- prefs: `PREFS_NAME`, `KEY_CORE_THRESHOLD`, `KEY_SURF_THRESHOLD`, `SUFFIX_ALERT_ACTIVE`,
  `SUFFIX_ALERT_LAST_MS` 및 키 조립 규칙(`<sensor>_alert_active_<deviceId>`) 전부 그대로.
- Notification ID: `(deviceId + ":" + sensor).hashCode()` 그대로 — 디바이스·센서별 독립 유지.
- 채널(`CHANNEL_ID`/importance), 알림 제목·본문·AI 추정 문구, PendingIntent 플래그,
  기본 임계값(80f/70f), `ensureChannel()`, `check()` 진입점 모두 미변경.
- 다른 파일은 건드리지 않았다. `notifyExceed()`는 `private`이라 반환형 변경의 외부 영향 없음.

### 디바이스 확인 필요 항목 (추가)

- [ ] **권한 거부 → 초과 → 나중 허용 시나리오** — 최초 실행에서 알림 권한 거부 → 임계를
  현재 표면온도보다 낮게 설정해 초과 상태 유지 → 설정에서 알림 권한 허용 → **5초(1 폴링) 안에**
  알림이 뜨는가. (수정 전에는 최대 10분 지연 또는 영구 누락)
- [ ] **권한 거부 상태의 무해성(AC-8 회귀)** — 권한 거부인 채로 초과가 지속될 때 크래시·ANR 없이
  다른 기능(차트/목록/설정)이 정상인가. 5초마다 판정이 재진입하지만 권한 체크에서 즉시
  빠져나오므로 부하는 무시할 수준이어야 한다.
- [ ] **정상 경로 회귀** — 권한 허용 상태에서 초과 시 알림 1회만 뜨고 반복되지 않는가
  (AC-6 히스테리시스가 그대로인지 재확인).
