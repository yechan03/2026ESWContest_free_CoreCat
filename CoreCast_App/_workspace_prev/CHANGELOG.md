# IN-GPS 변경 이력

최신 항목을 위에 추가한다(append). 과거 항목은 편집하지 않는다.

---

## 2026-08-31 — `core_temp` (SHF 추정 내부온도) 추가

ESP32가 SHF(Surface Heat Flux) 모델로 산출한 **설비 내부 추정온도**를 BLE mfg_data →
게이트웨이 → MQTT → DB → REST → Android 앱까지 전달하고, 앱의 기존 "AI 예측 온도"
카드에 표시한다.

### 파이프라인 (최종 아키텍처)

```
ESP32-S3  mfg_data[13..14] core_temp_x100 (int16 LE, °C×100)   [완료]
   ↓ BLE adv (총 28B / 31B 한계)
STM32 게이트웨이  offset [13..14] 파싱 → ÷100 → JSON "core_temp"  [★ 미완료 — 다른 담당자]
   ↓ MQTT  ingps/sensor
mqtt_subscriber.handle_sensor()  _cast_float() → temperature_log.core_temp   [완료]
   ↓
FastAPI  /temperature · /sensor · /temperature/chart · /chart/{device_id}    [완료]
   ↓ Retrofit
Android  TemperatureModel.coreTemp (Float) → SensorDetailFragment AI 카드     [완료]
```

### 변경 파일

**ESP32 펌웨어 (`C:\esp\in_gps_project`)**

| 파일 | 변경 |
|------|------|
| `main/ble/ble_adv.c` | `mfg_data[]` 13B→15B(끝에 append). `build_mfg_data()`에 SHF 호출 + int16 인코딩 + 무효 센티넬(-32768) 처리 + 포화 클램프(하한 -32767로 센티넬 충돌 방지). `<math.h>`/`shf_core_model.h` include. 레이아웃 주석 전면 갱신 |
| `main/sensor/shf_core_model.h` | 주석만 — "NOT WIRED IN" → "WIRED IN (2026-08-31), mfg_data[13..14], 임계 단독근거 금지". 수식·계수 무변경 |
| `main/app_main.c`, `main/sensor/adxl345.h` | 주석만 — mfg_data 길이 서술 정정. 코드 무변경 |

**서버 (`C:\in_gps_server`)**

| 파일 | 변경 |
|------|------|
| `mqtt_subscriber.py` | `_cast_float()` 신설(타입 검증 + `< -200.0` 센티넬 배제, 상한 없음). `handle_sensor()`에서 `core_temp` 파싱 → INSERT 컬럼/바인드 추가, 무효 시 `logger.warning`, 성공 로그에 `CORE=` 추가 |
| `in_gps_db_ver_6.sql` | **신규** — `ALTER TABLE temperature_log ADD COLUMN core_temp FLOAT NULL AFTER temp2;` |
| `app.py` | `SensorLogIn`/`SensorLogUpdate`에 `core_temp: Optional[float] = None`. `POST /sensor` INSERT, `PATCH /sensor/{id}` UPDATE 반영. SELECT 6곳(`GET /sensor` 2, `GET /temperature` since 경로, `GET /chart/{device_id}` raw/집계, `GET /temperature/chart` AVG)에 `core_temp` 추가 |

**Android 앱 (`C:\AndroidProject\IN_GPS`)**

| 파일 | 변경 |
|------|------|
| `model/TemperatureModel.java` | `@SerializedName("core_temp") public Float coreTemp;` — **wrapper `Float`** |
| `fragment/SensorDetailFragment.java` | `tvAiTemp` 선언 + `R.id.tv_ai_temp` 바인딩 + `getTemperatureData()` 콜백에 null 분기 렌더링 |
| `res/layout/fragment_sensor_detail.xml` | 카드 제목 `"AI 예측 온도"` → `"AI 예측 온도 (추정치)"` (TextView `android:text` 1줄) |

`ApiService` / `RetrofitClient` / `TemperatureRepository` / `SensorDetailViewModel` /
MPAndroidChart 관련 코드는 **무변경**. Repository가 파싱된 인스턴스를 필드 복사 없이
그대로 `postValue`하므로 모델에 필드를 추가하는 것만으로 Fragment까지 값이 도달한다.
신규 엔드포인트 없이 기존 응답에 필드만 추가했으므로 `ApiService` 수정도 불필요했다.

**문서**

| 파일 | 변경 |
|------|------|
| `ingps-system-map/references/contracts.md` | §1 mfg_data 15B 전체 레이아웃, §2 페이로드, §3 DTO, §5 응답 shape, §6 Retrofit 매핑, §7 DB, §8 값 도메인 갱신 + 미해결 불일치 4건 추가 |
| `ingps-system-map/references/firmware-map.md` | mfg_data 15B 반영, 31B 예산 잔여 3B 경고, `mfg_data` 발원지를 `adv_manager.c` → `ble_adv.c`로 정정 |

### 스코프 정정 이력

초기 분석(`01_analyst_spec.md`)이 **"Android가 BLE를 직접 스캔한다"**고 오판해 앱에
BLE 스캐너·권한·파서(신규 7파일 + Manifest/layout 수정)를 구현했다가, 실제 아키텍처가
기존 **게이트웨이 → MQTT → REST 경유**임이 확인되어 `git checkout` + 파일 삭제로
**전량 되돌렸다**(`01b_scope_correction.md`). 되돌림 후 `ble/` 디렉토리 부재와
Manifest의 BLE/LOCATION 권한 0건을 확인했다. ESP32 펌웨어 변경분은 두 아키텍처
모두에서 유효하므로 유지됐다.

