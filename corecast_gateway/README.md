# Gateway Firmware, STM32WBA52 — rev 1.0 (BLE→MQTT Bridge)

공장 설비에 부착된 다수의 BLE 센서 노드(ESP32-S3, 온도 2채널 + 진동 3축)를 상시 스캔해 유효 데이터를 필터링하고, 유선 이더넷(W5500)을 통해 MQTT로 서버에 전달하는 게이트웨이 펌웨어입니다. 센서 → 게이트웨이 간 BLE 페이로드 계약은 디바이스 펌웨어(`INGPS_Device`)와 1:1로 일치해야 하며, 오프셋을 바꾸면 양쪽이 함께 깨집니다.

> BLE 스캔은 하드웨어 Filter Accept List(최대 15개 제한)를 사용하지 않고, MAC 화이트리스트 + Company ID 이중 검증을 소프트웨어로 처리합니다. 센서 수가 15대를 넘어가는 시나리오까지 고려한 설계 결정입니다(과거 하드웨어 필터 버전은 참고용으로 커밋 히스토리에 남아있음).

---

## Tech Stack

| 구분 | 내용 |
|---|---|
| MCU | STM32WBA52CG |
| Framework | STM32CubeIDE 2.2.0, STM32CubeMX 6.18.0, FW_WBA 1.10.0 |
| BLE | ST BLE 스택(STM32_WPAN), Active Scan, `HCI_SCAN_FILTER_NO` (SW 필터 사용) |
| 이더넷 | W5500 (SPI3), TCP/IP 스택 하드웨어 오프로드 |
| MQTT | Eclipse Paho MQTT C Client (Embedded) |
| 센서 관리 | 정적 화이트리스트 `myDevices[12]`, MAC `memcmp` 매칭 |
| 안정성 | 1초 헬스체크 → 5초 재연결 → IWDG 워치독(약 15초) 3단계 |
| 상태 표시 | APA102 LED (소프트웨어 SPI), 디바이스별 신호 유무 표시 |
| 저장소 | NVS 미사용 — 네트워크 정보는 `wizchip_port.c`에 정적 정의 |

---

## Architecture — Layer 구성

부팅 시 W5500 초기화 → MQTT 네트워크/브로커 연결 → BLE 스택 초기화(`MX_APPE_Init`) 순으로 진행됩니다. 이후 메인 루프가 무한 반복하며 BLE 이벤트로 채워진 큐를 소비합니다. BLE 신호 수신은 **인터럽트 컨텍스트**에서, 실제 처리(LED 갱신·MQTT publish)는 **메인 루프 컨텍스트**에서 이뤄지며, 이 둘은 `ble_queue_push()` / `handle_ble_messages()`로 연결됩니다. `HAL_IWDG_Refresh()`는 메인 루프 전 구간을 가로질러 매 루프마다 호출되며 전체 레이어의 생존 여부를 감시합니다.

---

## 레이어별 책임

| Layer | 파일 | 책임 |
|---|---|---|
| Boot | `main.c` (초기화부) | W5500 → MQTT → BLE 스택 순 초기화, 실패해도 부팅은 계속 |
| BLE 수신 | `app_ble.c` | Active Scan 시작, Advertising Report 파싱, MAC/Company ID 검증, 900ms 디바운싱 |
| Queue | `app_ble.c` → `main.c` | 인터럽트-메인루프 간 비동기 전달, 블로킹 방지 |
| 메인 제어 | `main.c` | 큐 소비, LED 갱신, 헬스체크·재연결 상태머신 |
| 네트워크 | `wizchip_port.c`, `w5500.c`, `socket.c` | SPI 기반 W5500 레지스터 제어, 소켓 관리 |
| MQTT | `MQTT_Example.c`, `mqtt_interface.c` | 브로커 연결, JSON publish, 타이머 기반 타임아웃 |
| Watchdog | `main.c` (IWDG 관련부) | 무응답 시 자동 재부팅 (Prescaler=256, Reload=1874) |
| Config | `app_conf.h` | BLE/저전력/로그 매크로, `USE_DHCP` / `USE_LOCAL_MQTT_BROKER` 스위치 |

---

## BLE Advertising Packet 파싱 (Manufacturer Specific Data, 13B)

디바이스 펌웨어와의 경계면 계약입니다. 오프셋을 바꾸면 디바이스 인코더와 게이트웨이 파서가 함께 깨집니다.

| Offset | 크기 | 내용 |
|---|---|---|
| 0~1 | 2B | Company ID `0x1234` (LE) — 1차 필터 기준 |
| 2~3 | 2B | temp1 × 100, int16 LE |
| 4~5 | 2B | temp2 × 100, int16 LE |
| 6~7 | 2B | rms_x (mg), uint16 LE |
| 8~9 | 2B | rms_y (mg), uint16 LE |
| 10~11 | 2B | rms_z (mg), uint16 LE |
| 12 | 1B | Device ID (`ESP_DEVICE_ID`) — MAC 화이트리스트 매칭에 사용 |

