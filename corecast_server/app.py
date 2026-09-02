import csv
import io
import os

from fastapi import FastAPI, Depends, HTTPException, Query, Request, Response, Cookie
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import StreamingResponse, FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from db import get_db
from typing import Optional

import auth

app = FastAPI(title="IN-GPS API", version="0.3.0")
# JSON 응답 gzip 압축 (모바일 차트 페이로드 70~80% 절감; OkHttp가 자동 협상)
app.add_middleware(GZipMiddleware, minimum_size=1024)

WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")


class DummyLogIn(BaseModel):
    device_id: str = Field(..., examples=["DEV_2001"])
    reboot_count: int = 0
    temp_out_c: Optional[float] = 25.0
    temp_core_c: Optional[float] = 60.0
    fault_grade: int = 0


class SensorLogIn(BaseModel):
    """ESP RMS + 온도 페이로드. mqtt_subscriber와 동일 스키마."""
    device_id: str = Field(..., examples=["esp_32_0"])
    temp1: Optional[float] = None
    temp2: Optional[float] = None
    # core_temp: ESP가 SHF 모델로 계산한 내부 추정온도.
    # temp1/temp2와 달리 상한을 두지 않는다 — 과열 설비 내부 추정치라
    # 표면센서 유효범위(-20~80°C)를 정상적으로 넘길 수 있다.
    # 하한 -200.0은 mqtt_subscriber.py의 _cast_float()과 동일 — ESP 무효
    # 센티넬 AS6221_TEMP_INVALID_X100(÷100=-327.68°C)이 REST 경로로도
    # 들어올 수 있어 여기서도 걸러야 한다.
    core_temp: Optional[float] = Field(None, ge=-200.0)
    rms_x: Optional[int]   = Field(None, ge=0, le=65535)
    rms_y: Optional[int]   = Field(None, ge=0, le=65535)
    rms_z: Optional[int]   = Field(None, ge=0, le=65535)
    event: str = Field("normal", pattern="^(normal|warning|disconnected)$")


class SensorLogUpdate(BaseModel):
    temp1: Optional[float] = None
    temp2: Optional[float] = None
    core_temp: Optional[float] = Field(None, ge=-200.0)
    rms_x: Optional[int]   = Field(None, ge=0, le=65535)
    rms_y: Optional[int]   = Field(None, ge=0, le=65535)
    rms_z: Optional[int]   = Field(None, ge=0, le=65535)
    event: str = Field(..., pattern="^(normal|warning|disconnected)$")


class SignupIn(BaseModel):
    username: str = Field(..., min_length=3, max_length=64)
    password: str = Field(..., min_length=8, max_length=128)


