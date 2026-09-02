# 서버 구현 보고: `GET /devices` 응답에 디바이스별 최신 온도 추가

작성 2026-09-01 · 담당 `ingps-server-engineer` · 상류 명세 `_workspace/01_analyst_spec.md` §2-A

---

## 변경 파일

| 파일 | 변경 내용 |
|------|-----------|
| `C:\in_gps_server\app.py` | `list_devices()`의 `base_sql`을 `device` 단독 조회 → `temperature_log` LEFT JOIN(상관 서브쿼리)으로 교체, 응답 컬럼 3개 추가. 모듈 상수 `LATEST_TEMP_MAX_AGE_MINUTES = 10` 신설 |
| `C:\in_gps_server\README.md` | 엔드포인트 표의 `/devices` 용도 갱신 + `GET /devices` 응답 필드표 신규 작성 + `core_temp` 절에 "`/devices`도 이제 이 컬럼에 의존" 경고 추가 |

**DB DDL 신규 없음.** MQTT 파이프라인 변경 없음.

---

## 신규/변경 엔드포인트

### `GET /devices`  (기존 엔드포인트 확장)

- **쿼리 파라미터**: `equipment_id: Optional[str] = None` — 무변경. 지정 시 `WHERE d.equipment_id = :equipment_id`.
- **라우트 시그니처 무변경**, **응답 래핑 `{"items": [...]}` 무변경**, **인증 요구 없음(무변경)**.
- **상태 코드**: 200 고정. 별도 에러 분기 없음(기존과 동일) — DB 예외는 FastAPI 기본 500.

#### 응답 shape (실제 반환 코드: `return {"items": list(rows)}`, `rows`는 `.mappings().all()`)

```json
{
  "items": [
    {
      "device_id": "esp_32_0",
      "equipment_id": "EQ_0001",
      "status": "Connected",
      "installed_on": "2026-03-14",
      "last_seen_at": "2026-09-01T15:40:02",
      "created_at": "2026-03-14T00:47:11",
      "updated_at": "2026-08-31T18:49:03",
      "latest_temp1": 25.34,
      "latest_core_temp": 41.20,
      "latest_temp_at": "2026-09-01T15:40:01"
    }
  ]
}
```

#### 필드 계약 (Android `DeviceModel.java` `@SerializedName` 대조용)

| # | 필드명 (snake_case, 그대로) | 타입 | null 가능 | 비고 |
|---|------------------------------|------|-----------|------|
| 1 | `device_id` | string | 아니오 | **무변경** |
| 2 | `equipment_id` | string | 예 | **무변경** |
| 3 | `status` | string | 아니오 | **무변경**. 15초 룰로 계산된 값 |
| 4 | `installed_on` | string (date) | 예 | **무변경** |
| 5 | `last_seen_at` | string (datetime) | 예 | **무변경** |
| 6 | `created_at` | string (datetime) | 아니오 | **무변경**. **디바이스 등록 시각** — 온도 시각 아님 |
| 7 | `updated_at` | string (datetime) | 아니오 | **무변경** |
| 8 | `latest_temp1` | **float** | **예** | **신규**. 최신 표면온도(°C) |
| 9 | `latest_core_temp` | **float** | **예** | **신규**. 최신 AI 추정 코어온도(°C) |
| 10 | `latest_temp_at` | string (datetime) | **예** | **신규**. 8·9가 나온 `temperature_log.created_at` |

- 1~7은 **이름·타입·순서·의미 전부 무변경**. 8~10만 뒤에 추가 → 기존 소비처(웹 대시보드, 현 앱)에 하위 호환.
- `temp2`는 **추가하지 않았다**(명세 U3: 알림 대상 아님).
- 컬럼 별칭이 `latest_temp_at`이므로 `d.created_at`과 키 충돌 없음. `.mappings()` 키는 위 순서 그대로.

#### null이 되는 조건 (앱 로직에 직결 — 정확히 이대로다)

| 상황 | `latest_temp1` | `latest_core_temp` | `latest_temp_at` |
|------|----------------|--------------------|------------------|
| 최근 10분 내 `temperature_log` 행 **없음** (또는 디바이스가 한 번도 데이터 없음) | `null` | `null` | `null` |
| 최근 10분 내 행 있음, `core_temp`가 DB에 NULL (게이트웨이가 아직 core_temp 미전송 — 현 상태) | 값 | `null` | 값 |
| 최근 10분 내 행 있음, `temp1`이 `_valid_temp`(-20~80°C) 필터에 걸려 NULL 저장됨 | `null` | 값 | 값 |

