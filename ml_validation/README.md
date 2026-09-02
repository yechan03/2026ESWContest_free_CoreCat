# IN-GPS 캡스톤 — ML 모델 검증 환경

센서 데이터 기반 머신러닝 모델을 검증하고 ESP32에 배포하기 위한 분석 환경입니다.

## 디렉토리 구조

```
ml_validation/
├── data/
│   └── sensor_data.csv          # 센서 데이터 (추후 추가)
├── notebooks/
│   └── 01_model_evaluation.ipynb  # 모델 평가 메인 노트북
├── scripts/
│   ├── train_final.py           # 최종 학습 자동화
│   └── export_to_c.py           # C 헤더 변환 (ESP32)
├── output/
│   ├── figures/                 # 시각화 결과 이미지
│   └── model_params.h           # ESP32용 헤더 파일
├── requirements.txt
└── README.md
```

## 환경 설정

```bash
pip install -r requirements.txt
```

## 사용 방법

### 1. 노트북 실행

```bash
jupyter notebook notebooks/01_model_evaluation.ipynb
```

### 2. 최종 모델 학습

```bash
python scripts/train_final.py
```

### 3. ESP32용 헤더 생성

```bash
python scripts/export_to_c.py
# → output/model_params.h 생성
```