class LoginIn(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    password: str = Field(..., min_length=1, max_length=128)


def current_user(ingps_session: Optional[str] = Cookie(default=None)) -> str:
    """세션 쿠키를 검증해 username을 반환. 없거나 무효면 401."""
    username = auth.read_session(ingps_session) if ingps_session else None
    if not username:
        raise HTTPException(status_code=401, detail="Not authenticated")
    return username


@app.post("/auth/signup", status_code=201)
def signup(payload: SignupIn, db: Session = Depends(get_db)):
    exists = db.execute(
        text("SELECT user_id FROM web_user WHERE username = :u"),
        {"u": payload.username},
    ).first()
    if exists:
        raise HTTPException(status_code=409, detail="이미 사용 중인 아이디입니다.")
    pw_hash, salt, iters = auth.hash_password(payload.password)
    db.execute(text("""
        INSERT INTO web_user (username, pw_hash, pw_salt, pw_iters)
        VALUES (:u, :h, :s, :i)
    """), {"u": payload.username, "h": pw_hash, "s": salt, "i": iters})
    db.commit()
    return {"ok": True, "username": payload.username}


@app.post("/auth/login")
def login(payload: LoginIn, response: Response, db: Session = Depends(get_db)):
    row = db.execute(text("""
        SELECT pw_hash, pw_salt, pw_iters FROM web_user WHERE username = :u
    """), {"u": payload.username}).mappings().first()
    # 사용자 유무와 무관하게 동일 메시지 → 계정 존재 여부 노출 방지
    if not row or not auth.verify_password(
        payload.password, row["pw_hash"], row["pw_salt"], row["pw_iters"]
    ):
        raise HTTPException(status_code=401, detail="아이디 또는 비밀번호가 올바르지 않습니다.")
    db.execute(text("UPDATE web_user SET last_login = NOW() WHERE username = :u"),
               {"u": payload.username})
    db.commit()
    token = auth.make_session(payload.username)
    response.set_cookie(
        key=auth.COOKIE_NAME, value=token,
        max_age=auth.SESSION_TTL, httponly=True, samesite="lax",
        # 운영(HTTPS)에선 secure=True 권장. 로컬 http 테스트 위해 기본 False.
        secure=False,
    )
    return {"ok": True, "username": payload.username}


@app.post("/auth/logout")
def logout(response: Response):
    response.delete_cookie(auth.COOKIE_NAME)
    return {"ok": True}


@app.get("/auth/me")
def me(username: str = Depends(current_user)):
    return {"username": username}


@app.get("/health")
def health(db: Session = Depends(get_db)):
    try:
        db.execute(text("SELECT 1"))
        return {"ok": True, "db": "ok"}
    except Exception as e:
        return {"ok": False, "db": "fail", "error": str(e)}


@app.get("/lines")
def list_lines(db: Session = Depends(get_db)):
    rows = db.execute(text("""
        SELECT line_id, line_name, created_at, updated_at
        FROM line
        ORDER BY line_id
    """)).mappings().all()
    return {"items": list(rows)}


@app.get("/equipments")
def list_equipments(line_id: Optional[str] = None, db: Session = Depends(get_db)):
    if line_id:
        rows = db.execute(text("""
            SELECT equipment_id, line_id, equipment_name, created_at, updated_at
            FROM equipment
            WHERE line_id = :line_id
            ORDER BY equipment_id
        """), {"line_id": line_id}).mappings().all()
    else:
        rows = db.execute(text("""
            SELECT equipment_id, line_id, equipment_name, created_at, updated_at
            FROM equipment
            ORDER BY equipment_id
        """)).mappings().all()
    return {"items": list(rows)}


# /devices가 실어 보내는 "최신 온도"의 신선도 상한(분).
# 이보다 오래된 temperature_log 행은 후보에서 제외되어 latest_* 필드가 NULL이 된다.
# 의미론 근거: 몇 시간 전 온도로 지금 알림을 띄우면 오경보다. status가 이미
#   15초 룰로 신선도를 판정하는 것과 같은 사상이다.
# 성능 근거: 상한이 없으면 디바이스당 수만 행이 서브쿼리 후보가 된다.
# int 상수라 SQL 문자열에 직접 삽입해도 인젝션 위험이 없다(INTERVAL은 바인드 불가한 문법 위치).
LATEST_TEMP_MAX_AGE_MINUTES = 10


@app.get("/devices")
def list_devices(equipment_id: Optional[str] = None, db: Session = Depends(get_db)):
    # last_seen_at이 15초보다 오래됐거나 NULL이면 Disconnected로 표시한다.
    #
    # latest_temp1 / latest_core_temp / latest_temp_at:
    #   앱이 어느 화면에 있든 3초 폴링만으로 전 디바이스 온도를 감시할 수 있도록
    #   디바이스별 "최신 1행"을 같은 응답에 실어 보낸다(별도 엔드포인트 신설 대신).
    #   - 상관 서브쿼리로 디바이스당 1회 인덱스 seek → 왕복은 여전히 1회(N+1 아님).
    #   - WHERE device_id = ... ORDER BY created_at DESC 형태라
    #     idx_device_created(device_id, created_at)를 그대로 탄다.
    #     GROUP BY + MAX(id) 방식은 인덱스 2번째 컬럼이 created_at이라 피한다.
    #   - created_at이 DATETIME(초 해상도)이라 같은 초에 2행이 들어올 수 있다.
    #     ORDER BY created_at DESC, id DESC + LIMIT 1로 디바이스당 정확히 1행을 고정한다.
    #     (이게 깨지면 LEFT JOIN이 행을 불려 응답 item이 중복된다.)
    #   - 매칭 행이 없으면 LEFT JOIN이 세 필드를 모두 NULL로 남긴다.
    #   ※ temperature_log.core_temp 컬럼(in_gps_db_ver_6.sql)에 의존한다.
    base_sql = f"""
        SELECT d.device_id, d.equipment_id,
               CASE WHEN d.last_seen_at > NOW() - INTERVAL 15 SECOND THEN d.status
                    ELSE 'Disconnected' END AS status,
               d.installed_on, d.last_seen_at, d.created_at, d.updated_at,
               lt.temp1      AS latest_temp1,
               lt.core_temp  AS latest_core_temp,
               lt.created_at AS latest_temp_at
        FROM device d
        LEFT JOIN temperature_log lt
               ON lt.id = (SELECT t.id
                             FROM temperature_log t
                            WHERE t.device_id = d.device_id
                              AND t.created_at >= NOW() - INTERVAL {LATEST_TEMP_MAX_AGE_MINUTES} MINUTE
                            ORDER BY t.created_at DESC, t.id DESC
                            LIMIT 1)
    """
    if equipment_id:
        rows = db.execute(text(base_sql + """
            WHERE d.equipment_id = :equipment_id
            ORDER BY CAST(SUBSTRING_INDEX(d.device_id, '_', -1) AS UNSIGNED)
        """), {"equipment_id": equipment_id}).mappings().all()
    else:
        rows = db.execute(text(base_sql + " ORDER BY CAST(SUBSTRING_INDEX(d.device_id, '_', -1) AS UNSIGNED)")).mappings().all()
    return {"items": list(rows)}


@app.get("/devices/{device_id}/logs")
def get_device_logs(device_id: str, limit: int = 200, db: Session = Depends(get_db)):
    limit = max(1, min(limit, 1000))
    rows = db.execute(text("""
        SELECT log_id, device_id, reboot_count, temp_out_c, temp_core_c, fault_grade, created_at
        FROM device_log
        WHERE device_id = :device_id
        ORDER BY created_at DESC
        LIMIT :limit
    """), {"device_id": device_id, "limit": limit}).mappings().all()
    return {"items": list(rows)}



# bucket → MySQL time expression. 화이트리스트라 SQL 직접 삽입 안전.
_BUCKET_TIME_SQL = {
    "1m": "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:00')",
    "5m": "FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(created_at)/300)*300)",
    "1h": "DATE_FORMAT(created_at, '%Y-%m-%d %H:00:00')",
    "1d": "DATE_FORMAT(created_at, '%Y-%m-%d 12:00:00')",
}


def _resolve_bucket(bucket: str, days: int) -> str:
    """bucket=auto 일 때 days에 맞춰 합리적 해상도로 선택."""
    if bucket != "auto":
        return bucket
    if   days <= 1:  return "raw"
    elif days <= 7:  return "1m"
    elif days <= 30: return "1h"
    else:            return "1d"


@app.get("/chart/{device_id}")
def get_chart(
    device_id: str,
    days: int = Query(1, ge=1, le=365, description="조회 기간 (일)"),
    metric: str = Query(
        "all",
        pattern="^(all|temp|rms)$",
        description="all=온도+RMS, temp=온도만, rms=진동만",
    ),
    bucket: str = Query(
        "auto",
        pattern="^(auto|raw|1m|5m|1h|1d)$",
        description="시간 버킷. auto=days에 맞춰 자동, raw=원본, 1m/5m/1h/1d=평균 집계",
    ),
    db: Session = Depends(get_db),
):
    """
    단일 디바이스 차트용 시계열 (column/series 형식).

    응답:
      {
        "device_id": "esp_32_0",
        "days":   7,
        "bucket": "1m",
        "metric": "all",
        "count":  10080,
        "series": {
            "t":      ["2026-05-15T08:00:00", ...],
            "temp1":  [25.3, ...],
            "temp2":  [26.1, ...],
            "core_temp": [41.2, ...],   (metric=all|temp일 때만. 값 없으면 null)
            "rms_x":  [20, ...],
            "rms_y":  [18, ...],
            "rms_z":  [15, ...],
            "event":  ["normal", ...]
        }
      }
    """
    want_temp = metric in ("all", "temp")
    want_rms  = metric in ("all", "rms")
    resolved_bucket = _resolve_bucket(bucket, days)

    if resolved_bucket == "raw":
        rows = db.execute(text("""
            SELECT temp1, temp2, core_temp, rms_x, rms_y, rms_z, event,
                   created_at AS t
            FROM temperature_log
            WHERE device_id = :device_id
              AND created_at >= DATE_SUB(NOW(), INTERVAL :days DAY)
            ORDER BY created_at ASC
        """), {"device_id": device_id, "days": days}).mappings().all()
    else:
        time_expr = _BUCKET_TIME_SQL[resolved_bucket]
        sql = f"""
            SELECT
                {time_expr}        AS t,
                ROUND(AVG(temp1),2) AS temp1,
                ROUND(AVG(temp2),2) AS temp2,
                ROUND(AVG(core_temp),2) AS core_temp,
                ROUND(AVG(rms_x))   AS rms_x,
                ROUND(AVG(rms_y))   AS rms_y,
                ROUND(AVG(rms_z))   AS rms_z,
                CASE
                    WHEN SUM(event = 'disconnected') > 0 THEN 'disconnected'
                    WHEN SUM(event = 'warning')      > 0 THEN 'warning'
                    ELSE 'normal'
                END AS event
            FROM temperature_log
            WHERE device_id = :device_id
              AND created_at >= DATE_SUB(NOW(), INTERVAL :days DAY)
            GROUP BY t
            ORDER BY t ASC
        """
        rows = db.execute(text(sql), {"device_id": device_id, "days": days}).mappings().all()

    series: dict = {
        "t":     [r["t"]     for r in rows],
        "event": [r["event"] for r in rows],
    }
    if want_temp:
        series["temp1"] = [r["temp1"] for r in rows]
        series["temp2"] = [r["temp2"] for r in rows]
        series["core_temp"] = [r["core_temp"] for r in rows]
    if want_rms:
        series["rms_x"] = [r["rms_x"] for r in rows]
        series["rms_y"] = [r["rms_y"] for r in rows]
        series["rms_z"] = [r["rms_z"] for r in rows]

    return {
        "device_id": device_id,
        "days":      days,
        "bucket":    resolved_bucket,
        "metric":    metric,
        "count":     len(rows),
        "series":    series,
    }


@app.get("/sensor")
def get_sensor(
    device_id: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    db: Session = Depends(get_db),
):
    # core_temp 포함: 앱 "AI 예측 온도" 카드가 /temperature(→ 이 함수)의
    # items[0].core_temp를 읽는다. NULL은 그대로 JSON null로 내려 앱이 "측정 불가"로 표시.
    if device_id:
        rows = db.execute(text("""
            SELECT id, device_id, temp1, temp2, core_temp, rms_x, rms_y, rms_z, event, created_at
            FROM temperature_log
            WHERE device_id = :device_id
            ORDER BY created_at DESC
            LIMIT :limit
        """), {"device_id": device_id, "limit": limit}).mappings().all()
    else:
        rows = db.execute(text("""
            SELECT id, device_id, temp1, temp2, core_temp, rms_x, rms_y, rms_z, event, created_at
            FROM temperature_log
            ORDER BY created_at DESC
            LIMIT :limit
        """), {"limit": limit}).mappings().all()
    return {"items": list(rows)}


@app.post("/sensor", status_code=201)
def post_sensor(payload: SensorLogIn, db: Session = Depends(get_db)):
    result = db.execute(text("""
        INSERT INTO temperature_log
            (device_id, temp1, temp2, core_temp, rms_x, rms_y, rms_z, event)
        VALUES
            (:device_id, :temp1, :temp2, :core_temp, :rms_x, :rms_y, :rms_z, :event)
    """), payload.model_dump())
    db.commit()
    return {"ok": True, "id": result.lastrowid, "device_id": payload.device_id}


@app.patch("/sensor/{log_id}")
def update_sensor(log_id: int, payload: SensorLogUpdate, db: Session = Depends(get_db)):
    row = db.execute(text("SELECT id FROM temperature_log WHERE id = :id"), {"id": log_id}).first()
    if not row:
        raise HTTPException(status_code=404, detail="temperature_log id not found")
    db.execute(text("""
        UPDATE temperature_log
        SET temp1 = :temp1, temp2 = :temp2, core_temp = :core_temp,
            rms_x = :rms_x, rms_y = :rms_y, rms_z = :rms_z,
            event = :event
        WHERE id = :id
    """), {**payload.model_dump(), "id": log_id})
    db.commit()
    return {"ok": True, "id": log_id, "event": payload.event}


@app.get("/gateway_health")
def get_gateway_health(
    gateway_id: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    db: Session = Depends(get_db),
):
    """
    게이트웨이(STM32)가 재시작 직후 보내는 crash_report 조회.
    소스 토픽: ingps/gateway_health → gateway_health_log 테이블.
    최신순(created_at DESC)으로 최대 limit개 반환.
    """
    if gateway_id:
        rows = db.execute(text("""
            SELECT id, gateway_id, event,
                   prev_init_result, prev_mqtt_conn_result,
                   prev_pub_count, prev_fail_count, prev_uptime_sec, created_at
            FROM gateway_health_log
            WHERE gateway_id = :gateway_id
            ORDER BY created_at DESC
            LIMIT :limit
        """), {"gateway_id": gateway_id, "limit": limit}).mappings().all()
    else:
        rows = db.execute(text("""
            SELECT id, gateway_id, event,
                   prev_init_result, prev_mqtt_conn_result,
                   prev_pub_count, prev_fail_count, prev_uptime_sec, created_at
            FROM gateway_health_log
            ORDER BY created_at DESC
            LIMIT :limit
        """), {"limit": limit}).mappings().all()
    return {"items": list(rows)}


@app.get("/temperature/chart")
def legacy_temperature_chart(
    device_id: str,
    days: int = Query(1, ge=1, le=366, description="최근 N일 (start/end 미지정 시)"),
    start: Optional[str] = Query(None, description="조회 시작일 YYYY-MM-DD (end와 함께 사용)"),
    end: Optional[str] = Query(None, description="조회 종료일 YYYY-MM-DD (포함, start와 함께 사용)"),
    bucket: Optional[str] = Query(None, pattern="^(1m|1h|1d)$",
                                  description="집계 해상도. 미지정 시 span 규칙 적용"),
    db: Session = Depends(get_db),
):
    """
    모바일 차트용 items 응답. 기간(start~end) 또는 최근 N일(days) 조회.

    버킷: bucket 파라미터가 있으면 그 해상도로 서버 집계(권장 — 페이로드 최소화).
      미지정 시 레거시 규칙: span <= 7 → 1m, span > 7 → 1d.
      앱 기준 권장값: 1일 뷰=1m(분단위 곡선), 1주~기간=1d.

    응답: {"items":[{created_at, temp1, temp1_max, temp1_min, temp2, temp2_max,
                      temp2_min, core_temp, rms_x, rms_y, rms_z, event}, ...], ...}  (created_at ASC)

    core_temp는 AVG만 제공(max/min 미제공 — 필요해지면 temp1/temp2와 동일 패턴으로 확장).
    버킷 내 core_temp가 전부 NULL이면 AVG도 NULL → 해당 항목 null.
    """
    # ── 조회 구간(window) 결정 ───────────────────────────────────────
    params = {"device_id": device_id}
    if start and end:
        try:
            start_d = datetime.strptime(start, "%Y-%m-%d").date()
            end_d   = datetime.strptime(end,   "%Y-%m-%d").date()
        except ValueError:
            raise HTTPException(status_code=400, detail="start/end는 YYYY-MM-DD 형식이어야 합니다.")
        if end_d < start_d:
            raise HTTPException(status_code=400, detail="end는 start보다 빠를 수 없습니다.")
        span_days = (end_d - start_d).days + 1
        params["start_dt"] = f"{start_d} 00:00:00"
        params["end_dt"]   = f"{end_d + timedelta(days=1)} 00:00:00"   # 종료일 포함(다음날 0시 미만)
        where = "device_id = :device_id AND created_at >= :start_dt AND created_at < :end_dt"
    else:
        span_days = days
        params["days"] = days
        where = "device_id = :device_id AND created_at >= DATE_SUB(NOW(), INTERVAL :days DAY)"

    # 해상도 결정: 명시된 bucket 우선, 없으면 레거시 span 규칙
    resolved = bucket if bucket else ("1d" if span_days > 7 else "1m")
    # 표시용 created_at / GROUP BY 키 (화이트리스트라 f-string 삽입 안전)
    bucket_fmt = {
        "1m": ("%Y-%m-%d %H:%i:00", "%Y-%m-%d %H:%i:00"),
        "1h": ("%Y-%m-%d %H:00:00", "%Y-%m-%d %H"),
        "1d": ("%Y-%m-%d 12:00:00", "%Y-%m-%d"),
    }
    disp_fmt, group_fmt = bucket_fmt[resolved]

    event_case = ("CASE WHEN SUM(event='disconnected')>0 THEN 'disconnected' "
                  "WHEN SUM(event='warning')>0 THEN 'warning' ELSE 'normal' END AS event")

    sql = f"""
        SELECT DATE_FORMAT(created_at, '{disp_fmt}') AS created_at,
               ROUND(AVG(temp1),2) AS temp1, ROUND(MAX(temp1),2) AS temp1_max, ROUND(MIN(temp1),2) AS temp1_min,
               ROUND(AVG(temp2),2) AS temp2, ROUND(MAX(temp2),2) AS temp2_max, ROUND(MIN(temp2),2) AS temp2_min,
               ROUND(AVG(core_temp),2) AS core_temp,
               ROUND(AVG(rms_x)) AS rms_x, ROUND(AVG(rms_y)) AS rms_y, ROUND(AVG(rms_z)) AS rms_z,
               {event_case}
        FROM temperature_log
        WHERE {where}
        GROUP BY DATE_FORMAT(created_at, '{group_fmt}')
        ORDER BY created_at ASC
    """
    rows = db.execute(text(sql), params).mappings().all()
    return {
        "device_id": device_id,
        "bucket": resolved,
        "span_days": span_days,
        "start": start, "end": end,
        "count": len(rows),
        "items": [dict(r) for r in rows],
    }


@app.get("/temperature/dates")
def temperature_available_dates(
    device_id: str,
    days: int = Query(400, ge=1, le=1000, description="최근 N일 내에서 데이터가 있는 날짜 조회"),
    db: Session = Depends(get_db),
):
    """
    해당 디바이스에 데이터가 존재하는 '날짜' 목록(YYYY-MM-DD).
    모바일 캘린더에서 데이터 없는 날짜를 비활성/표시하는 데 사용.
    """
    rows = db.execute(text("""
        SELECT DISTINCT DATE(created_at) AS d
        FROM temperature_log
        WHERE device_id = :device_id
          AND created_at >= DATE_SUB(NOW(), INTERVAL :days DAY)
        ORDER BY d ASC
    """), {"device_id": device_id, "days": days}).mappings().all()
    return {"device_id": device_id, "dates": [str(r["d"]) for r in rows]}


@app.get("/temperature")
def legacy_temperature(
    device_id: Optional[str] = None,
    limit: int = 100,
    since: Optional[str] = Query(None, description="이 시각(YYYY-MM-DD HH:MM:SS) 이후 행만 (증분 폴링용, ASC)"),
    db: Session = Depends(get_db),
):
    # 증분 폴링: since 이후 새 행만 ASC로 반환 — 실시간 1초 폴링 페이로드 최소화.
    if since and device_id:
        rows = db.execute(text("""
            SELECT id, device_id, temp1, temp2, core_temp, rms_x, rms_y, rms_z, event, created_at
            FROM temperature_log
            WHERE device_id = :device_id AND created_at > :since
            ORDER BY created_at ASC
            LIMIT :limit
        """), {"device_id": device_id, "since": since, "limit": limit}).mappings().all()
        return {"items": list(rows)}
    return get_sensor(device_id=device_id, limit=limit, db=db)


@app.post("/debug/bootstrap")
def bootstrap_minimal(db: Session = Depends(get_db)):
    """최소 더미: LN_01 → EQ_B01 → esp_32_0 ~ esp_32_9."""
    db.execute(text("""
        INSERT INTO line (line_id, line_name)
        VALUES ('LN_01', 'A조립라인')
        ON DUPLICATE KEY UPDATE line_name = VALUES(line_name)
    """))
    db.execute(text("""
        INSERT INTO equipment (equipment_id, line_id, equipment_name)
        VALUES ('EQ_B01', 'LN_01', 'B설비')
        ON DUPLICATE KEY UPDATE line_id = VALUES(line_id), equipment_name = VALUES(equipment_name)
    """))
    for suffix in range(10):
        db.execute(text("""
            INSERT INTO device (device_id, equipment_id, status, installed_on, last_seen_at)
            VALUES (:device_id, 'EQ_B01', 'Normal', CURDATE(), NOW())
            ON DUPLICATE KEY UPDATE equipment_id = VALUES(equipment_id), last_seen_at = NOW()
        """), {"device_id": f"esp_32_{suffix}"})
    db.commit()
    return {"ok": True, "devices": [f"esp_32_{i}" for i in range(10)]}


@app.post("/debug/log")
def insert_dummy_log(payload: DummyLogIn, db: Session = Depends(get_db)):
    """더미 device_log + last_seen/status 갱신."""
    device = db.execute(text("""
        SELECT device_id FROM device WHERE device_id = :device_id
    """), {"device_id": payload.device_id}).mappings().first()
    if not device:
        raise HTTPException(status_code=404, detail="device_id not found. Call /debug/bootstrap first.")

    # device.status ENUM = Normal / Warning1 / Warning2 / Warning3 / Disconnected
    if   payload.fault_grade >= 8: status = "Warning3"
    elif payload.fault_grade >= 5: status = "Warning2"
    elif payload.fault_grade >= 3: status = "Warning1"
    else:                          status = "Normal"
    db.execute(text("""
        UPDATE device
        SET last_seen_at = NOW(), status = :status
        WHERE device_id = :device_id
    """), {"device_id": payload.device_id, "status": status})

    db.execute(text("""
        INSERT INTO device_log (device_id, reboot_count, temp_out_c, temp_core_c, fault_grade, created_at)
        VALUES (:device_id, :reboot_count, :temp_out_c, :temp_core_c, :fault_grade, NOW())
    """), payload.model_dump())

    db.commit()
    return {"ok": True, "device_id": payload.device_id, "status": status, "ts": datetime.now().isoformat()}


@app.get("/export/sensor.csv")
def export_sensor_csv(
    device_id: Optional[str] = Query(None, description="특정 디바이스만. 미지정 시 전체"),
    limit: int = Query(2500, ge=1, le=2500, description="최대 2500개"),
    username: str = Depends(current_user),   # 로그인 필수
    db: Session = Depends(get_db),
):
    """
    Android가 GET으로 받는 것과 동일한 센서 데이터
    (ADXL335 진동 rms_x/y/z + Thermistor temp1/temp2)를
    최대 1000개, 시간 오름차순으로 CSV 다운로드.

    최신 N개를 고른 뒤 시간순(오래된→최신)으로 정렬해 반환한다.
    """
    params = {"limit": limit}
    where = ""
    if device_id:
        where = "WHERE device_id = :device_id"
        params["device_id"] = device_id

    # 최신 limit개를 뽑고(서브쿼리), 바깥에서 created_at ASC 로 시간순 정렬
    rows = db.execute(text(f"""
        SELECT created_at, device_id, temp1, temp2, rms_x, rms_y, rms_z, event
        FROM (
            SELECT created_at, device_id, temp1, temp2, rms_x, rms_y, rms_z, event
            FROM temperature_log
            {where}
            ORDER BY created_at DESC
            LIMIT :limit
        ) AS recent
        ORDER BY created_at ASC
    """), params).mappings().all()

    buf = io.StringIO()
    writer = csv.writer(buf)
    writer.writerow(["created_at", "device_id", "temp1", "temp2",
                     "rms_x", "rms_y", "rms_z", "event"])
    for r in rows:
        writer.writerow([
            r["created_at"], r["device_id"], r["temp1"], r["temp2"],
            r["rms_x"], r["rms_y"], r["rms_z"], r["event"],
        ])
    buf.seek(0)

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    tag = device_id if device_id else "all"
    fname = f"ingps_sensor_{tag}_{stamp}.csv"
    return StreamingResponse(
        iter([buf.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{fname}"'},
    )


@app.get("/")
def root():
    return RedirectResponse(url="/web/login.html")

# /web/* : login.html, signup.html, index.html, style.css, app.js
app.mount("/web", StaticFiles(directory=WEB_DIR, html=True), name="web")
