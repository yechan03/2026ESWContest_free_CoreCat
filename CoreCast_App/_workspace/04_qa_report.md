# 통합 정합성 검증 보고: core_temp + 표면온도(temp1) 임계 초과 로컬 알림

작성 2026-09-01 · 담당 `ingps-integration-qa`
입력: `_workspace/01_analyst_spec.md`(rev.2) / `02_server_report.md` / `03_android_report.md` + 실제 코드
검증 방식: **정적 코드 교차 대조만 수행** (서버 미기동 · EC2 미접속 · 실기기 없음 · Gradle 빌드 없음)

---

## 요약

| 구분 | 개수 |
|------|------|
| 검증 경계면 | **14** |
| ✅ 통과 | **14** |
| ❌ 실패 (계약 불일치) | **0** |
| ⚠️ 관찰 항목 (계약 위반은 아니나 조용한 실패 위험) | **5** |
| ⚠️ 미검증 (실행/실물 필요) | **11** |

**핵심 결론: 이 시스템에서 가장 자주 터지는 Gson 무증상 실패는 발생하지 않았다.**
`app.py`의 SELECT 별칭 3개와 `DeviceModel`의 `@SerializedName` 3개가 한 글자까지 일치하며,
두 온도 필드 모두 wrapper `Float`다. 회귀 4항목도 git diff 기준으로 전부 무변경을 확인했다.

**단, "통과"는 전부 정적 대조 결과다.** EC2에 `temperature_log.core_temp` 컬럼이 없으면
`GET /devices`가 500이 되어 위 정합성과 무관하게 **디바이스 목록·시스템 상태 화면이 통째로
빈 화면**이 된다. 이 선행 조건은 아래 §미검증 U-1이며 **배포 전 사용자 확인 필수**다.

---

## ❌ 실패 (수정 필요)

**없음.** 이번 검증에서 확인된 경계면 계약 불일치는 0건이다.

---

## ⚠️ 관찰 항목 — 계약 위반은 아니나 조용한 실패로 이어질 수 있음

계약(필드명·타입·래핑·키 문자열)은 전부 맞다. 아래는 **런타임에 무증상으로 새어나갈 수 있는
지점**이라 별도로 분리해 기록한다. 즉시 수정을 요구하지는 않으나 담당자가 판단할 항목이다.

### [O-1] `/devices`가 500일 때 앱이 아무 신호도 내지 않는다 (심각도: 중)

- 생산자: `C:\in_gps_server\app.py:192-207` — `lt.core_temp` 컬럼에 **새로 의존**
- 소비자: `C:\AndroidProject\IN_GPS\app\src\main\java\com\example\in_gps\repository\DeviceRepository.java:24-34`

```java
public void onResponse(...) {
    if (response.isSuccessful() && response.body() != null) {   // 500이면 여기서 통째로 무시
        callback.onResult(response.body().items);
    }
}                                                                // else 분기 없음
public void onFailure(...) { /* polling will retry on next interval */ }   // 로그도 없음
```

- 증상: EC2에 `core_temp` 컬럼이 없으면 `/devices`가 `Unknown column 'lt.core_temp'`로 500 →
  콜백이 **호출되지 않음** → `TempWatchViewModel`의 LiveData가 영원히 갱신 안 됨 →
  알림 미발생 + 목록 빈 화면 + **Logcat에도 아무 흔적이 없다.** 3초/5초 폴링이 조용히 반복된다.
- 이 코드 자체는 이번 변경으로 만들어진 것이 아니다(**기존 코드**). 다만 이번 변경으로
  `/devices`가 500이 될 수 있는 경로가 처음 생겨서 **위험도가 올라갔다.**
- 판단 요청: `ingps-android-engineer` — `onFailure`/비2xx에 `Log.w` 한 줄이라도 남길지.
  (스코프 밖이면 그대로 두되, 배포 후 증상이 나오면 여기부터 의심할 것)

### [O-2] 알림 권한이 없어도 히스테리시스 상태가 `active=true`로 기록된다 (심각도: 중)

- 위치: `util\TempAlertNotifier.java:182-187` + `246`

```java
if (!active && v >= threshold) {
    notifyExceed(...);                      // 내부 246행: 권한 없으면 조용히 return
    prefs.edit().putBoolean(keyActive, true) // ← 알림이 안 떴어도 "떴다"고 기록
             .putLong(keyLastMs, now).apply();
}
```

