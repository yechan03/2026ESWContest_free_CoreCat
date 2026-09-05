# ML 모델 검증 환경

센서 데이터 기반 머신러닝/PINN 모델을 학습·검증하고, 경량 추론 코드를 임베디드 펌웨어용
C 헤더로 export하는 분석 환경입니다.

## 디렉토리 구조

```
ml_validation/
├── data/                              # 원본/가공 데이터 (CSV, 학습·검증용)
│   ├── real_thermal_runs.csv          # 실측 모터 벤치 데이터 (long format, 7 runs)
│   ├── shf_fem_synthetic.csv          # FEM 시뮬레이션 합성 데이터 (Track B)
│   ├── Thermistor_*.csv               # 서미스터 원본 세션 로그
│   ├── Vibration_data_norm_*.csv/.txt # 진동 원본 로그
│   └── electrical_data_*.csv          # 전류/전력 부하별 로그
├── notebooks/
│   ├── 01_model_evaluation.ipynb          # 초기 모델 평가
│   ├── 02_pinn_shf_real_data.ipynb        # SHF/PINN 코어온도 모델 (실측 데이터 트랙)
├── scripts/
│   ├── build_real_thermal_dataset.py  # 원본 CSV → real_thermal_runs.csv 가공
│   ├── fit_motor_thermal_resistance.py # 정상상태 열저항(R_motor) 회귀 (물리 계산, ML 아님)
│   ├── train_pinn_real.py             # SHF PINN 학습 — 실측 데이터 트랙 (배포됨)
│   ├── shf_fem_simulate.py            # FEM 시뮬레이션 합성 데이터 생성 (Track B)
│   ├── train_pinn_shf.py              # SHF PINN 학습 — 합성 FEM 트랙 (검토/대기)
│   ├── export_shf_pinn_to_c.py        # 학습된 SHF 모델 → C 헤더 export
│   ├── plot_r_motor_friendly.py       # R_motor 피팅 결과 시각화
│   ├── compare_model_sizes.py         # 모델 크기/정확도 비교
│   ├── adxl_noise_fft.py              # 진동 센서 노이즈 FFT 분석
│   ├── capture_serial.py              # 시리얼 포트 데이터 캡처 유틸
│   └── train_final.py                 # (레거시) 초기 모델 학습 자동화
├── output/
│   ├── shf_core_model.h               # SHF 코어온도 모델 — 펌웨어 배포용 헤더
│   ├── model_params.h                 # (레거시) 초기 모델 헤더
│   ├── figures/                       # 시각화 결과 이미지
│   ├── models/                        # 학습된 모델 파일
│   └── core_temperature_1C/           # 1C 코어온도 결과물
├── docs/
│   ├── pinn_pipeline_flow.md          # SHF/PINN 전체 파이프라인 흐름도·근거
│   └── motor_thermal_resistance_estimation.md  # R_motor 산출 근거
├── requirements.txt
└── README.md
```

## 환경 설정

```bash
pip install -r requirements.txt
```

## 주요 워크플로우

### 1. SHF(PINN) 코어온도 추정 모델 — 현재 배포 대상

두 표면온도 센서 값(T_ambient, T_room)으로부터 설비 내부 코어온도를 추정하는 모델입니다.
물리 손실(RC 열회로 미분방정식)을 학습에 활용하는 PINN 방식이지만, 실제 추론은 6-가중치
degree-2 다항식(poly2 + linear regression) 하나로 경량화되어 펌웨어에 그대로 올라갑니다.
전체 흐름과 설계 근거는 **[`docs/pinn_pipeline_flow.md`](docs/pinn_pipeline_flow.md)** 참조.

```bash
cd scripts
python build_real_thermal_dataset.py       # → ../data/real_thermal_runs.csv
python fit_motor_thermal_resistance.py     # → R_motor 산출 (물리 계산)
python train_pinn_real.py                  # → 3-way 비교 학습 (데이터만 / 자유 PINN / 앵커링 PINN)
python export_shf_pinn_to_c.py             # → ../output/shf_core_model.h (펌웨어 배포용)
```

노트북으로 보려면 `notebooks/02_pinn_shf_real_data.ipynb`.


