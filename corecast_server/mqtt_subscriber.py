import json
import logging
from typing import Optional

import paho.mqtt.client as mqtt
from db import SessionLocal
from sqlalchemy import text

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# 센서 오류와 노이즈를 거르되 실제 과열 데이터는 보존한다.
VALID_TEMP_MIN_C = -20.0
VALID_TEMP_MAX_C = 80.0


def _valid_temp(v):
    """유효 범위를 벗어난 온도는 None 반환(→ DB에 NULL 저장, 집계에서 자동 제외)."""
    if v is None:
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    if f < VALID_TEMP_MIN_C or f > VALID_TEMP_MAX_C:
        return None
    return f


def _cast_float(v):
    """타입 검증 + 무효 센티넬 배제. 실패/None/센티넬이면 None → DB NULL.

    core_temp 전용. core_temp는 SHF 모델이 추정한 '설비 내부' 온도라
    과열 시 VALID_TEMP_MAX_C(80°C)를 정상적으로 초과할 수 있다.
    표면 센서(AS6221)용 _valid_temp() 범위 필터를 적용하면 정작 필요한
    과열 구간이 통째로 NULL이 되므로 상한은 걸지 않는다.

    다만 ESP32(main/ble/ble_adv.c)는 TH1/TH2 읽기 실패 시 core_temp에
    AS6221_TEMP_INVALID_X100(-32768, ÷100=-327.68°C) 센티넬을 싣는다.
    게이트웨이가 이를 그대로 실수로 변환해 publish하면 이 필터 없이는
    "무효"가 아니라 실측처럼 DB에 쌓이고, AVG 집계와 앱 UI("측정 불가"
    대신 -327.7°C 표시)까지 오염된다. SHF 모델의 이론적 출력 범위는
    -141.5~138.1°C(ble_adv.c 주석)라 -200.0 미만은 정상값을 자를 위험 없이
    센티넬만 걸러낸다.
    """
    if v is None:
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    if f < -200.0:
        return None
    return f

MQTT_HOST = "localhost"
MQTT_PORT = 1883
# 토픽 구조 (와일드카드 # 로 통합 구독):
#   ingps/sensor          : ESP RMS+온도 페이로드 (게이트웨이가 mfg_data 파싱해 publish)
#   ingps/mvpmodel        : 레거시 (기존 호환용)
#   ingps/gateway_health  : STM32 게이트웨이 crash_report (재시작 직후 직전 실행 요약)
#   ingps/<gateway>/<dev> : gateway/device hello (last_seen 갱신)
MQTT_TOPIC = "ingps/#"


# ============================================================
# byte_id → device_id 매핑
#   ESP 펌웨어 mfg_data 13번째 바이트(0x01~0x0A) → "esp_32_0" ~ "esp_32_9"
#   매핑 룰: device_id_suffix = byte_id - 1
# ============================================================
def esp_byte_to_device_id(byte_id):
    # type: (Optional[int]) -> Optional[str]
    """ESP 메인패클처 byte → device_id 문자열. 범위를 벗어나면 None."""
    if byte_id is None:
        return None
    if 0 <= byte_id <= 9:
        return "esp_32_{0}".format(byte_id)
    return None


def handle_mfg_data(payload: dict):
    """
    STM32 Gateway hello/heartbeat 처리 (last_seen 갱신용).
    {
        "gateway_id": "GW_LN01",
        "device_id":  "esp_32_0",
        "mac":        "CA:BB:CC:DD:EE:01"
    }
    """
    db = SessionLocal()
    try:
        gateway_id = payload.get("gateway_id")
        device_id  = payload.get("device_id")
        mac        = payload.get("mac", "")

        if not gateway_id or not device_id:
            logger.warning("gateway_id 또는 device_id 누락: %s", payload)
            return

        result = db.execute(text("""
            UPDATE line_gateway
            SET last_seen_at = NOW(), status = 'Normal'
            WHERE gateway_id = :gateway_id
        """), {"gateway_id": gateway_id})

        if result.rowcount == 0:
            logger.warning("[MQTT] Gateway 미등록: %s", gateway_id)

        result = db.execute(text("""
            UPDATE device
            SET last_seen_at = NOW()
            WHERE device_id = :device_id
        """), {"device_id": device_id})

        if result.rowcount == 0:
            logger.info("[MQTT] 미등록 Device 감지: %s (mac=%s)", device_id, mac)
        else:
            logger.info("[MQTT] Device hello ✅ | id=%s mac=%s", device_id, mac)

        db.commit()

    except Exception as e:
        db.rollback()
        logger.error("[MQTT] DB 처리 실패: %s", e)
    finally:
        db.close()


