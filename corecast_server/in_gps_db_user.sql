-- ============================================================
-- IN-GPS Web : 회원(로그인/회원가입) 테이블
-- 실행: mysql -u <user> -p ingps < in_gps_db_user.sql
-- ============================================================
-- 비밀번호는 평문 저장하지 않는다. PBKDF2-HMAC-SHA256 으로 해시한 결과(pw_hash)와
-- 사용자별 난수 salt(pw_salt), 반복 횟수(pw_iters)만 저장한다. 자세한 설계는
-- IN_GPS/Docs/Web/ 문서 참고.

CREATE TABLE IF NOT EXISTS web_user (
    user_id    BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(64)  NOT NULL,
    pw_hash    VARCHAR(128) NOT NULL,                 -- PBKDF2 결과 (hex, 64B → 128 hex chars)
    pw_salt    VARCHAR(64)  NOT NULL,                 -- per-user salt (hex, 16B → 32 hex chars)
    pw_iters   INT          NOT NULL DEFAULT 200000,  -- PBKDF2 반복 횟수
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login DATETIME     NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uq_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
