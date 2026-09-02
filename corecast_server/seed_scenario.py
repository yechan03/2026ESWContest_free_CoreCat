"""
seed_scenario.py — UI 검증용 1년치 합성 센서 데이터 주입.

서버와 동일한 DB 접속(db.py의 engine)을 사용 — mysql CLI 비밀번호 불필요.
비어 있는 temperature_log에 넣는 것을 전제로 함 (먼저 TRUNCATE 권장).

사용법 (in_gps_server 폴더에서):
    python seed_scenario.py                      # 미리보기: 생성될 행 수·통계만 출력
    python seed_scenario.py --apply              # engine으로 직접 INSERT (DB 자격증명 필요)
    python seed_scenario.py --sql seed.sql       # SQL 파일 생성 → sudo mysql ingps < seed.sql
    python seed_scenario.py --device esp_32_1 --days 365 --sql seed.sql

root가 unix_socket 인증(비밀번호 TCP 불가)인 서버에서는 --sql 모드를 쓰고
sudo mysql로 주입하는 것이 가장 간단하다.

시나리오 구성 (UI 확인 포인트):
  · 계절 주기(여름↑) + 일주기(주간 가동 09~18시 발열) + 노이즈
      → 1년/1개월 뷰: 캡슐 바 길이·평균 점 변화 확인
  · 월 1~3회 '과열일' — 표면 온도가 임계(기본 40℃) 근처~초과(최대 ~48℃)
      → 빨간 초과 마커 + 캡슐 상단 빨강 + (근접 시) 위험선·음영 노출 확인
  · 120~116일 전 5일 연속 결측(장비 정지 가정)
      → 집계뷰 빈 슬롯, 시간별 뷰 선 끊김 확인
  · 어제 10~13시 3시간 결측 → 1일 뷰 선 끊김 확인
  · 과열일 전후 event='warning', 결측 직전 'disconnected' 몇 건
      → 이벤트 마커(주황/회색 링) 확인
  · rms_x/y/z: 가동 시간대 25~60mg, 과열일 150~300mg 스파이크
  · 밀도: 이틀 전까지는 시간당 1행, 최근 48시간은 분당 1행
      → 1일 뷰/실시간 상세보기 데이터 확보 (총 ~11,600행)
"""
import argparse
import math
import random
from datetime import datetime, timedelta

ROW_SQL = ("INSERT INTO temperature_log "
           "(device_id, temp1, temp2, rms_x, rms_y, rms_z, event, created_at) VALUES "
           "(:device_id, :temp1, :temp2, :rms_x, :rms_y, :rms_z, :event, :created_at)")