> **`latest_temp_at != null` 인데 `latest_core_temp == null`** 인 조합이 현재 운영 환경의
> **정상 상태**다(게이트웨이 mfg_data core_temp 파싱 미완, 01_analyst_spec §6 U4).
> 앱은 이 경우 core_temp 알림 판정을 **건너뛰어야** 하며, 0.0으로 대체하면 안 된다.

---

## 실제 적용된 SQL

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
-- equipment_id 지정 시 여기에: WHERE d.equipment_id = :equipment_id
ORDER BY CAST(SUBSTRING_INDEX(d.device_id, '_', -1) AS UNSIGNED)
```

명세 §2-A 권장 형태 그대로다. `10`은 `LATEST_TEMP_MAX_AGE_MINUTES` 상수를 f-string으로
삽입한다 — `INTERVAL`은 바인드 파라미터를 놓을 수 없는 문법 위치이고, 값이 모듈 내 `int`
리터럴이라 인젝션 경로가 없다(`_BUCKET_TIME_SQL` 화이트리스트와 같은 관용구).

### 하드 성능 요구사항 충족 여부

| 요구 | 충족 | 근거 |
|------|------|------|
| 단일 SQL, N+1 금지 | O | `db.execute()` 호출 **1회** 유지. 파이썬 루프 없음 |
| `idx_device_created` 사용 | O (설계상) | 서브쿼리 조건이 `t.device_id = ?` 등치 + `t.created_at >= ?` 범위 + `ORDER BY t.created_at DESC` → 인덱스 `(device_id, created_at)`의 선두 컬럼 등치 + 2번째 컬럼 범위·정렬. 인덱스 역방향 스캔으로 정렬이 해소되어 `LIMIT 1`에서 즉시 중단된다 |
| `GROUP BY`+`MAX(id)` 회피 | O | 사용하지 않음 |
| 신선도 상한 10분 | O | `AND t.created_at >= NOW() - INTERVAL 10 MINUTE`, 상수 `LATEST_TEMP_MAX_AGE_MINUTES` |
| 동률 타이브레이크 → 디바이스당 정확히 1행 | O | 서브쿼리가 `LIMIT 1`로 **스칼라 하나**(`lt.id`)를 반환하고 JOIN 조건이 `lt.id = <그 값>` 즉 PK 등치다. 따라서 `created_at`이 같은 초에 몇 행이 있든 JOIN 결과는 디바이스당 최대 1행으로 **구조적으로** 보장된다. `ORDER BY created_at DESC, id DESC`는 그중 어느 행을 고를지를 결정론적으로 고정한다 |
| 페이로드 | O | 디바이스 ≤10개 × 3필드 = 수백 바이트. GZip 임계(1024B) 아래라 압축 미적용 — 명세 §2-A 예상과 일치 |

### ⚠ 미검증: `EXPLAIN` 실행 못 함

로컬에 **MySQL 서버가 없다**(설치된 것은 MySQL Workbench 8.0 CE — GUI 클라이언트뿐).
`sqlalchemy`/`pymysql`도 이 워크스테이션에 미설치(`ModuleNotFoundError`). EC2 접속은 지시대로
하지 않았다. 따라서 위 "인덱스 사용" 행은 **쿼리 계획 서술이며 실측이 아니다**.

배포 후 EC2에서 확인할 명령(사용자 수행):

```sql
EXPLAIN SELECT t.id FROM temperature_log t
 WHERE t.device_id = 'esp_32_0'
   AND t.created_at >= NOW() - INTERVAL 10 MINUTE
 ORDER BY t.created_at DESC, t.id DESC LIMIT 1;
