# 펌웨어 구현 보고: BLE mfg_data에 SHF 추정 내부온도(core_temp) 추가

작성: ingps-firmware-engineer
날짜: 2026-08-31
입력 명세: `C:\AndroidProject\IN_GPS\_workspace\01_analyst_spec.md`

---

## 1. 변경 파일

| 파일 | 보드 | 변경 내용 |
|------|------|-----------|
| `C:\esp\in_gps_project\main\ble\ble_adv.c` | ESP32-S3 | `<math.h>` + `sensor/shf_core_model.h` include 추가. `mfg_data[]` 13B→15B(끝에 append). `build_mfg_data()`에 SHF 호출 + int16 인코딩 + 센티넬 처리. 레이아웃 주석 갱신, 31B 예산 주석 추가, DEBUG 로그 1줄 추가. |
| `C:\esp\in_gps_project\main\sensor\shf_core_model.h` | ESP32-S3 | **주석만.** "NOT WIRED IN" 항목이 이제 사실이 아니므로 "WIRED IN (2026-08-31) + mfg_data[13..14] + DB 미저장 + 임계 단독근거 금지"로 교체. 수식·계수 무변경. |
| `C:\esp\in_gps_project\main\app_main.c` | ESP32-S3 | **주석만.** 291행 "mfg_data 13B" → "앞 13B(offset 0..12)" + 전체 15B 사실 추가. 코드 무변경. |
| `C:\esp\in_gps_project\main\sensor\adxl345.h` | ESP32-S3 | **주석만.** 29행 "mfg_data 13B 계약" → "RMS 필드 배치(offset 6..11)". 코드 무변경. |

STM32 게이트웨이 / FastAPI 서버 / DB: **변경 없음** (스코프 제외, 아래 §4에서 무변경이 안전함을 검증).

### 핵심 코드 (ble_adv.c, `build_mfg_data()`)

```c
int16_t core_temp_x100 = AS6221_TEMP_INVALID_X100;
if (ok1 && ok2) {
    float core_c = shf_predict_core_temp(temp1 / 100.0f, temp2 / 100.0f);
    long v = lroundf(core_c * 100.0f);
    if (v >  32767) v =  32767;
    if (v < -32767) v = -32767;   /* 하한 -32767: 센티넬(-32768)과 충돌 방지 */
    core_temp_x100 = (int16_t)v;
}
...
mfg_data[13] = (uint8_t)((uint16_t)core_temp_x100 & 0xFF);
mfg_data[14] = (uint8_t)((uint16_t)core_temp_x100 >> 8);
```

- 인자 순서는 명세대로 `(temp1, temp2)` = `(t_ambient, t_room)`. 바꾸지 않았다.
- `mfg_data[12]`(device ID)는 정적 초기화값 그대로, 매 사이클 갱신 대상이 아니다 — 기존 동작 유지.
- **포화 클램프는 명세에 없던 추가분이다.** 이유: 계산 결과가 우연히 -32768이 되면
  "무효 센티넬"과 구분이 불가능해진다. 하한을 -32767로 잡아 두 상태를 항상 구분 가능하게 했다.
  실제로는 §5에서 보듯 전 입력구간에서 포화가 발생하지 않으므로 순수 방어 코드다.

---

## 2. 최종 mfg_data 레이아웃 (15바이트 전체)

ESP 배열 기준 절대 offset.

| offset | 필드 | 타입/스케일 | 무효값 | 변경 여부 |
|--------|------|-------------|--------|-----------|
| 0..1 | company ID | uint16 LE = 0x1234 | — | 불변 |
| 2..3 | temp1_x100 | int16 LE, °C×100 (AS6221 TH1) | -32768 | 불변 |
| 4..5 | temp2_x100 | int16 LE, °C×100 (AS6221 TH2) | -32768 | 불변 |
| 6..7 | rms_x | uint16 LE, mg | 0 | 불변 |
| 8..9 | rms_y | uint16 LE, mg | 0 | 불변 |
| 10..11 | rms_z | uint16 LE, mg | 0 | 불변 |
| 12 | device ID | uint8 = 0x01 | — | 불변 |
| **13..14** | **core_temp_x100** | **int16 LE, °C×100 (SHF 추정)** | **-32768** | **신규** |

무효 조건: `ok1 == false || ok2 == false` (TH1/TH2 중 하나라도 읽기 실패) → `-32768`.

