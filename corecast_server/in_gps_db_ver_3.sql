-- ============================================================
-- IN-GPS Database Schema v3
-- 변경사항: 이벤트 발생 기록 테이블 추가 (화재 예방 모니터링)
-- ============================================================

USE ingps;

-- ============================================================
-- 7) 이벤트 로그 테이블 [신규]
-- 온도 이벤트(warning/disconnected) 발생 시점 기록
-- 전후 window_hours 시간 데이터를 temperature_log에서 조회하여 사용
-- ============================================================
CREATE TABLE IF NOT EXISTS event_log (
    event_id     BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    device_id    VARCHAR(32)      NOT NULL,
    event_type   ENUM('warning','disconnected') NOT NULL,  -- 이벤트 종류
    event_at     DATETIME         NOT NULL,                -- 이벤트 최초 발생 시각
    window_hours TINYINT          NOT NULL DEFAULT 6,      -- 조회할 전후 시간 범위
    resolved_at  DATETIME         NULL,                    -- normal로 복귀한 시각 (NULL = 미해제)
    created_at   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (event_id),
    INDEX idx_event_device_at (device_id, event_at),       -- 디바이스별 시간순 조회
    INDEX idx_event_unresolved (device_id, resolved_at),   -- 미해제 이벤트 빠른 조회

    CONSTRAINT fk_event_device
        FOREIGN KEY (device_id) REFERENCES device(device_id)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- v2 → v3 변경 요약
-- ============================================================
-- [신규 테이블] event_log
--   : 온도 이벤트 발생/해제 시각 기록
--   : temperature_log 직접 참조 방식 (스냅샷 없음, 중복 저장 없음)
--   : window_hours 기준으로 전후 데이터를 API에서 범위 조회
-- ============================================================
