# 2026ESWContest자유공모부분CoreCat팀

산업 설비의 온도·진동을 상시 감시해 화재를 예방하는 시스템입니다. 센서 노드가 온도/진동을
측정해 BLE로 광고하면 게이트웨이가 이를 수집해 서버로 전달하고, 서버는 데이터를 적재해
모바일 앱과 웹 대시보드에 제공합니다.

## 기능

- [센서 노드 펌웨어 (ESP32-S3)](corecast_project/README.md) — 온도(AS6221)·진동(ADXL345) 측정, BLE 광고
- [게이트웨이 (STM32WBA52)](corecast_gateway/README.md) — BLE 스캔 및 MQTT 발행
- [서버 (FastAPI · AWS EC2)](corecast_server/README.md) — 데이터 수집·적재, REST API 제공
- [모바일 앱 (Android)](CoreCast_App/README.md) — 실시간·기간별 센서 데이터 시각화
- [ML 모델 검증](ml_validation/README.md) — 센서 데이터 기반 모델 학습·검증, 펌웨어 반영용 export

## Project Structure

```
CoreCast_team/
├── corecast_project/   # 센서 노드 펌웨어 (ESP-IDF, ESP32-S3)
├── corecast_gateway/   # 게이트웨이 펌웨어 (STM32Cube, STM32WBA52)
├── corecast_server/    # 백엔드 서버 (FastAPI, MySQL)
├── CoreCast_App/       # 모바일 앱 (Android)
└── ml_validation/      # ML 모델 학습·검증 환경
```

각 컴포넌트의 상세 내용은 위 하이퍼링크로 연결된 README.md를 참고하세요.