### Android 수신 측 offset (company ID 2B 제거된 배열 = ESP offset -2)

`ScanRecord.getManufacturerSpecificData(0x1234)` 반환 `byte[]` 길이 = **13**.

| offset | 필드 |
|--------|------|
| 0..1 | temp1_x100 |
| 2..3 | temp2_x100 |
| 4..5 | rms_x |
| 6..7 | rms_y |
| 8..9 | rms_z |
| 10 | device ID |
| **11..12** | **core_temp_x100** |

Android는 `-32768`을 0°C로 렌더링하면 안 된다 (wrapper 타입 또는 valid 플래그 필수).

---

## 3. BLE 광고 페이로드 31바이트 예산 — 실측 계산

디바이스 이름은 `app_main.c:319`의 `ble_svc_gap_device_name_set("IN_GPS")` → **"IN_GPS", 6자**.
(sdkconfig 기본값 `CONFIG_BT_NIMBLE_SVC_GAP_DEVICE_NAME="nimble"`은 이 런타임 set으로 덮인다.
NimBLE 스택 init 직후 · adv 태스크 생성 전에 호출되므로 첫 광고부터 "IN_GPS"가 실린다.)

이름 knob `advm/name`은 기본 1(=포함), NVS로 끌 수 있다 → **켜진 쪽이 최악 케이스**.

| AD 구조체 | 계산 | 바이트 |
|-----------|------|--------|
| Flags | 1(len) + 1(type 0x01) + 1(값) | 3 |
| Complete Local Name | 1(len) + 1(type 0x09) + 6("IN_GPS") | 8 |
| Manufacturer Specific Data | 1(len) + 1(type 0xFF) + 15(mfg_data) | 17 |
| **합계 (이름 포함, 최악)** | | **28** |
| 한계 (`BLE_HS_ADV_MAX_SZ`) | | 31 |
| **여유** | | **3 바이트** |

이름을 끄면 20B(여유 11B).

**결론: 31B 이내. 구현 강행 아님, 정상 통과.**

경고 — 남은 여유가 3B뿐이다. mfg_data에 2B(int16 1개)를 더 붙이면 30B로 아슬아슬하게 통과하지만
4B를 붙이면 32B로 **초과**한다. 초과 시 `ble_hs_adv_set_fields()`가 에러를 반환하고,
부팅 경로에서는 `wdt_guard_reboot("adv encode failed at boot")`로 **재부팅 루프**가 된다
(런타임 경로는 `continue`로 살아남지만 광고 데이터가 갱신되지 않는다).
다음 필드 추가 시 이름 AD 생략(=8B 확보)이 첫 번째 대안이다. 이 내용을 `encode_adv_fields()` 주석에 남겼다.

---

## 4. 게이트웨이 하위 호환 — 직접 검증함

`C:\ingps_Gateway\IN_GPS_GATEWAY_PCB_TEST\STM32_WPAN\App\app_ble.c` 코드를 직접 열어 확인:

- **504행** `uint8_t data_len = p_data[8];` — HCI adv report의 Length_Data = AD 전체 길이 = **28**(변경 후).
- **523행** AD 워크 가드 `if (field_len == 0 || (j + field_len) >= data_len) break;`
  - Mfg AD는 j=11에서 시작, field_len=16 → `j+field_len = 27`, `27 >= 28`은 **거짓** → break 안 걸림. 정상 진입.
  - (변경 전: j=11, field_len=14 → 25 >= 26 거짓. 동일하게 정상.) → **off-by-one 함정 없음.**
- **529행** `if (field_type == 0xFF && field_len >= 14)` — 변경 후 field_len=16, `16 >= 14` **참**. 통과.
- **567~572행** 읽는 최대 offset은 `adv_data[j+14]`(= mfg_data[12], device ID). **`mfg_data[13..14]`는 읽지 않는다.**

**결론: 게이트웨이·MQTT·서버·DB는 무변경으로 안전하다. server-engineer에게 요청할 대응 사항 없음.**

---

## 5. SHF 모델 값 도메인 검증 (오프타깃 수치 검증)

`shf_core_model.h`의 계수로 Python에서 전 입력구간을 스윕:

| 입력 (Ta, Tr) | 추정 core |
|---------------|-----------|
| 25.5, 25.0 (평범한 실내) | 29.77 °C |
| 34.23, 26.61 (학습 평균점) | 40.51 °C |
| 60.0, 26.0 | 58.62 °C |
| 80.0, 26.0 | 57.17 °C |
| 125.0, -40.0 (AS6221 극단) | 113.45 °C |
| -40.0, 125.0 (AS6221 극단) | -22.81 °C |

**AS6221 전 동작범위(-40~125°C, 두 입력 모두, 1°C 격자) 스윕 결과: 출력 -141.53 ~ +138.08 °C**
→ `x100` 기준 **-14153 ~ +13808**. int16 범위(-32767~32767) 안에 완전히 들어간다.
**오버플로 불가능, 센티넬(-32768) 우연 충돌 불가능.** §1의 클램프는 순수 방어 코드로 확인됐다.

### ⚠ 발견: 모델이 비단조(non-monotonic)다 — 안전 관련

`SHF_W_TA2 = -0.333`(음수)이라 Ta에 대해 **위로 볼록한 포물선**이다.

| Ta (Tr=26°C 고정) | 추정 core |
|-------------------|-----------|
| 50 °C | 54.31 °C |
| 60 °C | 58.62 °C |
| **68 °C (꼭짓점)** | **59.65 °C (최대)** |
| 80 °C | 57.17 °C |
| 90 °C | 51.41 °C |
| 100 °C | 42.29 °C |

**즉 표면 온도(TH1)가 68°C를 넘어 더 뜨거워지면 추정 내부온도는 오히려 내려간다.**
학습 분포(Ta 평균 34.2°C, σ=4.46°C)에서 68°C는 +7.6σ로, 완전한 외삽 구간이라 생긴 현상이다.

- 이번 스코프에는 알람 로직이 없으므로 **당장 위험은 없다.**
- 그러나 **이 값을 과열/화재 임계 판정에 쓰면 안 된다** — 가장 위험한 구간에서 값이 거꾸로 간다.
  실측 `temp1`/`temp2`가 그 역할을 맡아야 한다.
- 이 내용을 `ble_adv.c` 코드 주석에 구체적 수치와 함께 남겼다 (후임자 오인 방지).
- Android UI 라벨은 명세대로 **"추정 내부온도(SHF, 실측 아님)"**. 위 비단조성 때문에
  라벨링을 완화하지 말 것을 권고한다.

---

## 6. 컴파일 가능성 확인 — 방법과 결과

**여기서는 ESP-IDF 전체 빌드를 실행하지 않았다** (IDF 환경 미기동). 대신 두 단계로 확인:

1. **실제 타깃 툴체인으로 신규 코드 블록 컴파일 (PASS)**
   `C:\Espressif\tools\xtensa-esp-elf\esp-14.2.0_20251107\...\xtensa-esp-elf-gcc.exe`로,
   추가된 코드 블록을 그대로 옮긴 독립 파일을 컴파일:
   `-c -std=gnu17 -Wall -Wextra -Wconversion -mlongcalls` → **경고 0, 에러 0으로 통과.**
   (`-Wconversion`까지 켠 상태에서 `(uint8_t)((uint16_t)core_temp_x100 >> 8)` 캐스팅이
   경고 없이 통과함을 확인 — 기존 필드와 동일한 캐스팅 관용구다.)
2. **수동 검토 (PASS)**
   - `main/CMakeLists.txt`가 `INCLUDE_DIRS "."` → `#include "sensor/shf_core_model.h"` 해결됨.
     (`ble_adv.c`가 이미 `"sensor/sensor.h"`를 같은 방식으로 include 중.)
   - `lroundf`: `main/CMakeLists.txt:21`에 `target_link_libraries(${COMPONENT_LIB} INTERFACE m)`가
     이미 있어 링크 문제 없음. `<math.h>` include 추가함.
   - `shf_predict_core_temp`는 `static inline` — 별도 .c 없음, CMakeLists SRCS 수정 불필요.
   - `fields.mfg_data_len = sizeof(mfg_data)` (193행) — 이미 sizeof 기반이라 자동으로 15. **손대지 않음.**
   - 프로젝트 전체에서 `mfg_data` 길이를 하드코딩한 곳이 없음을 grep으로 확인 (주석 3곳만 있었고 갱신함).

**⚠ 실제 전체 빌드는 미검증이다.** 사용자가 `idf.py build`로 직접 확인해야 한다.

---

## 7. 저전력 / 워치독 영향