- 증상: AC-8대로 사용자가 최초 실행에서 권한을 **거부**한 상태에서 값이 임계를 넘으면
  `active=true`가 남는다. 이후 사용자가 시스템 설정에서 권한을 **허용**해도, 온도가 계속 임계
  위에 머무는 한 첫 분기는 다시 타지 않는다. 값이 `임계-2°C` 아래로 내려갔다 다시 올라오거나
  10분 리마인드 분기가 걸릴 때까지 **알림이 뜨지 않는다.**
- 최대 지연은 `MIN_RENOTIFY_MS`(10분)이므로 영구 유실은 아니다. 리마인드 분기를 삭제하면
  (명세 U5의 "불필요 판정 시 삭제" 옵션) **영구 유실이 된다** — 이 점은 U5 판단 시 함께 고려해야 한다.
- 판단 요청: `ingps-android-engineer` — `notifyExceed`가 실제 발행 여부를 `boolean`으로
  돌려주고 성공했을 때만 상태를 기록할지.

### [O-3] 코어 임계 기본값 `80f`가 두 파일에 리터럴로 중복 (심각도: 하)

- `fragment\SettingsFragment.java:111` — `prefs.getFloat("core_threshold_c", 80f)` (picker 복원용)
- `util\TempAlertNotifier.java:67` — `DEFAULT_CORE_THRESHOLD_C = 80f` (판정용)
- 현재 값은 일치하므로 **정합**이다. 다만 한쪽만 바꾸면 "설정 화면에는 80이 보이는데 실제
  판정은 다른 값" 상태가 되고 **컴파일도 통과하고 에러도 없다.** 키 문자열은 상수화했는데
  기본값은 상수화되지 않은 비대칭이다.
- 참고: 표면 임계는 `40f`(차트) / `70f`(알림)로 **의도적으로** 다르다(U10) — 이건 O-3 대상이 아니다.

### [O-4] `latestTempAt`은 모델에만 있고 소비자가 없다 (심각도: 하 / 정보)

- `model\DeviceModel.java:39` — `@SerializedName("latest_temp_at") public String latestTempAt;`
- 앱 전체 grep 결과 읽는 코드 **0건**. `TempAlertNotifier`의 알림 문구에도 쓰이지 않는다.
- 서버 보고서(02, 항목 5)가 "알림 문구의 '언제 값인지' 표기에 쓸 수 있다"고 제안했으나
  구현에는 반영되지 않았다. **명세 §3-A-1은 "신선도 표시·디버깅용"이라고만 적었으므로
  계약 위반은 아니다.** 의도된 미사용인지만 확인하면 된다.

### [O-5] 03_android_report의 "`SensorDetailFragment` 무변경" 표기가 git 기준으로는 부정확

- `git diff HEAD` 결과 `SensorDetailFragment.java`가 **9줄 변경 상태**로 잡힌다.
- 실제 내용을 열어 확인한 결과 **직전 세션(2026-08-31 core_temp 파이프라인)의 미커밋 변경분**이다
  — `tvAiTemp` 필드 선언 / `findViewById(R.id.tv_ai_temp)` / observe 콜백의 coreTemp null 분기 3곳뿐.
- **이번 알림 기능과 관련된 변경은 0줄**이고, 차트 임계 로직(`getDangerThresholdC()` / LimitLine /
  dangerZone / exceedanceMarkers)은 diff에 **전혀 등장하지 않는다** → 회귀 항목 R-4는 통과다.
- 보고서 문구만 "이번 기능으로는 무변경(직전 세션 미커밋 변경분은 남아 있음)"으로 정정 권장.
  담당: `ingps-android-engineer` (코드 수정 아님, 문서 정확도)

---

## ⚠️ 미검증 (사용자 확인 필요)

**아래 항목을 "통과"로 읽으면 안 된다.** 정적 코드로는 확인할 수 없는 것들이다.

