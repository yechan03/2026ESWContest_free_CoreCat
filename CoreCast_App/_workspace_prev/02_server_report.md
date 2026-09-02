# 서버 구현 보고: core_temp (SHF 추정 내부온도) 파이프라인 추가

기준 명세: `_workspace/01b_scope_correction.md` (정본). `01_analyst_spec.md`의 BLE 직접 스캔 전제는 무효.

## 변경 파일

| 파일 | 변경 내용 |
|------|----------|
| `C:\in_gps_server\mqtt_subscriber.py` | `_cast_float()` 헬퍼 신설(범위 필터 없는 타입 검증 전용). `handle_sensor()`에서 `core_temp` 파싱 → INSERT 컬럼/바인드 추가, docstring 페이로드 예시 갱신, 성공 로그에 `CORE=` 추가 |
| `C:\in_gps_server\in_gps_db_ver_6.sql` | **신규** — `temperature_log.core_temp FLOAT NULL` 추가 마이그레이션 |
| `C:\in_gps_server\app.py` | `SensorLogIn`/`SensorLogUpdate` DTO에 `core_temp` 추가. `POST /sensor` INSERT, `PATCH /sensor/{id}` UPDATE에 반영. `GET /sensor`(=`GET /temperature`) SELECT 3곳, `GET /temperature/chart` AVG, `GET /chart/{device_id}` raw/집계 + series 반영 |

## DB 변경

**파일: `C:\in_gps_server\in_gps_db_ver_6.sql`**

사용자가 EC2에서 직접 실행해야 함 (이 세션에서는 실행하지 않음):

```bash
sudo mysql ingps < in_gps_db_ver_6.sql
```

핵심 DDL 전문:

```sql
USE ingps;

ALTER TABLE temperature_log
  ADD COLUMN core_temp FLOAT NULL AFTER temp2;
```

적용 확인:

```sql
SHOW COLUMNS FROM temperature_log LIKE 'core_temp';
SELECT id, device_id, temp1, temp2, core_temp, created_at
  FROM temperature_log ORDER BY created_at DESC LIMIT 5;
```

> **적용 순서 주의**: DDL을 먼저 적용하고 그 다음에 서버/subscriber를 재시작할 것.
> 컬럼이 없는 상태로 새 코드가 뜨면 `handle_sensor()`의 INSERT가 `Unknown column 'core_temp'`로
> 전량 실패하고, 그 행의 temp1/temp2/rms까지 함께 유실된다 (INSERT 한 문장이라 부분 저장 없음).

기존 행 전체는 `core_temp = NULL`로 남는다. 이는 정상이며 앱에서 "측정 불가"로 표시되어야 한다.

## MQTT 파이프라인 변경

- **토픽 변경 없음** — `ingps/sensor` 그대로.
- 페이로드에 `core_temp` 키 추가 (게이트웨이 팀 작업, 이 세션 스코프 아님):

```json
{"gateway_id":"GW_LN01","esp_byte_id":0,"temp1":25.34,"temp2":25.10,"core_temp":41.20,"rms_x":12,"rms_y":8,"rms_z":5}
```

- **하위 호환**: `core_temp` 키가 없는 구버전 페이로드는 `payload.get("core_temp")` → `None` → DB NULL.
  기존 게이트웨이/ESP를 먼저 교체하지 않아도 temp1/temp2/rms 경로는 그대로 동작한다.
- 레거시 `ingps/mvpmodel`(`handle_temperature_legacy`)은 수정하지 않음 — `handle_sensor`에 위임하므로
  core_temp가 자연히 None으로 흐른다.

### 유효범위 필터를 core_temp에 적용하지 않은 이유 (의도적)

`temp1`/`temp2`는 기존대로 `_valid_temp()`(-20~80°C) 필터를 통과한다. `core_temp`는 별도 헬퍼를 쓴다:

```python
def _cast_float(v):
    """타입 검증만 하는 캐스팅(범위 필터 없음). 실패/None이면 None → DB NULL."""
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None
```