> 이 오판의 잔재가 `ble_adv.c` 주석에도 남아("소비자는 Android BLE 직접 스캔뿐",
> "게이트웨이/서버/DB는 손댈 필요 없다") **게이트웨이 담당자가 `[13..14]` 파싱을
> 불필요하다고 오판할 위험**이 있었다. QA에서 발견해 주석을 현행 아키텍처로 정정했다.

### 설계 결정

1. **`core_temp`에 `_valid_temp()`(-20~80°C)를 적용하지 않는다.** 그 필터는 AS6221
   **표면** 센서의 노이즈용이고, `core_temp`는 과열 설비 **내부 추정치**라 80°C 초과가
   설계상 정상이다. 같은 필터를 걸면 정작 감시해야 할 과열 구간만 골라서 NULL이 된다.
2. **대신 하한 -200°C 가드만 둔다.** ESP 무효 센티넬(-32768 → ÷100 = -327.68°C)을
   배제하기 위함. SHF 모델의 이론적 출력 범위가 -141.5~138.1°C라 정상값을 자르지 않는다.
3. **`coreTemp`를 wrapper `Float`으로 선언한다.** primitive `float`이면 Gson이 JSON
   `null`을 `0.0f`로 남겨 "0.0°C"가 조용히 렌더링된다 — `temp1`/`temp2`가 이미 걸려 있는
   기존 함정이다. 온도 계열 신규 필드는 이 쪽을 따를 것.

### 시도했다가 되돌린 접근 (같은 실패 반복 방지)

- **Android BLE 직접 스캔** — 위 "스코프 정정 이력" 참조. 이 시스템에서 앱은 BLE를
  스캔하지 않는다. 센서 데이터는 **항상** 게이트웨이 → MQTT → 서버 → REST 폴링으로 온다.
- **`_valid_temp()`를 `core_temp`에 재사용** — 검토했으나 과열 구간을 통째로 NULL로
  만들어 기능의 목적 자체를 무효화하므로 채택하지 않았다.

### QA 결과

정적 코드 대조로 경계면 12개 검증 → 통과 9 / 실패 1 / 미검증 2.

- **실패 1건**(수정 완료): ESP 무효 센티넬 `-32768`을 파이프라인 전 구간에서 아무도
  처리하지 않아, 센서 고장 시 앱에 "측정 불가"가 아니라 `-327.7°C`가 표시되고 DB·AVG
  집계까지 오염되는 결함. 되돌려진 BLE 아키텍처에서 Android가 맡기로 했던 센티넬 처리
  책임이 아키텍처 정정 시 이관되지 않아 생긴 공백이었다.
  → `_cast_float()`에 `< -200.0 → None` 가드 추가 + `ble_adv.c` 주석 정정으로 조치
  (`04b_fix_applied.md`).
- **필드명 5곳 일치 확인**: MQTT 키 / Pydantic DTO / DB 컬럼 / SELECT alias /
  `@SerializedName` 전부 `core_temp` — alias 변형 0건.

### 알려진 미해결 사항

| # | 내용 | 담당 | 상태 |
|---|------|------|------|
| 1 | **게이트웨이가 `mfg_data[13..14]`를 파싱하지 않아 `core_temp`가 실제로는 흐르지 않는다.** 앱은 항상 "측정 불가" 표시 — 정상 동작이며 장애가 아니다 | 게이트웨이 담당자 (세션 밖) | **미완료** |
| 2 | EC2에 `in_gps_db_ver_6.sql` 미적용 | 사용자 | **미확인** |
| 3 | 서버 미배포·미재시작, 앱 미빌드 | 사용자 | 미수행 |
| 4 | `SensorLogIn`/`SensorLogUpdate.core_temp`에 `ge=-200.0` 미적용 — 센티넬 가드가 MQTT 경로에만 있음 | server-engineer | 비대칭 잔존 |
| 5 | `/temperature/chart`에 `core_temp_max`/`core_temp_min` 없음 → min/max 밴드 차트 불가. `/export/sensor.csv`에도 미포함 | — | 추후 확장 |
| 6 | `esp_byte_id` 매핑 규칙 주석↔코드 불일치(기존 문제). `core_temp`가 어느 device 행에 쌓이는지를 좌우 | system-analyst | 기존 미해결 |
| 7 | SHF 모델 비단조 — Ta≈68°C 위로는 표면이 뜨거워질수록 `core_temp`가 내려간다. **과열 임계 판정의 단독 근거로 쓰면 안 된다** | — | 제품 semantics |
| 8 | SHF 정확도(벤치 MAE 0.508°C)가 실제 하드웨어에서 유지되는지 | 사용자 | 완전 미검증 |

> **⚠ 배포 순서**: DDL(`in_gps_db_ver_6.sql`)을 **먼저** 적용하고 그 다음 서버/subscriber를
> 재시작할 것. 순서가 뒤집히면 INSERT 전량 실패(그 행의 temp1/temp2/rms까지 유실)에
> 더해 `core_temp`를 참조하는 **SELECT 6곳이 HTTP 500**을 반환해, 앱에서 표면/외부 온도·
> 이벤트 칩·차트까지 전부 갱신이 멈춘다. 신규 필드 손실이 아니라 서비스 전체 가용성 문제다.

### 검증 상태

- **정적 검증만 수행.** ESP-IDF 전체 빌드, Gradle 빌드, 서버 실행, DB 조회, 실기
  렌더링은 **전부 미검증**이다.
- 확인한 것: `python -m py_compile app.py mqtt_subscriber.py` 통과, 신규 C 코드 블록을
  실제 xtensa 툴체인으로 `-Wall -Wextra -Wconversion` 컴파일 통과(경고 0),
  `_cast_float()` 단위 동작, `git diff` 전수 확인(스코프 밖 변경 없음).
