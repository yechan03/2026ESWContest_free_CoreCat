"""
train_final.py
최종 모델 학습 자동화 스크립트 (IN-GPS 캡스톤)
"""

import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score, classification_report
import joblib
import os

DATA_PATH = "../data/sensor_data.csv"
OUTPUT_DIR = "../output"


def load_data(path: str) -> tuple[pd.DataFrame, pd.Series]:
    df = pd.read_csv(path)
    X = df.drop(columns=["label"])
    y = df["label"]
    return X, y


def train(X_train, y_train):
    # TODO: 모델 정의 및 학습
    raise NotImplementedError("모델 학습 로직을 구현하세요.")


def evaluate(model, X_test, y_test):
    y_pred = model.predict(X_test)
    print(f"Accuracy: {accuracy_score(y_test, y_pred):.4f}")
    print(classification_report(y_test, y_pred))


def main():
    X, y = load_data(DATA_PATH)
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42
    )

    model = train(X_train, y_train)
    evaluate(model, X_test, y_test)

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    joblib.dump(model, os.path.join(OUTPUT_DIR, "final_model.pkl"))
    print(f"모델 저장 완료: {OUTPUT_DIR}/final_model.pkl")


if __name__ == "__main__":
    main()
