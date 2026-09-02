# 스코프 정정 (2026-08-31, Phase 2 도중)

`01_analyst_spec.md`의 "Android 직접 BLE 스캔" 전제가 틀렸다. 사용자 정정:

1. **Android는 BLE를 직접 스캔하지 않는다.** 기존 파이프라인(ESP32 --BLE--> Gateway --MQTT--> Server --REST--> Android)을 그대로 쓴다.
2. **게이트웨이는 다른 팀원이 수정 중** — 이 세션의 스코프 아님. 게이트웨이가 mfg_data를 파싱해 MQTT로 JSON을 publish하는 부분은 이미 `mqtt_subscriber.py`가 기대하는 형식과 동일한 패턴을 따른다.
3. **서버(FastAPI + mqtt_subscriber + DB)는 이 세션 스코프.** 사용자가 명시적으로 구현 요청.
4. **Android는 새 UI를 만들지 않는다.** 이미 존재하지만 미사용 상태인 "AI 예측 온도" 카드(`fragment_sensor_detail.xml`의 `card_ai_prediction`/`tv_ai_temp`, `SensorDetailFragment.java`에서 전혀 바인딩되지 않은 죽은 뷰)에 이 값을 연결한다.
5. **실제 MQTT JSON 계약을 사용자가 확정해서 전달함:**
   ```json
   {"gateway_id":"GW_LN01","esp_byte_id":0,"temp1":25.34,"temp2":25.10,"core_temp":41.20,"rms_x":12,"rms_y":8,"rms_z":5}
   ```
   토픽: `ingps/sensor` (기존 `handle_sensor()`가 처리하는 바로 그 토픽). 필드명 `core_temp` 확정 — 더 이상 추측 아님.

## Phase 2 되돌림 내역
- `C:\AndroidProject\IN_GPS`의 BLE 직접 스캔 구현(신규 파일 7개 + Manifest/layout/colors/Fragment 수정)을 `git checkout` + 삭제로 전량 되돌림. `02_firmware_report.md`가 기록한 ESP32 mfg_data 변경(`ble_adv.c`, SHF wire-in)은 **유효하며 유지** — 게이트웨이가 이 mfg_data를 파싱해 위 JSON으로 변환해 publish하는 흐름과 일치한다.
- 진행 중이던 구 아키텍처 기준 integration-qa 작업(`a4bfbf188a93d1807`)은 중단(kill). Android BLE 파서 관련 검증 내용은 전부 무효 — 재검증 필요 없음(해당 코드 자체가 삭제됨).

## 정정된 스코프 (Phase 2 재실행)

### 서버 (`C:\in_gps_server`) — 신규 작업
1. DB: `temperature_log`에 `core_temp FLOAT NULL` 컬럼 추가 DDL 작성 (사용자가 EC2에 직접 적용).
2. `mqtt_subscriber.py` `handle_sensor()`: `payload.get("core_temp")` 파싱 → INSERT에 포함.
   - 기존 `VALID_TEMP_MIN_C/MAX_C`(-20~80) 필터는 **적용하지 않는다** — 그건 AS6221 표면 센서 노이즈 필터용이고, core_temp는 설계 목적상 그 범위를 넘는 값(과열 설비 내부 추정치)을 정상적으로 낼 수 있다. 타입 검증(float 캐스팅 실패 시 None)만 한다.
3. `SensorLogIn`/`SensorLogUpdate` Pydantic DTO에 `core_temp: Optional[float] = None` 추가.
4. `/temperature` 응답(SELECT)에 `core_temp` 포함 — 앱의 "AI 예측 온도" 카드가 최우선으로 쓰는 게 이 엔드포인트일 가능성이 높음(확인 필요).
5. `/temperature/chart`에도 대칭적으로 포함 권장(AVG, 기존 temp1/temp2처럼 max/min까지는 이번 스코프에서 필수 아님 — 최소 AVG만).

### Android (`C:\AndroidProject\IN_GPS`) — 신규 작업 (BLE 스캔 아님)
1. `TemperatureModel.java`에 `@SerializedName("core_temp") public Float coreTemp;` 추가 — **wrapper Float, primitive 아님** (NULL이 0.0으로 렌더링되는 이 프로젝트의 기존 함정을 반복하지 않기 위해. temp1/temp2가 이미 이 함정에 걸려있는 사례가 계약서에 기록되어 있음 — core_temp는 처음부터 올바르게).
2. `SensorDetailFragment.java`: `R.id.tv_ai_temp`를 새 필드로 바인딩(`onViewCreated`의 기존 `findViewById` 블록, 137행 부근에 추가). `viewModel.getTemperatureData().observe(...)` 콜백(149~153행)에서 `tvSurfaceTemp`/`tvExternalTemp`와 같은 자리에 `coreTemp` null 체크 후 표시: null이면 "측정 불가" 또는 기존 placeholder "--°C" 유지, 값 있으면 "%.1f°C" 포맷.
3. 기존 `chip_ai_status`는 건드리지 않는다 (이미 `chipEventStatus`로 event 상태 표시에 재사용 중 — AI 카드의 상태칩이 아니라 이벤트칩으로 재목적된 상태. 이번 스코프에서 원상복구하거나 새 용도 부여하지 않는다).
4. MPAndroidChart 차트는 이번 스코프에서 손대지 않는다 (카드 텍스트만).
5. 신규 BLE 관련 클래스/권한/스캐너는 만들지 않는다.

### QA
- `core_temp` 키 이름이 MQTT payload → Pydantic DTO → DB 컬럼 → SELECT alias → Gson `@SerializedName` → UI 전 구간에서 정확히 일치하는지 교차검증.
- NULL 전파: DB NULL → JSON null → Android `Float` null → UI "측정 불가" (0.0으로 안 새는지 각 단계 확인).
- 기존 temp1/temp2/rms 경로에 회귀 없는지 확인.

### 문서화
- `contracts.md` §3(DTO)/§5(응답 shape)/§6(Retrofit 매핑)/§7(DB 테이블)에 `core_temp` 반영.