| # | 항목 | 확인할 수 없는 이유 | 사용자가 확인할 방법 |
|---|------|--------------------|---------------------|
| **U-1** | **EC2에 `temperature_log.core_temp` 컬럼 존재** 🚨 **배포 차단 항목** | EC2 미접속(지침상 금지). `in_gps_db_ver_6.sql`은 로컬 DDL 파일일 뿐 EC2 반영 보장 아님 | `sudo mysql ingps -e "SHOW COLUMNS FROM temperature_log LIKE 'core_temp';"` → 0행이면 `sudo mysql ingps < in_gps_db_ver_6.sql` **선적용 후** 서버 배포. **역순 금지** |
| U-2 | EC2에 `idx_device_created` 인덱스 존재 | 위와 동일 | `SHOW INDEX FROM temperature_log WHERE Key_name='idx_device_created';` — 없으면 3초 폴링마다 풀스캔 |
| U-3 | 서브쿼리가 실제로 인덱스를 타는지(`EXPLAIN`) | 로컬 MySQL 서버 부재, EC2 미접속 | `EXPLAIN SELECT t.id FROM temperature_log t WHERE t.device_id='esp_32_0' AND t.created_at >= NOW() - INTERVAL 10 MINUTE ORDER BY t.created_at DESC, t.id DESC LIMIT 1;` → `key=idx_device_created`, `Extra`에 `Using filesort` 없을 것 |
| U-4 | 디바이스당 응답 item 1개 **실측** | 서버 미기동 | 아래 §경계면 9의 SQL. **구조적으로는 보장됨**(PK 등치 JOIN) — 정적 판정은 통과 |
| U-5 | 실제 HTTP 응답 JSON 키 | 서버 미기동(`sqlalchemy`/`pymysql` 미설치) | 배포 후 `curl -s http://13.209.92.219:8000/devices \| head -c 600` — `latest_temp1`/`latest_core_temp`/`latest_temp_at` 3개 키가 그대로 보이는지 |
| U-6 | Gradle 컴파일 통과 | 빌드는 사용자 수행 | `gradlew assembleDebug` |
| U-7 | **AC-4/AC-5의 core_temp 경로 end-to-end** | 게이트웨이가 `mfg_data[13..14]`를 파싱하지 않아 `latest_core_temp`가 **항상 null**. 코어 알림이 뜨는 것을 볼 수 없다 | `POST /sensor`로 `core_temp` 값이 있는 행 1건 삽입 + 코어 임계를 그보다 낮게 설정. **주의: 10분 신선도 상한 안에 들어와야 `/devices`에 실린다** |
| U-8 | AC-8 권한 다이얼로그 / 거부 후 기존 기능 정상 | 실기기 필요 | API 33+ 기기 최초 실행 → 거부 → 목록·차트·설정 동작 확인 |
| U-9 | AC-9 표면 알림 실기 확인 (**실측값이 흐르므로 검증 가능**) | 실기기 필요 | 설정에서 위험 온도를 현재 표면온도보다 낮게 저장 → 5초 내 헤드업 알림 |
| U-10 | AC-12 배터리 — onStop 후 요청 정지 | 실기기/서버 로그 필요 | 홈 버튼 후 서버 access log 또는 네트워크 프로파일러에서 `/devices` 요청 중단 확인 |
| U-11 | 알림 아이콘 육안 (`evenOdd` 구멍) | 렌더링은 코드로 보장 불가 | 상태바에서 흰 사각형이 아니라 경고 삼각형 실루엣인지 |

### 추가 미검증 — 명세 자체의 미해결 항목 (판정 요청)

| 항목 | 내용 |
|------|------|
| **U10 결정 미확인** | 명세 §3-D 의사코드는 `surf: prefs(danger_threshold_c, **40f**)`인데, 같은 명세 §6 U10은 "**사용자 판단 필요**"로 열어두고 (나)=70f를 권고했다. 구현은 **70f**를 채택했다(`TempAlertNotifier.java:82`). **명세 내부가 모순**이며, 사용자가 (나)를 선택했다는 기록을 이번 세션 입력물에서 찾지 못했다. → **`ingps-system-analyst` 판정 + 사용자 확인 필요.** 구현은 U10 권고안과 일치하므로 그대로 두는 것이 합리적이나, QA가 임의로 "통과"로 닫지 않는다 |
| `device.status` 도메인 | `SystemHealthViewModel`은 `normal/warning/critical/disconnected`를 세는데, 서버 예시 응답과 CASE 식은 `'Connected'`/`'Disconnected'`를 반환한다. **이번 변경과 무관한 기존 사항**이며 CASE 식은 바이트 단위로 무변경이라 회귀는 아니다. EC2의 `device.status` 실제 값 분포는 미확인 → 별도 확인 권장(`SELECT status, COUNT(*) FROM device GROUP BY status;`) |

