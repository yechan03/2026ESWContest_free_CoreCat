-- ============================================================
-- IN-GPS Database Schema v6 (migration)
-- 변경사항:
--   1) temperature_log : core_temp FLOAT NULL 컬럼 추가
--      ESP32가 SHF(Surface Heat Flux) 모델로 계산한 '설비 내부 추정온도'.
--      게이트웨이가 mfg_data를 파싱해 ingps/sensor 토픽으로 publish하는
--      JSON의 core_temp 키를 그대로 적재한다.
--      페이로드 예:
--        {
--          "gateway_id": "GW_LN01",
--          "esp_byte_id": 0,
--          "temp1": 25.34,
--          "temp2": 25.10,
--          "core_temp": 41.20,
--          "rms_x": 12, "rms_y": 8, "rms_z": 5
--        }
--
--   * NULL 허용 필수 : 기존 행 전체와, core_temp를 아직 보내지 않는
--     구버전 ESP/게이트웨이·레거시 ingps/mvpmodel 토픽의 행은 NULL로 남는다.
--     앱은 이 NULL을 "측정 불가"로 표시한다(0.0으로 렌더링하면 안 됨).
--
--   * temp1/temp2와 달리 -20~80°C 유효범위 필터(mqtt_subscriber._valid_temp)를
--     적용하지 않는다. core_temp는 과열 설비의 내부 추정치라 80°C 초과가
--     정상 동작 범위이며, 필터를 걸면 정작 필요한 과열 구간이 NULL이 된다.
--     저장 단계 검증은 float 캐스팅 가능 여부뿐(_cast_float).
-- ============================================================

USE ingps;

-- ============================================================
-- 1) temperature_log 컬럼 추가
--    temp2 뒤에 배치해 온도 컬럼끼리 인접시킴(조회 가독성 목적, 기능 영향 없음).
-- ============================================================
ALTER TABLE temperature_log
  ADD COLUMN core_temp FLOAT NULL AFTER temp2;


-- ============================================================
-- 적용 확인용 (선택)
-- ============================================================
-- SHOW COLUMNS FROM temperature_log LIKE 'core_temp';
-- SELECT id, device_id, temp1, temp2, core_temp, created_at
--   FROM temperature_log ORDER BY created_at DESC LIMIT 5;


-- ============================================================
-- v5 → v6 변경 요약
-- ============================================================
-- [컬럼 추가] temperature_log.core_temp  FLOAT NULL
--   * 소스 토픽 : ingps/sensor  (payload key: "core_temp")
--   * 적재 경로 : mqtt_subscriber.handle_sensor() → INSERT
--   * 조회 API  : GET /sensor, GET /temperature (items[].core_temp)
--                 GET /temperature/chart        (items[].core_temp = AVG)
--                 GET /chart/{device_id}        (series.core_temp = AVG)
--   * 앱 소비처 : SensorDetailFragment "AI 예측 온도" 카드 (tv_ai_temp)
-- ============================================================
