-- ============================================================
-- IN-GPS Database Schema v4 (migration)
-- 변경사항:
--   1) temperature_log : angle_x/y/z → rms_x/y/z 컬럼 rename
--      (ADXL335 진동 RMS [mg] 저장으로 의미 변경, 단위 정수)
--   2) device 시드 데이터 추가 : esp_32_0 ~ esp_32_9 (10개)
--      ESP 펌웨어의 mfg_data 마지막 1바이트(0x01~0x0A)와
--      device_id 매핑: byte - 1 == suffix
-- ============================================================

USE ingps;

-- ============================================================
-- 1) temperature_log 컬럼 rename
--    기존 angle_x/y/z (FLOAT) → rms_x/y/z (SMALLINT UNSIGNED, mg 단위)
--    값 의미가 완전히 달라지므로 타입도 함께 변경.
-- ============================================================
ALTER TABLE temperature_log
  CHANGE COLUMN angle_x rms_x SMALLINT UNSIGNED NULL,
  CHANGE COLUMN angle_y rms_y SMALLINT UNSIGNED NULL,
  CHANGE COLUMN angle_z rms_z SMALLINT UNSIGNED NULL;


-- ============================================================
-- 2) line / equipment 더미 (FK 충족용)
-- ============================================================
INSERT INTO line (line_id, line_name) VALUES
  ('LN_01', 'A조립라인')
ON DUPLICATE KEY UPDATE line_name = VALUES(line_name);

INSERT INTO equipment (equipment_id, line_id, equipment_name) VALUES
  ('EQ_B01', 'LN_01', 'B설비')
ON DUPLICATE KEY UPDATE
  line_id        = VALUES(line_id),
  equipment_name = VALUES(equipment_name);


-- ============================================================
-- 3) device 시드 : esp_32_0 ~ esp_32_9
--    ESP mfg_data byte == device_id suffix + 1
--    (0x01 → esp_32_0, 0x02 → esp_32_1, ..., 0x0A → esp_32_9)
-- ============================================================
INSERT INTO device (device_id, equipment_id, status, installed_on, last_seen_at) VALUES
  ('esp_32_0', 'EQ_B01', 'Normal', CURDATE(), NULL),
  ('esp_32_1', 'EQ_B01', 'Normal', CURDATE(), NULL),
  ('esp_32_2', 'EQ_B01', 'Normal', CURDATE(), NULL),
  ('esp_32_3', 'EQ_B01', 'Normal', CURDATE(), NULL),
  ('esp_32_4', 'EQ_B01', 'Normal', CURDATE(), NULL),
  ('esp_32_5', 'EQ_B01', 'Normal', CURDATE(), NULL),
  ('esp_32_6', 'EQ_B01', 'Normal', CURDATE(), NULL),
  ('esp_32_7', 'EQ_B01', 'Normal', CURDATE(), NULL),
  ('esp_32_8', 'EQ_B01', 'Normal', CURDATE(), NULL),
  ('esp_32_9', 'EQ_B01', 'Normal', CURDATE(), NULL)
ON DUPLICATE KEY UPDATE
  equipment_id = VALUES(equipment_id),
  status       = VALUES(status);


-- ============================================================
-- v3 → v4 변경 요약
-- ============================================================
-- [컬럼 rename] temperature_log
--   angle_x → rms_x  (FLOAT      → SMALLINT UNSIGNED)
--   angle_y → rms_y
--   angle_z → rms_z
--   * 단위 : mg (milli-g)
--
-- [시드 데이터] device : esp_32_0 ~ esp_32_9 (10개)
--   * 매핑 : byte_id - 1 == suffix
-- ============================================================
