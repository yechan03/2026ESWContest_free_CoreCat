# auth.py
"""
IN-GPS Web 인증 유틸 (외부 의존성 없이 Python 표준 라이브러리만 사용).

비밀번호 저장 : PBKDF2-HMAC-SHA256 (사용자별 난수 salt + 반복 200,000회)
세션 관리     : HMAC-SHA256 으로 서명한 토큰을 HttpOnly 쿠키에 저장 (서버 무상태)

설계 의도는 IN_GPS/Docs/Web/ 문서에 정리되어 있다.
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import time

# ── 설정 ────────────────────────────────────────────────────────────
PBKDF2_ITERS = 200_000          # PBKDF2 반복 횟수 (느릴수록 무차별 대입에 강함)
PBKDF2_DKLEN = 64               # 파생 키 길이(byte) → hex 128자
SALT_BYTES   = 16               # per-user salt 길이(byte)
SESSION_TTL  = 60 * 60 * 8      # 세션 유효시간(초) = 8시간
COOKIE_NAME  = "ingps_session"

# 토큰 서명 키. 운영에선 반드시 환경변수 WEB_SECRET 로 고정값을 주입할 것.
# (미설정 시 프로세스마다 랜덤 → 재시작하면 모든 세션 무효화됨)
_SECRET = os.getenv("WEB_SECRET", secrets.token_hex(32)).encode()


# ── 비밀번호 해시 ───────────────────────────────────────────────────
def hash_password(password: str, *, iters: int = PBKDF2_ITERS):
    """평문 비밀번호 → (pw_hash_hex, salt_hex, iters). 저장용."""
    salt = secrets.token_bytes(SALT_BYTES)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iters, dklen=PBKDF2_DKLEN)
    return dk.hex(), salt.hex(), iters


def verify_password(password: str, pw_hash_hex: str, salt_hex: str, iters: int) -> bool:
    """입력 비밀번호가 저장된 해시와 일치하는지 (타이밍 공격 방지 비교)."""
    salt = bytes.fromhex(salt_hex)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iters, dklen=PBKDF2_DKLEN)
    return hmac.compare_digest(dk.hex(), pw_hash_hex)


# ── 세션 토큰 (HMAC 서명) ───────────────────────────────────────────
def _b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def make_session(username: str, *, ttl: int = SESSION_TTL) -> str:
    """username을 담은 서명 토큰 생성. 형식: base64(payload).base64(hmac)."""
    payload = {"u": username, "exp": int(time.time()) + ttl}
    body = _b64e(json.dumps(payload, separators=(",", ":")).encode())
    sig = hmac.new(_SECRET, body.encode(), hashlib.sha256).digest()
    return f"{body}.{_b64e(sig)}"


def read_session(token: str):
    """유효한 토큰이면 username 반환, 아니면 None (서명·만료 검증)."""
    if not token or "." not in token:
        return None
    body, sig = token.rsplit(".", 1)
    expected = hmac.new(_SECRET, body.encode(), hashlib.sha256).digest()
    try:
        if not hmac.compare_digest(_b64d(sig), expected):
            return None
        payload = json.loads(_b64d(body))
    except Exception:
        return None
    if payload.get("exp", 0) < int(time.time()):
        return None
    return payload.get("u")
