# 통합 정합성 검증 보고: core_temp (SHF 추정 내부온도)

검증일: 2026-08-31 / 담당: ingps-integration-qa
기준 명세: `_workspace/01b_scope_correction.md` (정본)
검증 방식: 보고서 내용을 근거로 삼지 않고 각 저장소의 실제 코드를 직접 열어 양쪽 대조

## 요약

- 검증 경계면: **12개** / 통과 **9** / 실패 **1** / 미검증 **2**
- 추가 관찰(수정 필수 아님) 4건 — §관찰 항목 참조

지시받은 6개 검증 항목 기준:

| # | 항목 | 판정 |
|---|------|------|
| 1 | 필드명 5곳 일치 | ✅ 통과 |
| 2 | NULL 왕복 (primitive/wrapper) | ✅ 통과 |
| 3 | 온도 필터 우회 + temp1/temp2 회귀 없음 | ✅ 통과 |
| 4 | mfg_data ↔ 게이트웨이 JSON 대응 | ❌ **실패 1건** + ⚠️ 미검증 |
| 5 | DDL 적용 순서 경고 | ✅ 통과 (경고 사실임 — 단, 서버 보고서가 **과소평가**함) |
| 6 | 스코프 밖 회귀 없음 | ✅ 통과 (관찰 2건) |

---

## ❌ 실패 (수정 필요)

### [경계면 1] ESP 무효 센티넬 `-32768`을 파이프라인 전 구간에서 아무도 처리하지 않는다

**이번 검증에서 발견된 유일한 실질 결함이며, 심각도 높음.**

- **생산자**: `C:\esp\in_gps_project\main\ble\ble_adv.c:125-135`
  ```c
  int16_t core_temp_x100 = AS6221_TEMP_INVALID_X100;   /* = -32768 */
  if (ok1 && ok2) { ... core_temp_x100 = (int16_t)v; }
  ```
  `C:\esp\in_gps_project\main\sensor\as6221.h:29` — `#define AS6221_TEMP_INVALID_X100 ((int16_t)-32768)`
  TH1/TH2 중 하나라도 I2C 읽기에 실패하면 `mfg_data[13..14]`에 `-32768`(바이트 `00 80`)이 실린다.
  `ble_adv.c:43` 주석이 이 계약을 명시하고 있다.

- **소비자 (1) 게이트웨이**: 코드 확인 불가. 사용자 확인 전제는 "int16을 ÷100 해서 실수로 변환".
  그 전제대로면 `-32768 / 100 = -327.68` 이 `{"core_temp": -327.68}` 로 publish된다.

- **소비자 (2) 서버**: `C:\in_gps_server\mqtt_subscriber.py:30-43` `_cast_float()`
  ```python
  def _cast_float(v):
      if v is None: return None
      try: return float(v)
      except (TypeError, ValueError): return None
  ```
  **범위 검사가 전혀 없다.** `-327.68`은 타입 캐스팅에 성공하므로 그대로 통과 →
  `mqtt_subscriber.py:240`에서 `temperature_log.core_temp = -327.68` 로 적재된다.
  `app.py:33-45`의 `SensorLogIn.core_temp`에도 `ge`/`le` 제약이 없어 REST 경로도 동일하다.

- **소비자 (3) 앱**: `TemperatureModel.java:23` `coreTemp = -327.68f` (null 아님) →
  `SensorDetailFragment.java:154`의 `data.coreTemp != null`이 **참**으로 평가되어
  `:155`에서 `"-327.7°C"`가 AI 예측 온도 카드에 표시된다.

- **증상**: TH1/TH2 커넥터 이탈·I2C 버스 오류 등 센서 고장 시 화면에 **"측정 불가"가 아니라
  `-327.7°C`** 가 표시된다. 크래시 없음, 로그 경고 없음(`_cast_float`의 경고는 캐스팅 실패 시에만
  찍히는데 이 값은 캐스팅에 성공한다). DB에도 `-327.68`이 정상 값처럼 누적되고,
  `AVG(core_temp)` 집계(`app.py:275`, `app.py:461`)를 통해 **정상 데이터가 섞인 버킷의 평균까지
  오염**시킨다 — NULL이면 AVG에서 자동 제외됐을 값이다.