---

## ✅ 통과 (양쪽 코드 동시 대조 완료)

### 1. `GET /devices` 응답 필드 ↔ `DeviceModel` `@SerializedName` — **최우선 항목, 한 글자 단위 대조**

| 서버 SELECT 별칭 (`app.py`) | Java `@SerializedName` (`DeviceModel.java`) | Java 타입 | 판정 |
|---|---|---|---|
| `lt.temp1 AS latest_temp1` `:197` | `"latest_temp1"` `:30` → `latestTemp1` `:31` | **`Float`** (wrapper) | ✅ 일치 |
| `lt.core_temp AS latest_core_temp` `:198` | `"latest_core_temp"` `:34` → `latestCoreTemp` `:35` | **`Float`** (wrapper) | ✅ 일치 |
| `lt.created_at AS latest_temp_at` `:199` | `"latest_temp_at"` `:38` → `latestTempAt` `:39` | `String` | ✅ 일치 |

- 오타·대소문자·언더스코어 위치 전부 동일. `latest_temp1`의 `1`(숫자 일), `latest_core_temp`의
  `core_temp`(언더스코어 1개) 확인.
- 서버에만 있고 모델에 없는 키: **없음.** 모델에만 있고 서버에 없는 키: **없음.**
- 별칭 `latest_temp_at`이 `d.created_at`(디바이스 등록 시각)과 **키 충돌하지 않음** 확인
  (`.mappings()`는 별칭 기준 키를 만든다). 앱도 `createdAt`/`latestTempAt`을 분리 보관.

### 2. wrapper `Float` 확인 (primitive면 즉시 결함) — ✅

- `DeviceModel.java:31,35` 둘 다 대문자 `Float`. 스킬 추출 스크립트의 primitive 의심 목록에도
  두 필드는 **잡히지 않았다**(잡힌 6건은 전부 `TemperatureModel`/`DeviceLogModel`의 기존 필드).
- 모델 `:25-27`에 "primitive float이면 Gson이 null을 0.0f로 채워 조용히 임계 미달 처리된다"는
  경고 주석이 박혀 있다.
- 서버 보고서는 `Double`을 제안했으나 구현은 `Float`다 — **양쪽 다 wrapper이므로 null 보존이
  동일하고, Gson은 JSON number를 대상 필드 타입으로 변환하므로 계약상 문제 없다.**
  기존 `TemperatureModel.coreTemp`(`Float`)와 타입이 정렬된다.

### 3. 응답 래핑 — ✅

| 생산자 | 소비자 |
|---|---|
| `app.py:216` — `return {"items": list(rows)}` | `DeviceModelResponse.java:9` — `@SerializedName("items") public List<DeviceModel> items;` |

`DeviceModelResponse`·`DeviceRepository`·`ApiService` 무변경. 래퍼 유지가 맞다.

### 4. 라우트 경로·쿼리 파라미터 — ✅

| 생산자 | 소비자 |
|---|---|
| `app.py:175` — `@app.get("/devices")`, `list_devices(equipment_id: Optional[str] = None)` | `ApiService.java:15-16` — `@GET("devices")` `Call<DeviceModelResponse> getDevices();` |

- `RetrofitClient.java:14` BASE_URL이 `http://13.209.92.219:8000/`로 **슬래시로 끝나므로**
  상대경로 `"devices"`가 `/devices`로 해석된다. 신규 엔드포인트 없음 → 미연결 엔드포인트 0건.
- `equipment_id`는 앱이 보내지 않고 서버 기본값 `None`으로 전체 조회 — 기존 동작 그대로.

### 5. 회귀 R-1 — 기존 7개 필드 이름·타입·순서·의미 무변경 — ✅

`git diff HEAD -- app.py`로 직접 확인. SELECT 절 앞부분이 테이블 별칭(`d.`)만 붙었고
**필드명·순서·CASE 식(`INTERVAL 15 SECOND`)은 바이트 단위로 동일**하다.

```
-               CASE WHEN last_seen_at   > NOW() - INTERVAL 15 SECOND THEN status
+               CASE WHEN d.last_seen_at > NOW() - INTERVAL 15 SECOND THEN d.status
```