def handle_gateway_health(payload: dict):
    """
    STM32 Gateway crash_report 처리 (재시작 직후 직전 실행 요약).

    토픽: ingps/gateway_health
    {
        "gateway_id": "GW_LN01",
        "event": "crash_report",
        "prev_init_result": 0,
        "prev_mqtt_conn_result": 0,
        "prev_pub_count": 1234,
        "prev_fail_count": 3,
        "prev_uptime_sec": 86400
    }
    """
    db = SessionLocal()
    try:
        gateway_id = payload.get("gateway_id")
        if not gateway_id:
            logger.warning("[MQTT] gateway_health gateway_id 누락: %s", payload)
            return

        db.execute(text("""
            INSERT INTO gateway_health_log
                (gateway_id, event,
                 prev_init_result, prev_mqtt_conn_result,
                 prev_pub_count, prev_fail_count, prev_uptime_sec, created_at)
            VALUES
                (:gateway_id, :event,
                 :prev_init_result, :prev_mqtt_conn_result,
                 :prev_pub_count, :prev_fail_count, :prev_uptime_sec, NOW())
        """), {
            "gateway_id":            gateway_id,
            "event":                 payload.get("event", "crash_report"),
            "prev_init_result":      payload.get("prev_init_result"),
            "prev_mqtt_conn_result": payload.get("prev_mqtt_conn_result"),
            "prev_pub_count":        payload.get("prev_pub_count"),
            "prev_fail_count":       payload.get("prev_fail_count"),
            "prev_uptime_sec":       payload.get("prev_uptime_sec"),
        })
        db.commit()
        logger.info(
            "[MQTT] gateway_health ✅ | gw=%s ev=%s uptime=%s pub=%s fail=%s",
            gateway_id, payload.get("event"),
            payload.get("prev_uptime_sec"),
            payload.get("prev_pub_count"), payload.get("prev_fail_count"),
        )

    except Exception as e:
        db.rollback()
        logger.error("[MQTT] gateway_health 저장 실패: %s | payload=%s", e, payload)
    finally:
        db.close()


def _classify_event(rms_x, rms_y, rms_z):
    # type: (Optional[int], Optional[int], Optional[int]) -> str
    """RMS 진동 크기로 간단 임계치 분류. 임계치는 추후 ML로 대체 가능."""
    rms_values = [v for v in (rms_x, rms_y, rms_z) if v is not None]
    if not rms_values:
        return "normal"
    peak = max(rms_values)
    if peak >= 5000:   # 5g 이상 → warning
        return "warning"
    return "normal"