def build_rows(device_id: str, days: int, seed: int = 42):
    rng = random.Random(seed)
    now = datetime.now().replace(second=0, microsecond=0)
    start = now - timedelta(days=days)

    # 과열일: 월 1~3일 무작위 선정 (일 인덱스 기준)
    hot_days = set()
    for month_start in range(0, days, 30):
        for _ in range(rng.randint(1, 3)):
            hot_days.add(month_start + rng.randint(0, 29))
    # 그중 20%는 임계 확실 초과(피크 44~48℃), 나머지는 근접(38~42℃)
    very_hot = {d for d in hot_days if rng.random() < 0.2}

    # 결측 구간: 120~116일 전(5일), 어제 10:00~13:00(3시간)
    gap_day_from, gap_day_to = days - 120, days - 116
    yesterday = (now - timedelta(days=1)).date()

    def temp_at(t: datetime, day_idx: int):
        doy = t.timetuple().tm_yday
        seasonal = 6.0 * math.sin(2 * math.pi * (doy - 105) / 365.0)   # 7월 최고
        hour_f = t.hour + t.minute / 60.0
        daily = 2.5 * math.sin(2 * math.pi * (hour_f - 9) / 24.0)
        ambient = 22.0 + seasonal + daily + rng.gauss(0, 0.3)

        operating = (t.weekday() < 6) and (9 <= hour_f < 18)           # 일요일 휴무
        motor = 0.0
        if operating:
            ramp = min(1.0, (hour_f - 9) / 2.0)                        # 가동 2h 램프업
            motor = 5.5 * ramp + rng.gauss(0, 0.4)
            if day_idx in hot_days:
                peak = (rng.uniform(44, 48) if day_idx in very_hot
                        else rng.uniform(38, 42))
                # 14시 전후 가우시안 피크 형태로 과열
                bump = math.exp(-((hour_f - 14.0) ** 2) / (2 * 2.0 ** 2))
                motor += max(0.0, (peak - ambient - 5.5)) * bump

        surface = ambient + motor + rng.gauss(0, 0.25)
        return round(surface, 2), round(ambient - 1.5 + rng.gauss(0, 0.2), 2), operating

    def rms_at(operating: bool, hot: bool):
        if not operating:
            base = rng.randint(5, 12)
        elif hot and rng.random() < 0.3:
            base = rng.randint(150, 300)
        else:
            base = rng.randint(25, 60)
        return (max(0, base + rng.randint(-4, 4)),
                max(0, base + rng.randint(-4, 4)),
                max(0, base + rng.randint(-4, 4)))

    rows = []
    t = start
    while t <= now:
        day_idx = (t - start).days
        step = timedelta(minutes=1) if (now - t) <= timedelta(hours=48) else timedelta(hours=1)

        skip = (gap_day_from <= day_idx < gap_day_to) or \
               (t.date() == yesterday and 10 <= t.hour < 13)
        if skip:
            t += step
            continue

        temp1, temp2, operating = temp_at(t, day_idx)
        hot = day_idx in hot_days
        rx, ry, rz = rms_at(operating, hot)

        event = "normal"
        if temp1 >= 40.0:
            event = "warning"
        elif t.date() == yesterday and t.hour == 9 and t.minute >= 55:
            event = "disconnected"                                     # 결측 직전
        rows.append({"device_id": device_id, "temp1": temp1, "temp2": temp2,
                     "rms_x": rx, "rms_y": ry, "rms_z": rz,
                     "event": event, "created_at": t.strftime("%Y-%m-%d %H:%M:%S")})
        t += step
    return rows


def write_sql(rows, path):
    """INSERT문 SQL 파일 생성 (500행 단위 multi-row INSERT)."""
    with open(path, "w", encoding="utf-8") as f:
        f.write("USE ingps;\n")
        for i in range(0, len(rows), 500):
            chunk = rows[i:i + 500]
            values = ",\n".join(
                "('{device_id}', {temp1}, {temp2}, {rms_x}, {rms_y}, {rms_z}, "
                "'{event}', '{created_at}')".format(**r)
                for r in chunk)
            f.write("INSERT INTO temperature_log "
                    "(device_id, temp1, temp2, rms_x, rms_y, rms_z, event, created_at) "
                    "VALUES\n" + values + ";\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="esp_32_1")
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--sql", metavar="PATH", help="INSERT SQL 파일로 출력(직접 접속 안 함)")
    args = ap.parse_args()

    rows = build_rows(args.device, args.days, args.seed)
    t1 = [r["temp1"] for r in rows]
    warn = sum(1 for r in rows if r["event"] == "warning")
    print(f"디바이스 {args.device} · {args.days}일 · 총 {len(rows)}행")
    print(f"temp1 범위 {min(t1):.1f} ~ {max(t1):.1f}℃ · warning {warn}행 · "
          f"disconnected {sum(1 for r in rows if r['event'] == 'disconnected')}행")

    if args.sql:
        write_sql(rows, args.sql)
        print(f"\nSQL 파일 생성됨: {args.sql}")
        print(f"주입:  sudo mysql ingps < {args.sql}")
        return

    if not args.apply:
        print("\n미리보기만 실행됨.")
        print("  SQL 파일로:  python3 seed_scenario.py --sql seed.sql  →  sudo mysql ingps < seed.sql")
        print("  직접 주입:   python3 seed_scenario.py --apply  (DB_USER/DB_PASS 필요)")
        return

    from sqlalchemy import text
    from db import engine
    with engine.begin() as conn:
        for i in range(0, len(rows), 2000):
            conn.execute(text(ROW_SQL), rows[i:i + 2000])
    print("주입 완료. 앱에서 1일/1주/1개월/1년/기간 뷰를 확인하세요.")


if __name__ == "__main__":
    main()