`ORDER BY CAST(SUBSTRING_INDEX(...))` 정렬도 별칭만 추가. 신규 3필드는 **뒤에만** 붙었다.
`DeviceModel.java:6-19`의 기존 7개 `@SerializedName`도 diff에 없다(추가만 20줄).

### 6. 회귀 R-2 — `SystemHealthViewModel` 집계 무변경 — ✅

- `viewmodel\SystemHealthViewModel.java:41-48` — `d.status` **하나만** 읽어
  `normal/warning/critical/disconnected`를 센다. 신규 필드를 참조하지 않는다.
- `git status`에 `SystemHealthViewModel.java` / `SystemHealthFragment.java` **미변경**.
- `DeviceAdapter.java:47-50`(목록 status 칩/색상)도 `deviceId`/`status`만 사용 — 무영향.

### 7. 회귀 R-3 — 웹 대시보드 — ✅

- `C:\in_gps_server\web\app.js:70-78` — `dev.body.items.forEach(d => { opt.value = d.device_id; ... })`
  **`device_id` 단 하나만** 읽고 필드를 열거하지 않는다. 필드 3개 추가는 하위 호환.
- `web/app.js` 미변경(`git status` 기준).

### 8. 회귀 R-4 — `danger_threshold_c` 기반 차트 로직 전혀 미수정 — ✅

`git diff HEAD`로 직접 확인 (보고서 문구가 아니라 코드 기준):

| 파일:라인 | 코드 | diff 등장 |
|---|---|---|
| `SensorDetailFragment.java:528` | `.getFloat("danger_threshold_c", 40f)` | ❌ 미등장 (무변경) |
| `SensorDetailTestFragment.java:428` | `.getFloat("danger_threshold_c", 40f)` | ❌ 미등장 (무변경) |

- `SensorDetailFragment` diff 9줄은 전부 `tvAiTemp` 표시(직전 세션분) — LimitLine / dangerZone /
  exceedanceMarkers / `RangeOverlay` 관련 줄은 **diff에 한 줄도 없다**. → O-5 참조.
- `SensorDetailTestFragment` diff는 **클래스 Javadoc 6줄뿐**, 코드 0줄. 명세 §2-1과 일치.
- `SettingsFragment.setupThreshold()` (`:74-95`) — 키 `danger_threshold_c`, 기본 40f,
  범위 20~120 전부 그대로. 신규 `setupCoreThreshold()`는 **아래에 추가만** 되었고
  `onViewCreated`에 호출 1줄(`:70`)이 붙었다.
- `fragment_settings.xml` diff는 기존 `card_threshold` 블록 **뒤에 순수 추가 82줄**.

### 9. 디바이스당 응답 item 정확히 1개 — ✅ (구조적 판정 / 실측은 U-4)

`app.py:200-207` SQL을 직접 읽고 판단:

```sql
LEFT JOIN temperature_log lt
       ON lt.id = (SELECT t.id ... ORDER BY t.created_at DESC, t.id DESC LIMIT 1)
```

- 서브쿼리는 `LIMIT 1`로 **스칼라 하나**(`lt.id`)를 반환하고, JOIN 조건이 `lt.id = <그 값>`
  즉 **PK 등치**다. → `created_at`이 같은 초에 몇 행이 있든 매칭은 **최대 1행**.
  행 증식이 구조적으로 불가능하다.
- 서브쿼리가 NULL(10분 내 행 없음)이면 `lt.id = NULL`이 UNKNOWN → LEFT JOIN이 3필드를
  모두 NULL로 남긴다. 디바이스 행 자체는 유지된다(목록에서 사라지지 않음).
- `ORDER BY created_at DESC, id DESC`는 명세 §2-A 성능계약 4의 타이브레이크 요구를 충족
  — **어느 행을 고를지**를 결정론적으로 고정한다.
- f-string 안전성: `base_sql`에 삽입되는 것은 모듈 상수 `LATEST_TEMP_MAX_AGE_MINUTES = 10`
  (`app.py:173`) int 리터럴뿐. 다른 `{}` 리터럴 없음 → 렌더링·인젝션 문제 없음.

### 10. SharedPreferences `in_gps_prefs` 키 스키마 — ✅ 오타 0건

