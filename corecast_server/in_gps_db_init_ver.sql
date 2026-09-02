CREATE DATABASE IF NOT EXISTS ingps
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE ingps;

-- 1) 라인 테이블
CREATE TABLE line (
  line_id    VARCHAR(32)  NOT NULL,   -- 예: LN_01
  line_name  VARCHAR(100) NOT NULL,   -- 예: A조립라인
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (line_id),
  UNIQUE KEY uk_line_name (line_name)
) ENGINE=InnoDB;

-- 2) 설비 테이블 (라인 1 : 설비 N)
CREATE TABLE equipment (
  equipment_id   VARCHAR(32)  NOT NULL,   -- 예: EQ_A01, EQ_B01
  line_id        VARCHAR(32)  NOT NULL,
  equipment_name VARCHAR(100) NULL,       -- 필요하면 사용
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (equipment_id),
  KEY idx_equipment_line (line_id),
  CONSTRAINT fk_equipment_line
    FOREIGN KEY (line_id) REFERENCES line(line_id)
    ON UPDATE CASCADE
    ON DELETE RESTRICT
) ENGINE=InnoDB;

-- 3) 디바이스(게이트웨이/센서노드) 테이블 (설비 1 : 디바이스 N)
CREATE TABLE device (
  device_id    VARCHAR(32) NOT NULL,       -- 예: DEV_2001 (MCU 고유 ID)
  equipment_id VARCHAR(32) NOT NULL,       -- "부착 설비 (FK)"
  status       ENUM('Normal','Warning1', 'Warning2', 'Warning3', 'Disconnected') NOT NULL DEFAULT 'Normal',
  installed_on DATE NULL,                 -- 설치일
  last_seen_at DATETIME NULL,             -- 수신 못하면 Disconnected 판단용
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (device_id),
  KEY idx_device_equipment (equipment_id),
  KEY idx_device_status (status),
  KEY idx_device_last_seen (last_seen_at),
  CONSTRAINT fk_device_equipment
    FOREIGN KEY (equipment_id) REFERENCES equipment(equipment_id)
    ON UPDATE CASCADE
    ON DELETE RESTRICT
) ENGINE=InnoDB;

-- 4) 디바이스 로그 테이블 (디바이스 1 : 로그 N)
CREATE TABLE device_log (
  log_id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, -- 로그ID(PK)
  device_id     VARCHAR(32) NOT NULL,                     -- 디바이스ID(FK)
  reboot_count  INT UNSIGNED NOT NULL DEFAULT 0,          -- 재부팅 횟수(해당 시점)
  temp_out_c    DECIMAL(5,2) NULL,                        -- 외부 온도(°C)
  temp_core_c   DECIMAL(5,2) NULL,                        -- 코어 온도(°C)
  fault_grade   TINYINT UNSIGNED NOT NULL DEFAULT 0,       -- 고장 등급(0~9 같은 규칙)
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (log_id),
  KEY idx_log_device_time (device_id, created_at),
  KEY idx_log_fault (fault_grade),
  CONSTRAINT fk_log_device
    FOREIGN KEY (device_id) REFERENCES device(device_id)
    ON UPDATE CASCADE
    ON DELETE CASCADE
) ENGINE=InnoDB;

-- GateWay 관리 테이블(현재 ppt에서는 특정 시간안에 Data수신이 안될 시 Disconnected를 보내기 때문에 일단 만들었지만 추가로 아키텍쳐 변경 여지 있음)
CREATE TABLE line_gateway (
  gateway_id   VARCHAR(32) NOT NULL,  
  line_id      VARCHAR(32) NOT NULL,
  status       ENUM('Normal','Warning1','Warning2','Warning3''Disconnected') NOT NULL DEFAULT 'Normal',
  installed_on DATE NULL,
  last_seen_at DATETIME NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (gateway_id),
  KEY idx_gw_line (line_id),
  KEY idx_gw_status (status),
  CONSTRAINT fk_gw_line
    FOREIGN KEY (line_id) REFERENCES line(line_id)
    ON UPDATE CASCADE
    ON DELETE RESTRICT
) ENGINE=InnoDB;

