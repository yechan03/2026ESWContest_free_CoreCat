# 기능 명세 — BLE mfg_data에 SHF 추정 내부온도 추가 + Android 직접 수신

작성: orchestrator (system-analyst 서브에이전트 호출 생략 — 사전 조사로 계약 확정, 사용자 확인 완료)
날짜: 2026-08-31

## 요구사항 원문
게이트웨이를 거치지 않고, ESP32에서 이미 계산 가능한 SHF 계수로 내부온도를 산출해
BLE 광고 패킷에 싣고, Android 앱이 이를 직접 BLE 스캔으로 받아 쓸 수 있게 한다.

## 영향 레이어

| 레이어 | 변경 필요 | 사유 |
|--------|----------|------|
| ESP32 센서 노드 | ✅ | mfg_data에 신규 필드 추가, SHF 모델 wire-in |
| STM32 게이트웨이 | ❌ | 스코프 제외 (사용자 명시). 하위 호환 확인만 (길이 `>=` 체크라 안전) |
| FastAPI 서버 | ❌ | 스코프 제외. 이 값은 DB에 저장되지 않음 |
| Android 앱 | ✅ | 신규 서브시스템: BLE 직접 스캔 (기존에 전무) |

## 사용자 결정 완료 사항
**TH1/TH2 물리 배치가 SHF 모델 학습 가정과 일치함을 사용자가 확인함**
(TH1=temp1=설비 표면/열원 근접 = t_ambient, TH2=temp2=실온 기준 = t_room).
→ `shf_predict_core_temp(temp1_c, temp2_c)` 그대로 호출.

## 경계면 계약 변경

### mfg_data 레이아웃 (13→15바이트, ESP 배열 기준 절대 offset)

| offset | 필드 | 타입 | 비고 |
|--------|------|------|------|
| 0..1 | company ID | LE = 0x1234 | 기존, 불변 |
| 2..3 | temp1_x100 | int16 LE | 기존, 불변 |
| 4..5 | temp2_x100 | int16 LE | 기존, 불변 |
| 6..7 | rms_x | uint16 LE | 기존, 불변 |
| 8..9 | rms_y | uint16 LE | 기존, 불변 |
| 10..11 | rms_z | uint16 LE | 기존, 불변 |
| 12 | device ID | uint8 | 기존, 불변 |
| **13..14** | **core_temp_x100 (NEW)** | **int16 LE** | SHF 추정치. 무효 시 센티넬 `AS6221_TEMP_INVALID_X100`(-32768) |

**게이트웨이 파싱(`app_ble.c:529`)은 `field_len >= 14`(≥ 비교)라 끝에 붙이는 신규 바이트는
게이트웨이/서버 파이프라인을 깨지 않는다. 기존 13바이트(offset 0~12)는 절대 변경 금지.**

### Android 수신 측 오프셋 (company ID 2바이트가 이미 제거된 상태 — ESP 배열보다 -2 시프트)

`ScanRecord.getManufacturerSpecificData(0x1234)` 반환 byte[] 기준:

| offset | 필드 |
|--------|------|
| 0..1 | temp1_x100 |
| 2..3 | temp2_x100 |
| 4..5 | rms_x |
| 6..7 | rms_y |
| 8..9 | rms_z |
| 10 | device ID |
| **11..12** | **core_temp_x100 (NEW)** |

이 -2 시프트를 놓치면 온도가 RMS 값으로 읽히는 등 전부 어긋난다 — QA 필수 확인 항목.

## SHF 모델 관련 제약 (반드시 UI에 반영)
- `shf_core_model.h`는 모터 벤치 데이터로 학습, 이 하드웨어(ESP32-S3+AS6221)에서
  온디바이스 검증 안 됨 (MAE 0.508°C/RMSE 0.593°C는 벤치 기준).
- Android UI에는 **"추정 내부온도(SHF, 실측 아님)"** 로 명확히 라벨링.
- 서버 DB에 저장하지 않음 — 화재 알람 임계값의 단독 근거로 쓰지 않는다 (이번 스코프에는
  알람 로직 자체가 없으므로 해당 없음이지만 향후 확장 시 원칙으로 남긴다).

## 값 도메인
- `core_temp_x100`: 유효 시 SHF 예측값 ×100 (int16 범위 내), 무효 시 `-32768` 센티넬.
- 무효 조건: `ok1 == false || ok2 == false` (AS6221 TH1/TH2 중 하나라도 읽기 실패).
- Android는 센티넬을 0°C로 렌더링하지 말 것 (계약서에 이미 기록된 "primitive float
  NULL→0.0" 함정과 동일 클래스 버그 — wrapper 타입 또는 valid 플래그 필수).

## BLE 페이로드 예산 (31바이트 한계)
- Flags AD: 3B
- Name AD (knob `advm/name` on 시): 이름 길이 + 2B 헤더, 기존 주석상 "~8B"
- Mfg Data AD: 2B(len+type) + mfg_data 배열 15B = 17B
- 합계(이름 포함 시): 3 + ~8 + 17 = ~28B ≤ 31B — 여유 있음.
  firmware-engineer가 실제 이름 문자열 길이로 재계산해 보고서에 명시할 것.

## 구현 순서
1. firmware-engineer: `ble_adv.c`에 SHF wire-in + mfg_data 확장 (계약 원천)
2. android-engineer: BLE 스캐너 신규 구현 (계약은 이미 확정되어 있어 firmware와 병행 가능)
3. integration-qa: 오프셋 교차검증 + 센티넬 왕복 + 페이로드 예산 + 게이트웨이 하위호환
4. doc-writer: contracts.md §1 갱신, CHANGELOG

## 스코프 경계
- STM32 게이트웨이, FastAPI 서버, DB 스키마: 변경 없음.
- 이 기능의 값은 서버에 저장되지 않고 Android가 BLE에서 직접 읽어 휘발성으로만 표시.