| 키 (실제 문자열) | 쓰기 지점 | 읽기 지점 | 판정 |
|---|---|---|---|
| `core_threshold_c` | `SettingsFragment.java:116` (리터럴) | `TempAlertNotifier.java:50` `KEY_CORE_THRESHOLD` → `:136` | ✅ 동일 리터럴. 복원 읽기 `SettingsFragment:111`도 동일 |
| `danger_threshold_c` | `SettingsFragment.java:89` (무변경) | `TempAlertNotifier.java:52` `KEY_SURF_THRESHOLD` → `:149`, `SensorDetailFragment:528`, `SensorDetailTestFragment:428` | ✅ 4곳 전부 동일 리터럴 |
| `core_alert_active_<dev>` | `TempAlertNotifier.java:185` | `:179` | ✅ 동일 상수 조합 |
| `core_alert_last_ms_<dev>` | `:186,196` | `:193` | ✅ |
| `surf_alert_active_<dev>` | `:185` | `:179` | ✅ |
| `surf_alert_last_ms_<dev>` | `:186,196` | `:193` | ✅ |

- 조립식이 맞다: `TempAlertNotifier.java:175-176`
  `sensor + SUFFIX_ALERT_ACTIVE + deviceId` / `sensor + SUFFIX_ALERT_LAST_MS + deviceId`,
  `SENSOR_CORE="core"` `:62` / `SENSOR_SURF="surf"` `:63`,
  `SUFFIX_ALERT_ACTIVE="_alert_active_"` `:58` / `SUFFIX_ALERT_LAST_MS="_alert_last_ms_"` `:59`
  → 렌더링 결과가 명세 §3-B의 `core_alert_active_esp_32_0` 형태와 정확히 일치.
- **읽기·쓰기가 같은 지역변수(`keyActive`/`keyLastMs`)를 쓰므로 구조적으로 어긋날 수 없다.**
- 파일명 `"in_gps_prefs"`: `TempAlertNotifier.java:47` `PREFS_NAME` ↔ `SettingsFragment.java:83,110`
  ↔ `SensorDetailFragment:527` — 전부 동일.
- 타입 일관성: `putFloat`↔`getFloat` (양쪽 키 모두). `ClassCastException` 위험 없음.

### 11. U10 — 기본값 인자 분기가 알림 경로에만 적용되는지 — ✅ (구현 일치 / 정책 확인은 별도)

grep 재확인 결과:

| 위치 | 기본값 |
|---|---|
| `TempAlertNotifier.java:82` `DEFAULT_SURF_THRESHOLD_C` → `:149` `getFloat(KEY_SURF_THRESHOLD, DEFAULT_SURF_THRESHOLD_C)` | **`70f`** (알림 경로만) |
| `SensorDetailFragment.java:528` | **`40f`** (변경 없음) |
| `SensorDetailTestFragment.java:428` | **`40f`** (변경 없음) |
| `SettingsFragment.java:84` (picker 복원) | **`40f`** (변경 없음) |

- **신규 키를 만들지 않았고**(AC-9 요구), `getFloat` 기본값 인자만 다르다. 사용자가 값을
  한 번이라도 저장하면 4곳 모두 그 저장값을 읽는다 → 분기 소멸. 구조 변경 아님.
- 다만 이 선택 자체(40f냐 70f냐)는 명세 내부가 모순이라 **정책 확인 필요** — §미검증 표 참조.

### 12. MVVM 경계 (AC-13) — ✅

- `TempWatchViewModel.java:40-44` 필드 전부: `MutableLiveData<List<DeviceModel>>` /
  `DeviceRepository` / `Handler(Looper.getMainLooper())` / `boolean polling`.
  **`Context`/`View`/`Fragment`/`Activity` 필드 0개. 메서드 인자로도 받지 않는다**
  (`startPolling()`/`stopPolling()`/`getDevices()` 전부 무인자).
- `TempAlertNotifier` — 생성자 `private`(`:37`), 모든 메서드 `static`, 인스턴스 필드 0개.
  `Context`는 인자로만 받고 `:157`에서 `getApplicationContext()`로 prefs를 잡아
  **Activity 참조를 붙들지 않는다.**
