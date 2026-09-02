-- ============================================================
-- temperature_db_init.sql
-- IN-GPS 온도 로그 더미 데이터 초기화 스크립트
--
-- 실행 방법: mysql -u <user> -p ingps < temperature_db_init.sql
--
-- 포함 내용:
--   1. temperature_log 테이블 생성 (없으면)
--   2. temperature_log 전체 초기화 후 더미 데이터 삽입
--      - 디바이스 : esp_32
--      - 기간     : 현재 기준 최근 365일
--      - 간격     : 30분 (하루 48건, 총 17,520건)
-- ============================================================


-- ============================================================
-- 1. temperature_log 테이블
-- ============================================================
CREATE TABLE IF NOT EXISTS temperature_log (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    device_id  VARCHAR(64) NOT NULL,
    temp1      FLOAT       NOT NULL,
    temp2      FLOAT,
    angle_x    FLOAT,
    angle_y    FLOAT,
    angle_z    FLOAT,
    event      ENUM('normal', 'warning', 'disconnected') NOT NULL DEFAULT 'normal',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_device_created (device_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ============================================================
-- 2. 온도 로그 초기화
-- ============================================================
TRUNCATE TABLE temperature_log;


-- ============================================================
-- 3. 더미 데이터 삽입 저장 프로시저
--
-- 온도 패턴 설계:
--   일중 변화  : SIN((hr-6)*15°) → 정오(12시) 최고, 자정 최저
--   계절 변화  : SIN((mon-4)*30°) → 7월 최고, 1월 최저
--   주말 감소  : 평일 대비 temp1 -10°C (가동률 저하)
--   노이즈     : RAND() 기반 랜덤 편차
--   이벤트     : temp1 >= 70°C 시 'warning', 0.3% 확률 'disconnected'
-- ============================================================
DROP PROCEDURE IF EXISTS InsertDummyTempData;

DELIMITER $$

CREATE PROCEDURE InsertDummyTempData()
BEGIN
    -- 365일 * 48 (30분 간격) = 17,520
    DECLARE total   INT     DEFAULT 17520;
    DECLARE i       INT     DEFAULT 0;
    DECLARE base_dt DATETIME;
    DECLARE rec_dt  DATETIME;
    DECLARE hr      INT;
    DECLARE dow     INT;   -- 1=일요일, 7=토요일
    DECLARE mon     INT;
    DECLARE t1      FLOAT;
    DECLARE t2      FLOAT;
    DECLARE ax      FLOAT;
    DECLARE ay      FLOAT;
    DECLARE az      FLOAT;
    DECLARE ev      VARCHAR(20);

    SET base_dt = DATE_SUB(NOW(), INTERVAL 365 DAY);

    START TRANSACTION;

    WHILE i < total DO
        SET rec_dt = DATE_ADD(base_dt, INTERVAL (i * 30) MINUTE);
        SET hr     = HOUR(rec_dt);
        SET dow    = DAYOFWEEK(rec_dt);
        SET mon    = MONTH(rec_dt);

        SET t1 = 45
               + 18 * SIN(RADIANS((hr - 6) * 15))
               +  7 * SIN(RADIANS((mon - 4) * 30))
               + (RAND() * 8 - 4);
        IF dow IN (1, 7) THEN SET t1 = t1 - 10; END IF;

        SET t2 = 24
               +  7 * SIN(RADIANS((hr - 6) * 15))
               +  4 * SIN(RADIANS((mon - 4) * 30))
               + (RAND() * 4 - 2);
        IF dow IN (1, 7) THEN SET t2 = t2 - 4; END IF;

        SET ax = ROUND(RAND() * 2.0 - 1.0,  3);
        SET ay = ROUND(RAND() * 2.0 - 1.0,  3);
        SET az = ROUND(RAND() * 0.4 - 0.2,  3);

        IF    t1 >= 70        THEN SET ev = 'warning';
        ELSEIF RAND() < 0.003 THEN SET ev = 'disconnected';
        ELSE                       SET ev = 'normal';
        END IF;

        INSERT INTO temperature_log
            (device_id, temp1, temp2, angle_x, angle_y, angle_z, event, created_at)
        VALUES
            ('esp_32', ROUND(t1,2), ROUND(t2,2), ax, ay, az, ev, rec_dt);

        SET i = i + 1;
    END WHILE;

    COMMIT;
END$$

DELIMITER ;

-- 프로시저 실행 (완료 후 자동 삭제)
CALL InsertDummyTempData();
DROP PROCEDURE IF EXISTS InsertDummyTempData;
