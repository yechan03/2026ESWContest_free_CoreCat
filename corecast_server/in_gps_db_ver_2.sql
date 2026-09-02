-- ============================================================
-- IN-GPS Database Schema v2
-- 변경사항: ESP32 raw 센서값 수신 + 서버 ML 판단 구조로 개편
-- ============================================================

CREATE DATABASE IF NOT EXISTS ingps
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE ingps;

-- ============================================================
-- 1) 라인 테이블 (변경 없음)
-- ============================================================
CREATE TABLE line (
  line_id    VARCHAR(32)  NOT NULL,
  line_name  VARCHAR(100) NOT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (line_id),
  UNIQUE KEY uk_line_name (line_name)
) ENGINE=InnoDB;

-- ============================================================
-- 2) 설비 테이블 (변경 없음)
-- ============================================================
CREATE TABLE equipment (
  equipment_id   VARCHAR(32)  NOT NULL,
  line_id        VARCHAR(32)  NOT NULL,
  equipment_name VARCHAR(100) NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (equipment_id),
  KEY idx_equipment_line (line_id),
  CONSTRAINT fk_equipment_line
    FOREIGN KEY (line_id) REFERENCES line(line_id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

-- ============================================================
-- 3) 디바이스 테이블
-- [변경] status ENUM에서 Warning 판단 주체를 서버로 이전
--        → status는 서버 ML 결과를 반영한 최신 상태값
-- ============================================================
CREATE TABLE device (
  device_id    VARCHAR(32) NOT NULL,
  equipment_id VARCHAR(32) NOT NULL,
  status       ENUM('Normal','Warning1','Warning2','Warning3','Disconnected')
               NOT NULL DEFAULT 'Normal',  -- [서버 ML이 업데이트]
  installed_on DATE NULL,
  last_seen_at DATETIME NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (device_id),
  KEY idx_device_equipment (equipment_id),
  KEY idx_device_status (status),
  KEY idx_device_last_seen (last_seen_at),
  CONSTRAINT fk_device_equipment
    FOREIGN KEY (equipment_id) REFERENCES equipment(equipment_id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

-- ============================================================
-- 4) 디바이스 로그 테이블
-- [v1 → v2 변경 내용]
--
--  [추가] ESP32 raw 센서값 컬럼
--    accel_x, accel_y, accel_z : 가속도 3축 (단위: g 또는 m/s²)
--    voltage_v                 : 전압 raw값 (단위: V)
--    temp_surface_c            : 써미스터 표면 온도 (°C) ← 기존 temp_out_c 역할 명확화
--
--  [변경] temp_out_c → temp_surface_c 로 rename (역할 명확화)
--
--  [추가] 서버 ML 결과 컬럼
--    temp_core_c               : ML이 예측한 Core 온도 (°C) ← 기존 유지
--    fault_grade               : ML이 계산한 고장 등급 (0~9) ← 기존 유지
--    warning_level             : ML이 결정한 Warning 레벨 ← [신규]
--    ml_processed              : ML 처리 완료 여부 플래그 ← [신규]
--
--  [추가] 데이터 출처 추적
--    reboot_count              : ESP32 재부팅 횟수 (기존 유지)
-- ============================================================
CREATE TABLE device_log (
  -- PK / FK
  log_id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
  device_id         VARCHAR(32)      NOT NULL,

  -- [ESP32 raw 센서값] Gateway가 POST할 때 채워지는 값
  reboot_count      INT UNSIGNED     NOT NULL DEFAULT 0,         -- ESP32 재부팅 횟수
  temp_surface_c    DECIMAL(5,2)     NULL,                       -- 써미스터 표면온도 (°C)
  accel_x           DECIMAL(8,4)     NULL,                       -- 가속도 X축
  accel_y           DECIMAL(8,4)     NULL,                       -- 가속도 Y축
  accel_z           DECIMAL(8,4)     NULL,                       -- 가속도 Z축
  voltage_v         DECIMAL(6,3)     NULL,                       -- 전압 (V)

  -- [서버 ML 결과값] EC2 ML 서비스가 채워넣는 값
  temp_core_c       DECIMAL(5,2)     NULL,                       -- ML 예측 Core 온도 (°C)
  fault_grade       TINYINT UNSIGNED NOT NULL DEFAULT 0,          -- 고장 등급 (0: 정상)
  warning_level     ENUM('Normal','Warning1','Warning2','Warning3')
                    NOT NULL DEFAULT 'Normal',                    -- ML 판단 Warning 레벨
  ml_processed      TINYINT(1)       NOT NULL DEFAULT 0,          -- 0: ML 미처리, 1: 처리완료

  created_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (log_id),
  KEY idx_log_device_time  (device_id, created_at),
  KEY idx_log_fault        (fault_grade),
  KEY idx_log_warning      (warning_level),       -- [신규] Warning 레벨 조회용
  KEY idx_log_ml_processed (ml_processed),         -- [신규] ML 미처리 배치 조회용
  CONSTRAINT fk_log_device
    FOREIGN KEY (device_id) REFERENCES device(device_id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB;

-- ============================================================
-- 5) Gateway 관리 테이블
-- [v1 → v2 변경] ENUM 누락 콤마 버그 수정
-- ============================================================
CREATE TABLE line_gateway (
  gateway_id   VARCHAR(32) NOT NULL,
  line_id      VARCHAR(32) NOT NULL,
  status       ENUM('Normal','Warning1','Warning2','Warning3','Disconnected')
               NOT NULL DEFAULT 'Normal',   -- [버그수정] 'Warning3''Disconnected' → 콤마 추가
  installed_on DATE NULL,
  last_seen_at DATETIME NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (gateway_id),
  KEY idx_gw_line   (line_id),
  KEY idx_gw_status (status),
  CONSTRAINT fk_gw_line
    FOREIGN KEY (line_id) REFERENCES line(line_id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

-- ============================================================
-- 6) [신규] ML 모델 메타 테이블
-- 서버에서 어떤 모델 버전으로 예측했는지 추적용
-- ============================================================
CREATE TABLE ml_model (
  model_id      VARCHAR(32)   NOT NULL,           -- 예: MODEL_20250101_v1
  model_type    VARCHAR(50)   NOT NULL,            -- 예: RandomForest, LightGBM
  model_version VARCHAR(20)   NOT NULL,            -- 예: 1.0.0
  description   VARCHAR(255)  NULL,               -- 모델 설명
  is_active     TINYINT(1)    NOT NULL DEFAULT 0,  -- 현재 사용 중인 모델 여부
  trained_at    DATETIME      NULL,               -- 학습 시각
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (model_id),
  KEY idx_model_active (is_active)
) ENGINE=InnoDB;

-- ============================================================
-- v1 → v2 변경 요약
-- ============================================================
-- [추가 컬럼] device_log
--   + accel_x, accel_y, accel_z  : 가속도 raw값
--   + voltage_v                  : 전압 raw값
--   + temp_surface_c             : 써미스터 표면온도 (temp_out_c rename)
--   + warning_level              : 서버 ML 판단 Warning 레벨
--   + ml_processed               : ML 처리 완료 플래그
--
-- [추가 인덱스] device_log
--   + idx_log_warning            : Warning 레벨 빠른 조회
--   + idx_log_ml_processed       : ML 미처리 데이터 배치 조회
--
-- [신규 테이블] ml_model
--   : 어떤 모델 버전으로 예측했는지 추적
-- ============================================================