- `MainActivity.java:48-49` — `observe(this, list -> TempAlertNotifier.check(MainActivity.this, list))`.
  **Context가 등장하는 지점은 여기 한 곳.** 명세 §2-B 다이어그램과 일치.
- 판정 진입점 단일화 확인: `TempAlertNotifier` 참조가 앱 전체에서 `MainActivity.java:42,49`
  **2곳뿐**(채널 생성 + observe). `SensorDetailFragment`에는 붙지 않았다 → 중복 판정 없음.
- 폴링 제어: `onStart():78` `startPolling()` / `onStop():84` `stopPolling()`.
  `stopPolling`이 `polling=false` + `removeCallbacksAndMessages(null)` → 이후 신규 요청 없음(AC-12).

### 13. null 안전성 (AC-7 / AC-10) — ✅ NPE 경로 없음

`TempAlertNotifier`의 방어 순서를 코드로 추적:

| 위치 | 방어 |
|---|---|
| `:118` | `if (context == null \|\| devices == null) return;` — 응답 없음/실패 시 |
| `:121` | `if (d == null \|\| d.deviceId == null) continue;` — 리스트 원소 |
| `:134-135`, `:146-147` | `prefs(context)` null 체크 |
| **`:173`** | **`if (value == null) return;`** — 알림도, **상태 변경도** 하지 않는다 |
| `:178` | `final float v = value;` — **null 체크 이후**에만 언박싱 |

- `Float` → `float` 언박싱이 `:173` 이후에만 일어나므로 **NPE 불가**.
- `latestCoreTemp == null`(게이트웨이 미완으로 현재의 정상 상태)에서 코어 알림은
  발생하지 않고 크래시도 없다 → AC-7/AC-10 코드상 충족.
  `SensorDetailFragment`의 "측정 불가" 표시도 무변경(`data.coreTemp != null` 분기 유지).
- `latest_temp_at != null` + `latest_core_temp == null` 조합(서버 보고서가 "현 운영 정상 상태"로
  명시)에서도 코어만 건너뛰고 표면은 정상 판정된다 — 서버 null 조건표 2행과 앱 로직 일치.

### 14. Notification ID 센서별 분리 — ✅

- `TempAlertNotifier.java:227` — `int notificationId = (deviceId + ":" + sensor).hashCode();`
- `esp_32_0:core`와 `esp_32_0:surf`는 서로 다른 문자열 → 다른 hashCode → **표면 알림이 코어
  알림을 덮어쓰지 않는다.** 디바이스별로도 분리된다(`esp_32_0:surf` ≠ `esp_32_1:surf`).
- 같은 값이 `PendingIntent`의 requestCode(`:230`)로도 쓰여 인텐트 재사용 충돌도 없다.
- 채널: `CHANNEL_ID="ingps_temp_alert"` `:41`, `IMPORTANCE_HIGH` `:107`,
  `Build.VERSION_CODES.O` 분기 `:101`, `MainActivity.onCreate:42`에서 멱등 생성 — 명세 §3-C와 일치.
- 매니페스트: `AndroidManifest.xml:6` `POST_NOTIFICATIONS`가 `<application>` **바깥**에
  올바르게 선언됨. `MainActivity:88-95`에서 `TIRAMISU` 분기 + `registerForActivityResult`
  필드 초기화(생성자 시점 = 표준 패턴).

---

## 명세 수용 기준 대조

