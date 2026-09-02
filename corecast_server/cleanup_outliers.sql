-- temperature_log 온도 이상치 정리
-- 배경: 써미스터 개방/단락·전기 노이즈로 튄 단일 샘플이 100~120°C로 저장돼
--       차트 최댓값(MAX)을 왜곡. 실데이터는 <50°C.
-- 실행법: 반드시 1)로 먼저 확인 후, 2) 또는 3)의 주석을 풀어 실행.
--   mysql -u <user> -p <db> < cleanup_outliers.sql
-- ⚠️ 백업 권장: mysqldump 로 temperature_log 백업 후 진행.

-- 유효 범위(필요시 수정): -20 ~ 50 °C 밖은 이상치로 간주
SET @TMIN := -20;
SET @TMAX := 50;

-- 1) 먼저 확인 (삭제/수정 전 영향 범위)
SELECT
  COUNT(*)                               AS affected_rows,
  SUM(temp1 > @TMAX OR temp1 < @TMIN)    AS bad_temp1,
  SUM(temp2 > @TMAX OR temp2 < @TMIN)    AS bad_temp2,
  MAX(temp1)                             AS max_temp1,
  MAX(temp2)                             AS max_temp2
FROM temperature_log
WHERE temp1 > @TMAX OR temp1 < @TMIN
   OR temp2 > @TMAX OR temp2 < @TMIN;

-- 2) 방법 A (권장): 이상치 '온도만' NULL 처리 → 같은 행의 진동(rms) 데이터는 보존.
--    NULL은 차트 집계(AVG/MAX/MIN)에서 자동 제외됨.
UPDATE temperature_log SET temp1 = NULL WHERE temp1 > @TMAX OR temp1 < @TMIN;
UPDATE temperature_log SET temp2 = NULL WHERE temp2 > @TMAX OR temp2 < @TMIN;

-- 3) 방법 B: 이상치가 있는 '행 전체' 삭제 (rms·temp2 등 다른 값도 함께 삭제됨 — 주의)
-- DELETE FROM temperature_log
WHERE temp1 > @TMAX OR temp1 < @TMIN
   OR temp2 > @TMAX OR temp2 < @TMIN;

-- 4) 정리 후 재확인 (0 이어야 함)
SELECT COUNT(*) AS remaining_bad
FROM temperature_log
WHERE temp1 > @TMAX OR temp1 < @TMIN OR temp2 > @TMAX OR temp2 < @TMIN;