- **책임 공백이 생긴 경위**: `02_firmware_report.md:226`이 "`-32768` 센티넬을 반드시 별도 처리
  (0°C로 렌더링 금지)"라고 명시했으나, 그 지시 대상은 **되돌려진 Android BLE 직접 스캔 구현**이었다.
  `01b_scope_correction.md`로 아키텍처가 게이트웨이 경유로 정정되면서 센티넬 처리 책임이
  이관되지 않았다. 실제로 `02_server_report.md`, `03_android_report.md`,
  `01b_scope_correction.md` 세 문서 모두에 `-32768`/`327`/`센티넬` 문자열이 **한 건도 없다**(grep 확인).
  기존 `_valid_temp()`(-20~80°C)를 core_temp에서 의도적으로 제거한 결정 자체는 타당하지만,
  그 필터가 유일하게 이 센티넬을 걸러주던 장치였다는 점이 함께 검토되지 않았다.

- **수정 요청 (양쪽 모두 필요 — 방어 심층화)**:
  1. **게이트웨이 담당자(이 세션 밖 팀원)** — 1차 방어선.
     `mfg_data[13..14]`가 `-32768`이면 **JSON에 `core_temp` 키를 아예 싣지 않거나
     `"core_temp": null`로 publish**할 것. ÷100 변환을 하지 말 것.
     (temp1/temp2도 동일 센티넬을 쓰므로 같은 처리가 필요한지 함께 확인 요망.)
  2. **ingps-server-engineer** — 2차 방어선. 게이트웨이 구현을 신뢰할 수 없으므로 서버에도 하한 가드 필요.
     `mqtt_subscriber.py:30` `_cast_float()`에 물리적 하한 검사를 추가할 것.
     안전한 임계: `ble_adv.c:122-123`에 따르면 SHF 모델의 이론적 출력 범위는
     **-141.5 ~ 138.1°C**이므로, `< -200.0` 을 무효로 처리하면 정상값을 자를 위험 없이
     센티넬(-327.68)만 걸러낼 수 있다. 상한은 걸지 말 것(과열 구간 보존이 이번 설계 의도).
     `app.py`의 `SensorLogIn`/`SensorLogUpdate.core_temp`에도 `Field(None, ge=-200.0)` 대칭 적용 권장.

---

## ⚠️ 미검증 (사용자 확인 필요)

### 1. 게이트웨이가 실제로 `core_temp`를 실수 단위로 publish하는지

- **확인할 수 없는 이유**: 게이트웨이 저장소(`C:\ingps_Gateway\IN_GPS_GATEWAY_PCB_TEST`)는
  다른 팀원 작업 영역이며 이 세션 스코프 밖이다. "게이트웨이가 ÷100 해서 실수로 보낸다"는
  전제는 사용자 구두 확인일 뿐 코드로 확인되지 않았다.
- **서버/앱 쪽 정합성은 그 전제 하에서 통과**: `mqtt_subscriber.py:223`은
  `_cast_float(payload.get("core_temp"))`로 **재변환(÷100 등) 없이 그대로** float 캐스팅만 한다.
  DB→API→앱 전 구간에서도 스케일 연산이 없다(`app.py`는 `ROUND(AVG(...),2)`만 수행 — 스케일 무관).
  **이중 스케일링 버그 없음 — 통과.**
- **역방향 위험**: 만약 게이트웨이가 정수 `4120`(x100 원본)을 그대로 보내면, 서버는 그대로
  `4120.0`을 저장하고 앱은 `"4120.0°C"`를 표시한다. 이 경우에도 크래시·에러는 없다.
- **사용자 확인 방법**:
  ```bash
  mosquitto_sub -h <broker> -t 'ingps/sensor' -v
  ```
  출력에 `"core_temp"` 키가 존재하는지, 값이 `41.2` 형태(실수, ÷100 완료)인지
  `4120` 형태(정수, 미변환)인지 육안 확인.
- **현재 상태 주의**: `ble_adv.c:32-34` 주석에 따르면 **현행 게이트웨이 파서는
  `field_len >= 14` 비교 후 offset 12까지만 읽고 뒤 바이트를 무시**한다. 즉 게이트웨이 수정이
  끝나기 전까지 `core_temp`는 MQTT에 **절대 실리지 않으며**, 앱은 항상 "측정 불가"를 표시한다.
  이는 정상 동작(하위 호환)이며 장애가 아니다. 이 상태에서 "AI 카드가 안 나온다"를
  서버/앱 결함으로 오판하지 말 것.

### 2. EC2 실 DB에 `core_temp` 컬럼이 존재하는지