| 기준 | 충족 | 근거 |
|---|---|---|
| AC-1 코어 임계 카드 별도 추가 / 기존 카드 무변경 | ✅ 코드상 | `fragment_settings.xml` diff = 기존 `card_threshold` 뒤 순수 추가 82줄. `setupThreshold()` 무수정. **레이아웃 육안은 U-6/실기** |
| AC-2 `core_threshold_c`에 저장, `danger_threshold_c` 불변 | ✅ | `SettingsFragment.java:116` `putFloat("core_threshold_c", val)` — 다른 키를 쓰지 않음 |
| AC-3 재진입 시 복원 / 기본 80 | ✅ 코드상 | `SettingsFragment.java:111` `getFloat("core_threshold_c", 80f)`, picker 20~200(`:106-107`) |
| AC-4 화면 무관 전역 감시 | ⚠️ **부분** | 아키텍처는 코드상 충족(Activity 스코프 폴러 + `MainActivity` 단일 observe). **표면 경로 실기 확인 U-9 / 코어 경로 구조적 미검증 U-7** |
| AC-5 본문에 ID·측정값·임계값 + 코어 전용 AI 고지 | ✅ 코드상 | `TempAlertNotifier.java:207-219`. `Locale.US` `%.1f°C`, 코어에만 고지 문구 추가, `BigTextStyle`로 2줄 미절단 |
| AC-6 히스테리시스 · 반복 알림 금지 | ✅ 코드상 | `:182-197` 3분기. `HYSTERESIS_C=2.0f`, `MIN_RENOTIFY_MS=10분`. 명세 §3-D 의사코드와 분기·부등호 방향까지 일치. **단 O-2 참조** |
| AC-7 값 null → 무알림·무크래시 | ✅ | `:173` 조기 반환. 언박싱은 그 이후 |
| AC-8 API 33+ 권한 요청 / 거부해도 기존 기능 정상 | ⚠️ 코드상 충족, **실기 U-8** | `MainActivity:88-95` + `TempAlertNotifier:246,254-258` 3중 방어(런타임 체크 + `try/catch SecurityException` + `@SuppressLint`) |
| AC-9 temp1 알림, temp2 비대상, 기존 키 사용 | ✅ 코드상 | `check():122-123`이 `latestCoreTemp`/`latestTemp1`만 순회. `temp2`는 서버 응답에도 모델에도 없음. `KEY_SURF_THRESHOLD="danger_threshold_c"` |
| AC-10 응답에 최신 온도 필드, 없으면 JSON null, 목록 안 깨짐 | ✅ 코드상 | 경계면 1·9. LEFT JOIN이라 디바이스 행은 유지 |
| AC-11 기존 소비처 회귀 없음 | ✅ | 경계면 5·6·7·8 (git diff 기준) |
| AC-12 onStop 후 요청 정지 | ⚠️ 코드상 충족, **실측 U-10** | `MainActivity:84` → `stopPolling()`. 단 in-flight 응답 1건은 도착 가능(신규 요청은 없음) |
| AC-13 ViewModel이 Context/View/Fragment/Activity 미보유 | ✅ | 경계면 12 |

**충족 표기 규칙:** ✅는 **정적 코드 대조로 확인된 것**이다. 실행이 필요한 항목은 ⚠️로
표시하고 위 미검증 표에 대응 항목을 두었다. **어느 것도 실기 동작을 확인한 것이 아니다.**

---

## 수정 요청 (담당 배정)

| # | 담당 | 내용 | 강제성 |
|---|------|------|--------|
| O-1 | `ingps-android-engineer` | `DeviceRepository`의 비2xx/`onFailure` 무음 처리 — 로그 1줄 추가 여부 판단 | 판단 요청 (기존 코드) |
| O-2 | `ingps-android-engineer` | 권한 없어 알림이 억제됐을 때 `active=true`를 기록하지 않도록 `notifyExceed` 반환값 활용 검토 | 판단 요청 |
| O-3 | `ingps-android-engineer` | 코어 기본값 `80f` 리터럴 2곳 중복 — 상수 단일화 검토 | 낮음 |
| O-4 | `ingps-android-engineer` | `latestTempAt` 미사용이 의도인지 확인 | 정보 |
| O-5 | `ingps-android-engineer` | 03 보고서의 "`SensorDetailFragment` 무변경" 문구 정정 (코드는 정상) | 문서 |
| U10 | `ingps-system-analyst` + **사용자** | 명세 §3-D(40f) ↔ §6 U10 권고(70f) 모순 판정 | **확인 필요** |
| U-1 | **사용자** | **EC2 `core_temp` 컬럼 확인 → 없으면 DDL 선적용 → 그 다음 서버 배포** | **배포 차단** |

---

## 검증 방법 기록

- 스킬 `ingps-contract-check` 절차 §4·§5·§6·§8 수행. `scripts/extract_contracts.py` 실행으로
  라우트 21개 / Retrofit 메서드 7개 / 모델 필드 매핑을 자동 추출해 후보를 좁힌 뒤,
  **모든 판정은 양쪽 파일을 직접 열어 확인**했다(스크립트 출력만으로 판정한 항목 없음).
- 회귀 4항목은 **보고서 문구가 아니라 `git diff HEAD` 원문**으로 확인했다.
- 접근 실패한 파일 없음.