def handle_sensor(payload: dict):
    """
    ESP RMS + 온도 페이로드 처리.

    Gateway가 mfg_data를 파싱해 보내는 JSON payload:
    {
        "gateway_id":  "GW_LN01",      (옵션)
        "esp_byte_id": 1,              ← mfg_data 13번째 바이트 그대로
        "temp1":       25.30,
        "temp2":       26.10,
        "core_temp":   41.20,          ← ESP가 SHF 모델로 계산한 내부 추정온도 (옵션)
        "rms_x":       20,             (mg, uint16)
        "rms_y":       18,
        "rms_z":       15
    }
    또는 device_id를 게이트웨이가 이미 결정해 보낼 경우:
        "device_id": "esp_32_0" 도 우선 인식.
    """
    db = SessionLocal()
    try:
        device_id = payload.get("device_id")
        if not device_id:
            byte_id = payload.get("esp_byte_id")
            device_id = esp_byte_to_device_id(byte_id)

        if not device_id:
            logger.warning("[MQTT] device_id/esp_byte_id 매핑 실패: %s", payload)
            return

        temp1 = _valid_temp(payload.get("temp1"))
        temp2 = _valid_temp(payload.get("temp2"))
        if temp1 is None and payload.get("temp1") is not None:
            logger.warning("[MQTT] temp1 이상치 무시(→NULL): %s", payload.get("temp1"))
        if temp2 is None and payload.get("temp2") is not None:
            logger.warning("[MQTT] temp2 이상치 무시(→NULL): %s", payload.get("temp2"))

        # core_temp: 상한 없이 -200°C 미만(ESP 무효 센티넬 -327.68°C 포함)만 배제
        # (_cast_float 주석 참고). 레거시 ingps/mvpmodel 페이로드에는 키 자체가
        # 없어 자연히 None → NULL.
        core_temp = _cast_float(payload.get("core_temp"))
        if core_temp is None and payload.get("core_temp") is not None:
            logger.warning("[MQTT] core_temp 무효/캐스팅 실패(→NULL): %r", payload.get("core_temp"))

        rms_x = payload.get("rms_x")
        rms_y = payload.get("rms_y")
        rms_z = payload.get("rms_z")

        event = payload.get("event") or _classify_event(rms_x, rms_y, rms_z)

        db.execute(text("""
            INSERT INTO temperature_log
                (device_id, temp1, temp2, core_temp, rms_x, rms_y, rms_z, event, created_at)
            VALUES
                (:device_id, :temp1, :temp2, :core_temp, :rms_x, :rms_y, :rms_z, :event, NOW())
        """), {
            "device_id": device_id,
            "temp1": temp1, "temp2": temp2, "core_temp": core_temp,
            "rms_x": rms_x, "rms_y": rms_y, "rms_z": rms_z,
            "event": event,
        })

        db.execute(text("""
            UPDATE device
            SET last_seen_at = NOW()
            WHERE device_id = :device_id
        """), {"device_id": device_id})

        db.commit()
        logger.info(
            "[MQTT] sensor ✅ | %s T1=%.2f T2=%.2f CORE=%.2f RMS=(%s,%s,%s) ev=%s",
            device_id,
            temp1 if temp1 is not None else float("nan"),
            temp2 if temp2 is not None else float("nan"),
            core_temp if core_temp is not None else float("nan"),
            rms_x, rms_y, rms_z, event,
        )

    except Exception as e:
        db.rollback()
        logger.error("[MQTT] sensor 저장 실패: %s | payload=%s", e, payload)
    finally:
        db.close()


def handle_temperature_legacy(payload: dict):
    """
    레거시 ingps/mvpmodel 토픽 — angle_x/y/z 키로 들어오는 옛 페이로드도
    DB 컬럼 rename에 맞춰 rms_x/y/z 자리에 저장.
    """
    # angle_* 키가 들어오면 rms_* 자리로 옮겨 sensor 핸들러에 위임
    aliased = dict(payload)
    if "angle_x" in aliased and "rms_x" not in aliased:
        aliased["rms_x"] = aliased.pop("angle_x")
    if "angle_y" in aliased and "rms_y" not in aliased:
        aliased["rms_y"] = aliased.pop("angle_y")
    if "angle_z" in aliased and "rms_z" not in aliased:
        aliased["rms_z"] = aliased.pop("angle_z")
    handle_sensor(aliased)


def on_connect(client, userdata, flags, rc):
    if rc == 0:
        logger.info("[MQTT] Broker 연결 성공")
        client.subscribe(MQTT_TOPIC)
        logger.info("[MQTT] 구독 시작: %s", MQTT_TOPIC)
    else:
        logger.error("[MQTT] 연결 실패 rc=%d", rc)


def on_message(client, userdata, msg):
    logger.info("[MQTT] 수신 topic=%s", msg.topic)
    try:
        payload = json.loads(msg.payload.decode("utf-8"))
    except json.JSONDecodeError as e:
        logger.error("[MQTT] JSON 파싱 실패: %s | raw=%s", e, msg.payload)
        return

    if msg.topic == "ingps/sensor":
        handle_sensor(payload)
    elif msg.topic == "ingps/mvpmodel":
        handle_temperature_legacy(payload)
    elif msg.topic == "ingps/gateway_health":
        handle_gateway_health(payload)
    else:
        handle_mfg_data(payload)


def start_mqtt():
    client = mqtt.Client()
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
    logger.info("[MQTT] Subscriber 시작...")
    client.loop_forever()


if __name__ == "__main__":
    start_mqtt()