- **확인할 수 없는 이유**: `in_gps_db_ver_6.sql`은 로컬 DDL 파일일 뿐이며, 이 세션에서
  EC2에 적용하지 않았다(전역 규칙상 원격 DB 조작 금지). `git status`상 **untracked 신규 파일**이다.
- **사용자 확인 방법**:
  ```sql
  SHOW COLUMNS FROM temperature_log LIKE 'core_temp';
  ```
  결과가 비어 있으면 서버 배포 전에 `sudo mysql ingps < in_gps_db_ver_6.sql` 를 먼저 실행할 것.

### 3. (참고) 디바이스 실측·육안 확인 항목

아래는 코드 검증 범위 밖이며 사용자가 실기로만 확인 가능하다 — **통과로 간주하지 않는다**.

- 값이 있을 때 AI 카드가 `"41.2°C"`로 갱신되는지 (5초 폴링 반영 포함)
- DB NULL 레코드에서 `"측정 불가"`가 뜨고 `0.0°C`로 새지 않는지
- `"측정 불가"` 4글자가 `tv_ai_temp`(32sp bold, `layout_weight=1`) 옆 chip을 밀지 않는지
- ESP 실기에서 TH2 커넥터 분리 시 `mfg_data[13..14] == 00 80` 관찰 (센티넬 왕복)

---

## ✅ 통과

| # | 경계면 | 확인 근거 (양쪽 파일:라인) |
|---|--------|---------------------------|
| 2 | **MQTT JSON 키 → subscriber 파싱** | 생산자 계약 `01b_scope_correction.md:11` `"core_temp":41.20` ↔ 소비자 `mqtt_subscriber.py:223` `payload.get("core_temp")`. 문자열 완전 일치. 키 부재 시 `None` → DB NULL (하위 호환) |
| 3 | **subscriber INSERT → DB 컬럼** | `mqtt_subscriber.py:235,237,240` 컬럼/바인드/파라미터 3곳 모두 `core_temp` ↔ `in_gps_db_ver_6.sql:35` `ADD COLUMN core_temp FLOAT NULL`. 컬럼명 일치 (EC2 반영 여부는 §미검증 2) |
| 4 | **DB 컬럼 → app.py SELECT alias** | `in_gps_db_ver_6.sql:35` ↔ `app.py:325,333`(GET /sensor), `app.py:510`(GET /temperature since 경로), `app.py:261,275`(GET /chart), `app.py:461`(GET /temperature/chart). **alias 변형 없음** — raw SELECT는 컬럼명 그대로, 집계는 `AS core_temp` 명시. 5개 SQL 전부 확인 |
| 5 | **API 응답 실제 JSON 키 → Gson `@SerializedName`** | 생산자 `app.py:338` `return {"items": list(rows)}` — `.mappings()` 결과를 가공 없이 반환하므로 JSON 키 = SELECT 컬럼명 = `core_temp`. `app.py` 전체에 `response_model` 선언이 **0건**(grep 확인)이라 Pydantic 응답 직렬화 단계 자체가 없음 → 기본값으로 새는 지점 없음. 소비자 `TemperatureModel.java:22-23` `@SerializedName("core_temp") public Float coreTemp;`. **5곳(MQTT키/DTO/DB컬럼/SELECT alias/SerializedName) 전부 정확히 `core_temp`** — `coreTemp`·`core_temperature` 등 변형 0건 |
| 6 | **Pydantic DTO 필드명** | `app.py:41` `SensorLogIn.core_temp: Optional[float] = None`, `app.py:51` `SensorLogUpdate.core_temp`. INSERT 바인드 `app.py:345,347`(`payload.model_dump()` 사용이므로 컬럼 목록 갱신 필수 — 갱신됨), UPDATE `app.py:360`. 바인드 파라미터 불일치 없음 |
| 7 | **NULL 왕복 (서버측)** | `Optional[float] = None` 선언(`app.py:41,51`) + 응답 모델 부재 → DB NULL이 `None` → JSON `null`로 그대로 직렬화. 기본값 치환 경로 없음 |
| 8 | **NULL 왕복 (앱측 wrapper 타입)** | `TemperatureModel.java:23` — **`Float`(wrapper) 확인. `float` primitive 아님.** 같은 파일 `:14,17`의 `temp1`/`temp2`는 여전히 primitive `float`(기존 함정, 이번 스코프 밖)이라 대조 확인됨 |
| 9 | **Fragment null 분기 / 암묵 언박싱 NPE** | `SensorDetailFragment.java:154` `if (data.coreTemp != null)` 명시적 null 체크 존재. `:155`의 `String.format("%.1f°C", data.coreTemp)`는 분기 **내부**라 오토언박싱 NPE 불가. 저장소 전체 grep 결과 `coreTemp` 참조는 `:154`, `:155` **2곳뿐** — 다른 언박싱 경로 없음. `:157` else 분기 `"측정 불가"` |
| 10 | **Repository/ViewModel 무손실 전달** | `TemperatureRepository.java` `fetchLatest()` — `liveData.postValue(response.body().items.get(0))`. 파싱된 인스턴스를 필드 복사 없이 그대로 전달, null 소실·치환 지점 없음. `TemperatureResponse.java` `@SerializedName("items") List<TemperatureModel> items` — 서버 `{"items":[...]}` 래핑과 shape 일치 |
| 11 | **API 계약 (라우트·쿼리 파라미터)** | `ApiService.java:18-21` `@GET("temperature")` + `@Query("device_id")`, `@Query("limit")` ↔ `app.py:500-506` `legacy_temperature(device_id, limit, since)`. 이름·경로 일치. `getTemperatureSince`(`:25-30`)의 `@Query("since")` ↔ `app.py:504` `since`, `getTemperatureChart`(`:33-38`)의 `@Query("bucket")` ↔ `app.py:407` `bucket` 일치. **이번 변경으로 ApiService 수정 불필요 — 신규 엔드포인트 없이 기존 응답에 필드만 추가**했으므로 미연결 엔드포인트 발생 없음 |
| 12 | **온도 필터 우회 + temp1/temp2 무회귀** | `mqtt_subscriber.py:223` core_temp는 `_cast_float()` 경유 — `_valid_temp()`를 타지 않음. `_valid_temp()`(`:17-27`) 본문은 `git diff` 결과 **한 줄도 변경되지 않음**(위쪽 주석 3줄만 재작성). `temp1`/`temp2`는 `:214-215`에서 여전히 `_valid_temp()` 사용. 별도 함수 신설이라 기존 로직 오염 없음 — **회귀 없음** |