`_valid_temp()`는 AS6221 표면 센서의 써미스터 개방/노이즈 글리치를 거르는 장치이고,
`core_temp`는 과열 설비의 **내부 추정치**라 80°C 초과가 설계상 정상 출력이다.
같은 필터를 걸면 정작 감시해야 할 과열 구간만 골라서 NULL이 된다.

동작 검증(단위 수준, 실행 확인 완료):

| 입력 | 결과 |
|------|------|
| `41.2` / `"41.2"` | `41.2` |
| `120.5` (80°C 초과) | `120.5` — **통과** (temp1이면 None이 됐을 값) |
| `-30.0` | `-30.0` — 통과 |
| `None` / 키 누락 | `None` → DB NULL |
| `"abc"` / `{}` | `None` → DB NULL (경고 로그 1줄) |

운영 로그 형식:

```
[MQTT] sensor ✅ | esp_32_0 T1=25.34 T2=25.10 CORE=41.20 RMS=(12,8,5) ev=normal
```

값이 없으면 `CORE=nan`으로 찍힌다(기존 T1/T2와 동일한 관용구).

## 신규/변경 엔드포인트

### GET /temperature  ← **앱 "AI 예측 온도" 카드의 소비처, 최우선**

- 쿼리 파라미터: `device_id: Optional[str] = None`, `limit: int = 100`, `since: Optional[str] = None`
- 동작: `since`+`device_id`가 같이 오면 증분 폴링(ASC), 아니면 `get_sensor()`에 위임(최신순 DESC).
  **두 경로 모두 core_temp를 포함**하도록 수정함.
- 응답 shape (실제 반환 코드는 `return {"items": list(rows)}`, 행은 SELECT 컬럼 그대로):

```json
{
  "items": [
    {
      "id": 184223,
      "device_id": "esp_32_0",
      "temp1": 25.34,
      "temp2": 25.1,
      "core_temp": 41.2,
      "rms_x": 12,
      "rms_y": 8,
      "rms_z": 5,
      "event": "normal",
      "created_at": "2026-08-31T09:20:11"
    }
  ]
}
```

`limit=1` 조회 시 `items[0].core_temp`가 최신 추정 내부온도. **DDL 적용 전 행이거나 게이트웨이가
아직 core_temp를 안 보내면 `"core_temp": null`** — 앱은 반드시 이 null을 처리해야 한다.

- 상태 코드: 200. 데이터 없으면 `{"items": []}` (404 아님).

### GET /sensor

동일 SELECT를 공유하므로 응답 shape은 위와 같다 (`core_temp` 포함).

### GET /temperature/chart

- 쿼리 파라미터: `device_id: str`(필수), `days: int = 1`, `start/end: Optional[str]`, `bucket: Optional[str]("1m"|"1h"|"1d")`
- `items[]`에 `core_temp` (AVG) 추가. **max/min은 미제공** — temp1/temp2와 비대칭.
- 응답 shape:

```json
{
  "device_id": "esp_32_0",
  "bucket": "1m",
  "span_days": 1,
  "start": null,
  "end": null,
  "count": 1440,
  "items": [
    {
      "created_at": "2026-08-31 09:20:00",
      "temp1": 25.34, "temp1_max": 25.9, "temp1_min": 24.8,
      "temp2": 25.1,  "temp2_max": 25.6, "temp2_min": 24.7,
      "core_temp": 41.2,
      "rms_x": 12, "rms_y": 8, "rms_z": 5,
      "event": "normal"
    }
  ]
}
```

### GET /chart/{device_id}

- `metric=all` 또는 `metric=temp`일 때 `series.core_temp` 배열 추가 (raw는 원본값, 집계는 AVG).
  `metric=rms`면 키 자체가 없다 — 기존 temp1/temp2와 동일한 조건부 규칙.

```json
{
  "device_id": "esp_32_0", "days": 7, "bucket": "1m", "metric": "all", "count": 10080,
  "series": {
    "t": ["2026-08-31 09:20:00"],
    "event": ["normal"],
    "temp1": [25.34], "temp2": [25.1], "core_temp": [41.2],
    "rms_x": [12], "rms_y": [8], "rms_z": [5]
  }
}
```

### POST /sensor / PATCH /sensor/{log_id}