- **깨어남 주기 변화: 없음.** 신규 코드는 기존 `build_mfg_data()` 내부, 기존 광고 사이클 안에서만
  실행된다. 새 태스크·타이머·wake 소스를 추가하지 않았다. I2C 접근 횟수도 그대로다
  (이미 읽은 temp1/temp2를 재사용).
- **추가 연산량: 부동소수 곱 7회 + 덧셈 5회 + `lroundf` 1회.** ESP32-S3는 단정밀도 FPU가 있어
  수 µs 수준이며, 사이클당 1회다. 소비 전력 영향은 측정 한계 이하로 판단한다 (미실측).
- **워치독: 영향 없음.** `wdt_guard` 관련 코드를 건드리지 않았고, 블로킹 구간·지연·루프를
  추가하지 않았다. `WDT_HB_ACCEL`/`WDT_HB_ADV` kick 지점과 stale 임계 모두 무변경.
- **광고 페이로드 2B 증가 → TX 시간 소폭 증가.** 1Mbps PHY에서 2바이트 = 16 µs/광고 이벤트.
  3채널 광고 기준 이벤트당 약 48 µs 증가. 배터리 수명 영향은 무시 가능 수준으로 추정하나 **미실측**.
- **ULP: 해당 없음** (rev 4.0에서 ULP 서브시스템 제거됨 — `main/CMakeLists.txt:23` 참조).

---

## 8. 하드웨어 실측 확인 필요 항목

전부 **미검증**. 사용자가 실물로 확인해야 하는 항목이다.

- [ ] **ESP-IDF 전체 빌드** / `idf.py build` / 에러·경고 0으로 완료
- [ ] **광고 패킷 길이** / nRF Connect 등 스캐너로 raw AD 확인 / 총 28B, Mfg AD가 `10 FF 34 12 ...`(len=0x10=16)로 보일 것
- [ ] **mfg_data 15바이트 수신** / 스캐너에서 Manufacturer Data 길이 확인 / company ID 제외 13B (= 15B - 2B)
- [ ] **core_temp 값 타당성** / 정상 실내에서 temp1/temp2 확인 후 §5 표와 대조 / 예: TH1=25.5, TH2=25.0일 때 약 29.8°C
- [ ] **센티넬 왕복** / TH2 커넥터를 뽑고 광고 관찰 / `mfg_data[13..14]` = `00 80` (= -32768)
- [ ] **게이트웨이 무영향(회귀)** / 게이트웨이 켜고 서버 `temperature_log` 계속 쌓이는지 / temp1/temp2/rms 값이 변경 전과 동일하게 정상 수신
- [ ] **Android BLE 스캔 offset** / 앱에서 core_temp 표시 확인 / 온도가 RMS 값으로 읽히는 -2 시프트 실수 없는지 (QA 필수)
- [ ] **BLE 도달 거리 / TX 전류** / 2B 증가 후에도 기존과 동일한지 / 유의미한 차이 없어야 정상
- [ ] **SHF 정확도** (장기) / 실제 설비에 열전대 등 기준계 병설 / 벤치 MAE 0.508°C가 이 하드웨어에서도 유지되는지 — **현재 완전 미검증**
- [ ] **TH1/TH2 물리 배치 재확인** / 프로브 재설치 시마다 / TH1=열원 근접, TH2=실온 기준 (뒤바뀌면 값이 무의미)

---

## 9. 다른 에이전트에게 전달할 사항

- **server-engineer: 대응 필요 없음.** §4에서 게이트웨이가 `mfg_data[13..14]`를 읽지 않음을
  코드로 확인했다. MQTT 페이로드·DTO·DB 스키마 무변경.
- **android-engineer**:
  - 수신 배열 길이는 **13**, core_temp는 **offset 11..12** (int16 LE). §2 표 참조.
  - `-32768` 센티넬을 반드시 별도 처리 (0°C로 렌더링 금지).
  - **§5의 비단조성**을 알고 있을 것 — UI에 "추정(실측 아님)" 라벨을 반드시 유지하고,
    이 값 기반의 경고/색상 임계를 임의로 넣지 말 것.
- **integration-qa**: 교차 검증 포인트는 (1) ESP offset 13..14 ↔ Android offset 11..12의 -2 시프트,
  (2) 센티넬 왕복, (3) 페이로드 28B ≤ 31B, (4) 게이트웨이 `field_len >= 14` 회귀 무영향.