파싱 조건: `field_type == 0xFF && field_len >= 14`(길이 필드 포함 14B) 인 경우에만 유효 데이터로 간주합니다.

---

## MQTT Publish Payload (JSON, Topic: `ingps/sensor`)

| 필드 | 소스 | 비고 |
|---|---|---|
| `gateway_id` | 고정 문자열 (`GW_LN01`) | 멀티 게이트웨이 확장 시 구분자 |
| `esp_byte_id` | 파싱된 Device ID (offset 12) | 서버 `esp_byte_to_device_id()`가 0~9만 지원 — 10/11 미매핑 이슈 존재 |
| `temp1`, `temp2` | 파싱된 온도 값 / 100 | float 변환 |
| `rms_x`, `rms_y`, `rms_z` | 파싱된 진동 값 | mg 단위 그대로 전달 |

```json
{
  "gateway_id": "GW_LN01",
  "esp_byte_id": 3,
  "temp1": 25.30,
  "temp2": 26.10,
  "rms_x": 18,
  "rms_y": 15,
  "rms_z": 20
}
```

---

## BLE 스캔 파라미터

| 항목 | 값 |
|---|---|
| Scan Type | Active |
| Scan Interval | 500ms |
| Scan Window | 500ms (Interval과 동일 → 100% Duty Cycle) |
| Filter Policy | `HCI_SCAN_FILTER_NO` (SW 필터로 대체) |
| 디바운스 | 900ms 이내 재수신 시 중복으로 간주해 무시 |

---

## 안정성 / 자가복구

| 계층 | 감시 대상 | 대응 |
|---|---|---|
| L1 헬스체크 | PHY 링크 상태, MQTT 연결 상태 | 1초 주기 점검, `gw_state` 갱신(`INIT`/`CONNECTED`/`DISCONNECTED`) |
| L2 재연결 | 연결 끊김 지속 여부 | 5초 주기로 재연결 시도, 블로킹 없이 반복 |
| L3 워치독(IWDG) | 메인 루프 생존 신호 | 약 15초 무응답 시 하드웨어 자동 재부팅 |
| L4 타이머 | SysTick 기반 millis 카운터 | `MilliTimer_Handler()` 누락 시 MQTT 타임아웃 판정 자체가 무력화(과거 실제 발생 이슈) |

---

## Hardware (SPI3 핀맵)

핀 매핑의 단일 출처는 `Core/Inc/main.h`입니다 — 다른 곳에 핀 번호를 직접 적지 마세요.

| 신호 | GPIO | 용도 |
|---|---|---|
| SCLK | PB3 | W5500 SPI 클럭 |
| MISO | PB2 | W5500 → MCU 데이터 |
| MOSI | PA15 | MCU → W5500 데이터 |
| RSTn | PA6 | W5500 하드웨어 리셋 |
| SCSn | PA3 | W5500 Chip Select |
| SCK | PB14 | APA102 LED 소프트웨어 SPI 클럭 |
| DATA | PB15 | APA102 LED 소프트웨어 SPI 데이터 |

---

## Requirements

- STM32CubeIDE 2.2.0 이상
- STM32CubeMX 6.18.0, FW_WBA 1.10.0 Firmware Package
- ST-LINK (플래시/디버깅)
- W5500 이더넷 모듈, RJ45 유선 연결 환경

## Build & Flash

1. STM32CubeIDE에서 `Import > Existing Projects into Workspace`로 본 저장소 임포트
2. `Core/Inc/app_conf.h`에서 네트워크 모드 확인

```c
#define USE_DHCP              0   // 0: 고정 IP, 1: DHCP
#define USE_LOCAL_MQTT_BROKER 1   // 0: EC2 직접 연결, 1: 로컬 브로커 경유
```

3. `Drivers/Ethernet_W550/wizchip_port.c`에서 고정 IP 사용 시 `netInfo` 값 확인
4. `Project > Build All`
5. ST-LINK 연결 후 Run/Debug로 플래시

디버그 로그는 UART 미탑재 환경이라 STM32CubeIDE Live Expressions 또는 `printf()` 직접 로깅으로 확인합니다.

---

## Device 연동

- 게이트웨이는 정적 화이트리스트(`myDevices[12]`)에 등록된 MAC + Company ID `0x1234`인 패킷만 유효 데이터로 처리합니다.
- 화이트리스트에 없는 신규 기기는 서버의 `pending_device` 승인 플로우를 통해 등록 후 반영합니다(자동 학습 아님).

---

## Branch 안내

- `master` : 최신 안정 버전 (고정 IP 운용 중)
- DHCP 전환은 `app_conf.h`의 `USE_DHCP` 매크로로 전환 가능, 별도 브랜치 분리하지 않음
