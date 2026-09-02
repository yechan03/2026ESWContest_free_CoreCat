-- ============================================================
-- IN-GPS Database Schema v5 (migration)
-- 변경사항:
--   1) gateway_health_log 테이블 신설
--      STM32 게이트웨이가 재시작 직후 보내는 crash_report(직전 실행 요약)를 적재.
--      토픽: ingps/gateway_health
--      페이로드 예:
--        {
--          "gateway_id": "GW_LN01",
--          "event": "crash_report",
--          "prev_init_result": 0,
--          "prev_mqtt_conn_result": 0,
--          "prev_pub_count": 1234,
--          "prev_fail_count": 3,
--          "prev_uptime_sec": 86400
--        }
--   * FK(line_gateway) 미연결: 미등록 게이트웨이의 crash_report도 유실 없이 저장하기 위함.
--     (line_gateway.last_seen 갱신은 기존 hello 경로에서 계속 처리)
-- ============================================================

USE ingps;

CREATE TABLE IF NOT EXISTS gateway_health_log (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,  -- 로그ID(PK)
  gateway_id            VARCHAR(32)  NOT NULL,                    -- 예: GW_LN01
  event                 VARCHAR(32)  NOT NULL DEFAULT 'crash_report',
  prev_init_result      BIGINT UNSIGNED NULL,                     -- 직전 실행 초기화 결과 코드
  prev_mqtt_conn_result BIGINT UNSIGNED NULL,                     -- 직전 실행 MQTT 연결 결과 코드
  prev_pub_count        BIGINT UNSIGNED NULL,                     -- 직전 실행 publish 성공 누적
  prev_fail_count       BIGINT UNSIGNED NULL,                     -- 직전 실행 publish 실패 누적
  prev_uptime_sec       BIGINT UNSIGNED NULL,                     -- 직전 실행 가동 시간(초)
  created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_gwh_gw_time (gateway_id, created_at)
) ENGINE=InnoDB;

-- ============================================================
-- v4 → v5 변경 요약
-- ============================================================
-- [신규 테이블] gateway_health_log
--   * 소스 토픽 : ingps/gateway_health
--   * 조회 API  : GET /gateway_health?gateway_id=&limit=
-- ============================================================