- `SensorLogIn` / `SensorLogUpdate`에 `core_temp: Optional[float] = None` 추가.
  **범위 제약(`ge`/`le`) 없음** — MQTT 경로와 같은 이유.
- 응답 shape 변경 없음: `{"ok": true, "id": ..., "device_id": ...}` / `{"ok": true, "id": ..., "event": ...}`
- `POST /sensor`는 `payload.model_dump()`를 그대로 바인딩하므로 INSERT 컬럼 목록도 함께 갱신함
  (안 하면 `core_temp` 바인드 파라미터 불일치).

## NULL 집계 주의

`AVG(core_temp)`는 NULL을 자동 제외한다. 버킷 안의 행이 전부 NULL이면 `AVG`도 NULL →
`items[].core_temp: null`. 반면 `count`는 `len(rows)`(버킷 개수)라 core_temp 유무와 무관하다.
즉 **`count > 0`인데 `core_temp`가 전부 null인 응답이 정상적으로 나올 수 있다** — 마이그레이션
직후 과거 구간을 조회하면 반드시 이 상태가 된다. QA는 이걸 장애로 오판하지 말 것.

## 앱 측 대응 필요 사항 (android-engineer)

1. `TemperatureModel.java`에 `@SerializedName("core_temp") public Float coreTemp;`
   — **반드시 wrapper `Float`, primitive `float` 금지.** primitive면 JSON null이 0.0으로 역직렬화되어
   "0.0°C"가 화면에 뜬다 (temp1/temp2가 이미 걸려 있는 함정).
2. `SensorDetailFragment`에서 `R.id.tv_ai_temp` 바인딩 후, `coreTemp == null`이면 placeholder("--°C"
   또는 "측정 불가") 유지, 값이 있으면 `String.format("%.1f°C", coreTemp)`.
3. 필드명은 전 구간 `core_temp`로 통일됨 (MQTT key = DTO = DB 컬럼 = SELECT alias = SerializedName).
4. 차트에 core_temp를 쓸 계획이면 `/temperature/chart`의 `items[].core_temp`(AVG)만 있고
   `core_temp_max`/`core_temp_min`은 **없다**. RangeOverlay 스타일 min/max 밴드는 불가.

## 미검증 항목

- **EC2 DB 반영 여부 — 미검증.** `in_gps_db_ver_6.sql`을 작성만 했고 실행하지 않았다. 사용자가
  `sudo mysql ingps < in_gps_db_ver_6.sql` 적용 후 `SHOW COLUMNS` 결과를 확인해야 한다.
- **실제 게이트웨이 payload 수신 — 미검증.** 게이트웨이의 `core_temp` publish 구현은 다른 팀원
  작업이라 이 세션에서 확인 불가. 브로커에서 `mosquitto_sub -t 'ingps/sensor' -v`로 실제 키 존재를
  확인해야 최종 확정된다.
- **엔드포인트 런타임 동작 — 미검증.** 로컬에 fastapi/sqlalchemy 미설치라 `import app`이 불가능했다.
  `python -m py_compile app.py mqtt_subscriber.py`(구문 검사) 통과까지만 확인. Pydantic 모델
  인스턴스화와 SQL 실제 실행은 배포 후 확인 필요.
- **`_cast_float()` 동작 — 검증 완료** (AST로 함수만 분리 실행, 위 표 참조).
- **서버 배포/재시작 — 미수행.** 사용자가 진행.

## 이번 스코프에서 의도적으로 제외한 것

- **CSV export (`/export/...`)에 core_temp 미포함.** 컬럼 헤더가 고정 계약이라 임의 추가 시
  기존 소비처에 영향 가능. 필요하면 별도 요청으로 추가 — *추후 확장 필요*.
- **`/temperature/chart`의 `core_temp_max`/`core_temp_min`.** 명세상 필수 아님 — *추후 확장 필요*.
- **`temperature_db_init.sql` 더미 생성기.** 개발용 시드라 core_temp 없이 두면 NULL로 들어간다
  (정상 동작). 더미로 AI 카드 화면 테스트가 필요하면 별도 요청 — *추후 확장 필요*.
- **게이트웨이 코드(`C:\ingps_Gateway\...`) 미수정** — 다른 팀원 담당.