-- 기대: key = idx_device_created, rows 소수, Extra에 "Using filesort"가 없을 것
```

디바이스당 1행 보장은 아래로 확인:

```sql
SELECT device_id, COUNT(*) FROM (<위 전체 SELECT>) x GROUP BY device_id HAVING COUNT(*) > 1;
-- 기대: 0 rows
```

### 검증 완료 항목

- `python -m py_compile app.py` → **통과**.
- f-string 렌더링 결과 확인 → `INTERVAL 10 MINUTE`로 정상 치환, SQL 문자열 형태 정상.

---

## DB 변경

**이번 작업에서 신규 DDL 없음.** 새 `.sql` 파일도 만들지 않았다.

### 🚨 배포 전 반드시 확인 — `core_temp` 컬럼 EC2 적용 여부 (미확인)

이 변경으로 **`GET /devices`가 `temperature_log.core_temp` 컬럼에 새로 의존하게 되었다.**
이전까지 `/devices`는 `device` 테이블만 단독 조회했기 때문에 `in_gps_db_ver_6.sql` 미적용
상태에서도 정상 동작했다. **이제는 그 방패가 사라졌다.**

컬럼이 EC2에 없는 상태로 이 코드를 배포하면:
- `GET /devices`가 `Unknown column 'lt.core_temp'`로 **HTTP 500**
- → Android **디바이스 목록 화면**과 **시스템 상태 화면**이 통째로 빈 화면
- → 3초 폴링이므로 에러가 계속 반복

**배포 전 사용자가 EC2에서 실행할 것:**

```bash
sudo mysql ingps -e "SHOW COLUMNS FROM temperature_log LIKE 'core_temp';"
```

- **1행이 나오면** → 이미 적용됨. 그대로 배포 가능.
- **0행이면** → 배포 **전에** 먼저 DDL 적용:

```bash
sudo mysql ingps < /path/to/in_gps_db_ver_6.sql
# 내용: ALTER TABLE temperature_log ADD COLUMN core_temp FLOAT NULL AFTER temp2;
sudo mysql ingps -e "SHOW COLUMNS FROM temperature_log LIKE 'core_temp';"   # 재확인
```

**순서: DDL 적용 → 확인 → 서버(app.py) 재배포/재시작.** 역순 금지.

> 나(server-engineer)는 EC2에 접속하지 않았고 배포하지 않았다. 코드만 작성했다.

부수 사항: `temperature_log`에 `idx_device_created (device_id, created_at)` 인덱스가
실제로 EC2에 존재하는지도 미확인이다(`temperature_db_init.sql`의 `CREATE TABLE`에 정의되어
있으나 파일 존재 ≠ EC2 반영). 없으면 3초 폴링마다 풀스캔이 된다. 함께 확인 권장:

```sql
SHOW INDEX FROM temperature_log WHERE Key_name = 'idx_device_created';
```

---

## MQTT 파이프라인 변경

**없음.** 토픽·페이로드 형태 무변경. `mqtt_subscriber.py` 미수정.
`VALID_TEMP_MIN_C/MAX_C`(-20~80°C) 필터 우회 없음 — 그 필터가 NULL로 만든 `temp1`은
`latest_temp1`에도 그대로 `null`로 나간다(위 null 조건표 3행).

---

## 앱 측 대응 필요 사항 (→ `ingps-android-engineer`, `ingps-integration-qa`)

1. **`DeviceModel.java`에 필드 3개 추가.** 필드명은 아래 세 개, 한 글자도 다르면 Gson이
   조용히 null을 넣는다(컴파일 통과·크래시 없음·알림만 안 뜸):

   ```java
   @SerializedName("latest_temp1")     private Double latestTemp1;
   @SerializedName("latest_core_temp") private Double latestCoreTemp;
   @SerializedName("latest_temp_at")   private String latestTempAt;
   ```

2. **박싱 타입(`Double`)을 쓸 것.** `double` 원시 타입이면 서버의 `null`이 `0.0`이 되어
   "0도"로 해석된다 — 알림 판정에서 오탐/미탐 양쪽으로 새어나간다.

3. **`latest_core_temp`가 null인 것이 현재 정상 상태다.** 게이트웨이가 mfg_data의
   core_temp를 아직 파싱하지 않는다(§6 U4). null이면 core_temp 알림 판정을 **건너뛴다**.

4. **`created_at`을 온도 시각으로 쓰지 말 것.** `created_at`은 디바이스 **등록** 시각이다.
   온도 시각은 `latest_temp_at`이다.

5. **신선도는 서버가 이미 10분으로 자른다.** 앱에서 다시 시간 비교를 할 필요는 없지만,
   `latest_temp_at`이 있으면 알림 문구의 "언제 값인지" 표기에 쓸 수 있다.

6. 기존 7개 필드는 무변경이므로 **현재 `DeviceModel` 코드는 그대로 두고 추가만 하면 된다.**

---

## 남은 미검증 항목 요약

| 항목 | 상태 |
|------|------|
| `app.py` 문법 | 검증 완료 (`py_compile` 통과) |
| SQL 문자열 렌더링 | 검증 완료 |
| `EXPLAIN` 인덱스 사용 실측 | **미검증** — 로컬 MySQL 서버 부재 |
| 디바이스당 1행 실측 | **미검증** (단, PK 등치 JOIN이라 구조적으로 보장됨) |
| EC2에 `core_temp` 컬럼 존재 | **미검증 — 배포 전 사용자 확인 필수 (위 🚨)** |
| EC2에 `idx_device_created` 존재 | **미검증** |
| 실제 HTTP 응답 | **미검증** — 서버 미기동 (의존성 미설치) |
