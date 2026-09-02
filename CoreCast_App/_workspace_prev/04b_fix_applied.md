# QA 실패 항목 수정 (orchestrator 직접 적용)

`04_qa_report.md`의 실패 1건("ESP 무효 센티넬 -32768을 아무도 처리하지 않음")을 다음과 같이 수정했다.

## 1. `C:\in_gps_server\mqtt_subscriber.py`
- `_cast_float()`에 `f < -200.0 → None` 가드 추가 (상한은 걸지 않음 — SHF 이론 출력 범위 -141.5~138.1°C 기준으로 여유).
- `core_temp` 처리부에 무효/캐스팅 실패 시 `logger.warning` 추가 (기존 temp1/temp2와 동일한 관례).
- `python -m py_compile mqtt_subscriber.py` 통과 확인.

## 2. `C:\esp\in_gps_project\main\ble\ble_adv.c`
- 상단 mfg_data 레이아웃 주석이 되돌려진 구 아키텍처("소비자는 Android BLE 직접 스캔뿐", "게이트웨이/서버/DB는 손댈 필요 없다")를 그대로 담고 있어 게이트웨이 담당자가 offset [13..14] 파싱 추가를 불필요하다고 오판할 위험이 있었다.
- 정정: 게이트웨이가 offset [13..14]를 읽어 MQTT JSON `"core_temp"`(÷100 실수 변환) 키로 publish해야 함을 명시. 서버/앱은 이미 수신 준비 완료, 남은 건 게이트웨이 파싱뿐임을 명시. 센티넬 필터링이 서버 쪽에서 처리됨을 명시.

## 남은 미검증
- 실제 게이트웨이 구현(다른 담당자)이 이 -200°C 필터를 신뢰하고 별도 처리 없이 그대로 실수 변환해 보내는지는 게이트웨이 코드 완성 후 확인 필요.
- EC2 DDL 미적용, 서버 미배포 상태이므로 이 수정 자체도 실 서버 환경에서는 아직 미검증.