### 5번 항목(DDL 적용 순서 경고) 별도 판정 — 경고는 **사실**, 단 **범위가 과소평가됨**

`02_server_report.md:40-42`의 주장("컬럼이 없으면 INSERT가 전량 실패하고 그 행의 temp1/temp2/rms까지
함께 유실된다")을 코드로 검증한 결과 **참**이다:

- `mqtt_subscriber.py:233-243` — 9개 컬럼을 한 문장에 넣는 **단일 INSERT**다. 부분 저장 경로 없음.
  `core_temp` 컬럼 부재 시 `Unknown column`으로 문장 전체가 실패하고, `:261-263`의
  `except` → `db.rollback()`으로 그 행이 통째로 사라진다.

**다만 서버 보고서가 빠뜨린 더 큰 영향이 있다 — 읽기 API도 함께 죽는다:**

- `app.py:261`, `:275`, `:325`, `:333`, `:461`, `:510` — **6개 SELECT문이 `core_temp`를 참조**한다.
  컬럼이 없는 상태로 신규 코드가 배포되면 `GET /temperature`, `GET /sensor`,
  `GET /temperature/chart`, `GET /chart/{device_id}`가 전부 **HTTP 500**을 반환한다.
- 이 경우 앱 증상은 "AI 카드만 측정 불가"가 아니라 **표면/외부 온도·이벤트 칩·차트까지 전부
  갱신 중단**이다(`TemperatureRepository`의 `response.isSuccessful()` 실패 → LiveData 미갱신, 무증상 정지).
- 즉 DDL 선적용은 "신규 필드 손실 방지"가 아니라 **서비스 전체 가용성 문제**다. 순서 준수 필수.

### 6번 항목(스코프 밖 회귀) 판정 — 통과

- `C:\in_gps_server` `git status`: `M app.py`, `M mqtt_subscriber.py`, `?? in_gps_db_ver_6.sql` — 스코프 내.
- `C:\AndroidProject\IN_GPS` `git status`: `M SensorDetailFragment.java`, `M TemperatureModel.java`,
  `M fragment_sensor_detail.xml`, `?? _workspace/` — 스코프 내.
- **BLE 잔재 없음 확인**: `app/src/main/java/com/example/in_gps/ble/` 디렉토리 **부재**(`ls` 확인).
  `AndroidManifest.xml`에 `BLUETOOTH`/`bluetooth`/`LOCATION` 문자열 **0건**(grep 확인). 완전히 되돌려짐.
- **실행 코드 삭제 없음 확인**: `git diff -U0 app.py`의 삭제 라인 24줄을 전수 확인한 결과,
  `core_temp` 추가에 수반된 SELECT/INSERT/UPDATE 문 재작성 6건을 제외한 나머지는 **전부 주석**이다.
- Android 차트 코드(`setupChart`, `RangeOverlay`, transformer, legend, gesture) **무변경** 확인 —
  기존 MPAndroidChart 제약에 신규 위험 없음.

---

## 관찰 항목 (수정 필수 아님 — 판단 요청)

1. **[문서 오류, 조정 필요] `ble_adv.c:44` 주석이 되돌려진 아키텍처를 기술하고 있다.**
   > `소비자는 현재 Android 앱의 BLE 직접 스캔뿐이다(서버 DB 미저장).`

   현행 아키텍처(게이트웨이→MQTT→DB→REST→앱)와 정면으로 모순된다. 더 위험한 것은
   `ble_adv.c:32-34`의 "게이트웨이/서버/DB는 이 확장으로 손댈 필요가 없다"는 문장이다 —
   mfg_data 계약 주석을 근거로 작업하는 **게이트웨이 담당 팀원이 이 주석을 읽으면
   `[13..14]` 파서 추가가 불필요하다고 판단**할 수 있다. 실패 항목 1의 수정 요청을 전달할 때
   이 주석 정정을 함께 요청할 것. 담당: **ingps-firmware-engineer**.

2. **[스코프 이탈, 무해] 서버 커밋에 요청되지 않은 주석 편집이 섞여 있다.**
   `app.py`에서 `# ---------- DTOs ----------` 류 섹션 헤더 주석 10개와 파일 첫 줄 `# app.py`가
   삭제됐고, `mqtt_subscriber.py:12-14`의 유효범위 설명 주석 3줄이 1줄로 축약됐다.
   런타임 영향 0이지만 core_temp 스코프 밖 변경이며, diff 리뷰 비용을 키운다.
   담당: **ingps-server-engineer** — 커밋 분리 또는 되돌림 판단 요청.

3. **[의도치 않은 개선] `app.py:165` 주석의 5초→15초 수정은 결과적으로 옳다.**
   삭제된 주석은 "last_seen_at이 최근 5초 이내"라고 적혀 있었으나 실제 SQL(`app.py:168`)은
   `INTERVAL 15 SECOND`다. 새 주석이 코드와 일치한다 — 되돌리지 말 것. 다만 이것도 2번과 같은
   스코프 밖 변경이므로 기록해 둔다.

4. **[기존 불일치, core_temp 이전부터 존재] `esp_byte_id` 매핑 규칙이 주석과 코드에서 다르다.**
   - `mqtt_subscriber.py:57-58` 주석: `0x01~0x0A → esp_32_0~esp_32_9`, `suffix = byte_id - 1`
   - `mqtt_subscriber.py:65-66` 코드: `if 0 <= byte_id <= 9: return "esp_32_{byte_id}"` (**-1 없음**)
   - `handle_sensor()` docstring `:192`: `"esp_byte_id": 1, ← mfg_data 13번째 바이트 그대로`
   - `ble_adv.c:25`: `#define ESP_DEVICE_ID 0x01` → `mfg_data[12] = 1`

   게이트웨이가 `mfg_data[12]`를 가공 없이 `esp_byte_id`로 실어 보내면 현재 코드는 `esp_32_1`에
   적재한다. 주석 규칙대로면 `esp_32_0`이어야 한다. **core_temp가 어느 디바이스 행에 쌓이는지를
   좌우하므로** 게이트웨이 연동 시 실제 적재 device_id를 반드시 확인할 것.
   이번 변경으로 생긴 문제는 아니므로 실패로 분류하지 않는다. 판정 요청: **ingps-system-analyst**.

5. **[제품 semantics, 참고]** `ble_adv.c:115-121`에 따르면 SHF 모델은 비단조다 —
   Ta≈68°C에서 꼭짓점을 찍고 그 위로는 표면이 뜨거워질수록 추정 내부온도가 **내려간다**
   (Ta=100°C → 42.3°C). 즉 `core_temp`는 과열 판정 근거로 쓸 수 없다. 현재 앱은 값을 표시만 하고
   임계 판정에 쓰지 않으므로 이번 스코프에서는 문제없다. 향후 알람 로직 추가 시 재검토 대상.

---

## 명세 수용 기준 대조 (`01b_scope_correction.md`)

| 기준 | 충족 | 근거 |
|------|------|------|
| 서버 1: `temperature_log.core_temp FLOAT NULL` DDL 작성 | ✅ | `in_gps_db_ver_6.sql:34-35`. EC2 적용은 미검증 |
| 서버 2: `handle_sensor()`에서 `core_temp` 파싱 → INSERT 포함 | ✅ | `mqtt_subscriber.py:223`, `:235,237,240` |
| 서버 2: `_valid_temp()` 범위 필터 미적용, 타입 검증만 | ✅ | `_cast_float()` 신설 `mqtt_subscriber.py:30-43`. ⚠️ 단 실패 항목 1 — 하한 가드 부재 |
| 서버 3: `SensorLogIn`/`SensorLogUpdate`에 `Optional[float]` 추가 | ✅ | `app.py:41`, `app.py:51` |
| 서버 4: `/temperature` 응답에 `core_temp` 포함 (두 경로 모두) | ✅ | `app.py:510`(since 경로) + `app.py:325,333`(`get_sensor` 위임 경로) |
| 서버 5: `/temperature/chart`에 AVG 포함 | ✅ | `app.py:461` `ROUND(AVG(core_temp),2) AS core_temp`. max/min 미제공(명세상 선택) |
| 앱 1: `@SerializedName("core_temp") public Float coreTemp;` wrapper | ✅ | `TemperatureModel.java:22-23` |
| 앱 2: `R.id.tv_ai_temp` 바인딩 + null 분기 렌더링 | ✅ | `SensorDetailFragment.java:97`(선언), `:138`(바인딩), `:154-158`(분기) |
| 앱 3: `chip_ai_status`/`chipEventStatus` 무변경 | ✅ | `git diff` — `:139` 바인딩 라인 변경 없음 |
| 앱 4: MPAndroidChart 무변경 | ✅ | `git diff` — 차트 관련 코드 변경 0줄 |
| 앱 5: BLE 클래스/권한/스캐너 미생성 | ✅ | `ble/` 디렉토리 부재, Manifest BLE 권한 0건 |
| QA: 필드명 전 구간 일치 교차검증 | ✅ | 위 통과표 #2~#5 |
| QA: NULL 전파 (0.0으로 안 새는지) | ✅ | 위 통과표 #7~#10 |
| QA: temp1/temp2/rms 회귀 없음 | ✅ | 위 통과표 #12 + `git diff` 전수 확인 |
| (범위 외) 앱 layout 라벨 `"AI 예측 온도 (추정치)"` | ✅ | `fragment_sensor_detail.xml:277`. 명세상 선택 항목, TextView `android:text` 1줄만 변경 |

---

## 후속 조치 요약

| 우선순위 | 담당 | 내용 |
|---------|------|------|
| 1 (High) | 게이트웨이 팀원 (세션 밖) | `mfg_data[13..14] == -32768`이면 `core_temp` 키 생략 또는 `null` publish. ÷100 하지 말 것 |
| 1 (High) | ingps-server-engineer | `_cast_float()`에 `< -200.0 → None` 하한 가드 추가 (`mqtt_subscriber.py:30`). `SensorLogIn`/`SensorLogUpdate.core_temp`에 `ge=-200.0` 대칭 적용 |
| 2 | 사용자 | EC2에 `in_gps_db_ver_6.sql` **선적용** 후 서버/subscriber 재시작. 순서 역전 시 읽기 API 6개까지 500 |
| 2 | 사용자 | `mosquitto_sub -t 'ingps/sensor' -v`로 `core_temp` 키 존재·스케일(실수 vs 정수) 확인 |
| 3 | ingps-firmware-engineer | `ble_adv.c:32-34, 44` 주석을 현행 게이트웨이 경유 아키텍처로 정정 |
| 3 | ingps-server-engineer | 스코프 밖 주석 편집(관찰 2) 커밋 분리 판단 |
| 4 | ingps-system-analyst | `esp_byte_id` 매핑 규칙 판정(관찰 4) |

> 이번 검증은 **정적 코드 대조**만 수행했다. 서버 실행, DB 조회, 앱 빌드·실기 렌더링은
> 수행하지 않았으며 모두 §미검증으로 분리했다.
