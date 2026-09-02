#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
capture_serial.py — ESP32-S3 raw 캡처 시리얼 → CSV 저장기

펌웨어(ADXL_RAW_CAPTURE=1)가 부팅 직후 UART로 흘리는
  # ADXL_RAW_CAPTURE BEGIN ...
  x,y,z
  ...
  # ADXL_RAW_CAPTURE END n=... elapsed_ms=...
구간만 골라 CSV로 저장한다. BEGIN~END 사이의 'a,b,c' 숫자 3열만 기록.

사용:
  python capture_serial.py --port COM5 --baud 115200 --out ../data/esp32_still_01.csv
  (리눅스/맥은 --port /dev/ttyUSB0 또는 /dev/ttyACM0)

의존성: pyserial  (pip install pyserial)
"""
import argparse
import re
import sys
import time

try:
    import serial
except Exception:
    print("pyserial 필요: pip install pyserial", file=sys.stderr)
    raise

ROW = re.compile(r"^\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*$")


def esp_reset(ser):
    """DTR/RTS 자동 리셋 회로(RTS→EN, DTR→GPIO0)를 토글해 칩을 일반 모드로 리셋.
       리셋 버튼 없는 PCB에서 소프트웨어로 캡처 창(부팅)을 다시 띄우기 위함.
       GPIO0은 HIGH 유지(DTR=False) → 다운로드 모드 아닌 앱 부팅."""
    ser.setDTR(False)   # GPIO0 = HIGH (앱 부팅)
    ser.setRTS(True)    # EN = LOW  → 리셋 걸림
    time.sleep(0.1)
    ser.setRTS(False)   # EN = HIGH → 리셋 해제, 부팅 시작
    time.sleep(0.05)
    ser.reset_input_buffer()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", required=True, help="시리얼 포트 (COM5, /dev/ttyUSB0 ...)")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--out", required=True, help="저장할 CSV 경로")
    ap.add_argument("--timeout", type=float, default=120.0,
                    help="END 마커 못 받을 때 최대 대기 초")
    ap.add_argument("--no-reset", action="store_true",
                    help="DTR/RTS 자동 리셋 비활성화(리셋 버튼 직접 누를 때)")
    args = ap.parse_args()

    ser = serial.Serial(args.port, args.baud, timeout=1.0)
    if not args.no_reset:
        print(f"[{args.port}] 열림 — DTR/RTS로 보드 자동 리셋. 기기는 정지 상태로.")
        esp_reset(ser)
    else:
        print(f"[{args.port}] 열림 — 보드 리셋(EN) 누르면 캡처 시작. 기기는 정지 상태로.")
    capturing = False
    n = 0
    t0 = time.time()
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("# x,y,z (counts) — ESP32-S3 ADXL raw @200Hz\n")
        while time.time() - t0 < args.timeout + (300 if capturing else 0):
            line = ser.readline().decode("utf-8", "replace").strip()
            if not line:
                continue
            if "ADXL_RAW_CAPTURE BEGIN" in line:
                capturing = True
                t0 = time.time()
                print(">> BEGIN 감지, 기록 시작")
                continue
            if "ADXL_RAW_CAPTURE END" in line:
                print(f">> END 감지: {line.lstrip('# ')}")
                break
            if capturing:
                m = ROW.match(line)
                if m:
                    f.write(",".join(m.groups()) + "\n")
                    n += 1
                    if n % 1000 == 0:
                        print(f"   {n} samples...")
    ser.close()
    print(f"완료: {n} samples → {args.out}")
    if n:
        print("다음: python adxl_noise_fft.py "
              f"{args.out} --fs 200 --units counts")


if __name__ == "__main__":
    main()
