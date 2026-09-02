"""
cleanup_outliers.py — 온도 이상치(글리치) 정리.

서버와 동일한 DB 접속(db.py의 engine)을 그대로 사용하므로, mysql CLI 비밀번호를
몰라도 실행할 수 있다. (서버가 붙는 자격증명 = 환경변수 DB_USER/DB_PASS 또는 기본값)

사용법 (in_gps_server 폴더에서):
    python cleanup_outliers.py           # 미리보기: 몇 개가 영향받는지만 출력
    python cleanup_outliers.py --apply   # 실제로 이상치 온도를 NULL 처리(진동 rms는 보존)

유효 범위: TMIN ~ TMAX (°C). 필요시 아래 값을 수정.
"""
import sys
from sqlalchemy import text
from db import engine

TMIN = -20
TMAX = 130


def main(apply: bool = False):
    with engine.begin() as conn:
        row = conn.execute(text("""
            SELECT COUNT(*)                            AS affected,
                   SUM(temp1 > :mx OR temp1 < :mn)     AS bad1,
                   SUM(temp2 > :mx OR temp2 < :mn)     AS bad2,
                   MAX(temp1)                          AS mx1,
                   MAX(temp2)                          AS mx2
            FROM temperature_log
            WHERE temp1 > :mx OR temp1 < :mn
               OR temp2 > :mx OR temp2 < :mn
        """), {"mn": TMIN, "mx": TMAX}).mappings().first()

        print(f"유효범위: {TMIN} ~ {TMAX} °C")
        print(f"영향 행: {row['affected']}  | 이상 temp1: {row['bad1']}  이상 temp2: {row['bad2']}")
        print(f"현재 최댓값 → temp1: {row['mx1']}  temp2: {row['mx2']}")

        if not apply:
            print("\n미리보기만 실행됨. 실제 적용하려면:  python cleanup_outliers.py --apply")
            return

        r1 = conn.execute(text(
            "UPDATE temperature_log SET temp1 = NULL WHERE temp1 > :mx OR temp1 < :mn"),
            {"mn": TMIN, "mx": TMAX})
        r2 = conn.execute(text(
            "UPDATE temperature_log SET temp2 = NULL WHERE temp2 > :mx OR temp2 < :mn"),
            {"mn": TMIN, "mx": TMAX})
        print(f"\n적용 완료 → temp1 NULL: {r1.rowcount}개, temp2 NULL: {r2.rowcount}개 (진동 rms는 보존)")


if __name__ == "__main__":
    main(apply="--apply" in sys.argv)
