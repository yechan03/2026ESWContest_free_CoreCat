#include "apa102.h"

static void SCK_HIGH(void) { HAL_GPIO_WritePin(GPIOB, GPIO_PIN_14, GPIO_PIN_SET); }
static void SCK_LOW(void)  { HAL_GPIO_WritePin(GPIOB, GPIO_PIN_14, GPIO_PIN_RESET); }
static void DATA_HIGH(void){ HAL_GPIO_WritePin(GPIOB, GPIO_PIN_15, GPIO_PIN_SET); }
static void DATA_LOW(void) { HAL_GPIO_WritePin(GPIOB, GPIO_PIN_15, GPIO_PIN_RESET); }

// 클럭 한 번 토글 (필요하면 딜레이 추가, APA102는 보통 딜레이 없어도 동작함)
static inline void clock_pulse(void)
{
    SCK_HIGH();
    // __NOP(); // 클럭이 너무 빨라서 안 되면 NOP 몇 개 추가
    SCK_LOW();
}

// 1바이트를 MSB부터 순차적으로 전송
static void send_byte(uint8_t byte)
{
    for (int i = 7; i >= 0; i--) {
        if (byte & (1 << i))
            DATA_HIGH();
        else
            DATA_LOW();
        clock_pulse();
    }
}

// Start Frame: 32bit의 0
static void send_start_frame(void)
{
    for (int i = 0; i < 4; i++) {
        send_byte(0x00);
    }
}

// End Frame: 최소 (LED_COUNT/2) bit 이상의 클럭 필요 → 넉넉히 바이트 단위로 전송
static void send_end_frame(void)
{
    int end_bytes = (LED_COUNT + 15) / 16;  // 여유있게 계산
    for (int i = 0; i < end_bytes; i++) {
        send_byte(0xFF);
    }
}

// 한 개 LED 프레임 전송
static void send_led_frame(apa102_led_t led)
{
    uint8_t brightness_byte = 0xE0 | (led.brightness & 0x1F); // 111 + 5bit
    send_byte(brightness_byte);
    send_byte(led.b);
    send_byte(led.g);
    send_byte(led.r);
}

// 전체 LED 배열 전송
void apa102_update(apa102_led_t *leds, int count)
{
    send_start_frame();
    for (int i = 0; i < count; i++) {
        send_led_frame(leds[i]);
    }
    send_end_frame();
}

// 모든 LED를 꺼진 상태로 초기화하고 갱신
void apa102_reset(apa102_led_t *leds, int count)
{
    for (int i = 0; i < count; i++) {
        leds[i].brightness = 0;
        leds[i].r = 0;
        leds[i].g = 0;
        leds[i].b = 0;
    }
    apa102_update(leds, count);
}

// 특정 device_id의 LED를 지정한 색상/밝기로 켜고 전체 갱신
void apa102_notify_device(apa102_led_t *leds, int count, uint8_t device_id,
                           uint8_t brightness, uint8_t r, uint8_t g, uint8_t b)
{
    if (device_id >= count) return;  // 범위 밖이면 무시

    leds[device_id].brightness = brightness;
    leds[device_id].r = r;
    leds[device_id].g = g;
    leds[device_id].b = b;

    apa102_update(leds, count);
}

// 순차 점등 함수
void apa102_sequential_test(void)
{
    apa102_led_t leds[LED_COUNT];

    for (int i = 0; i < LED_COUNT; i++) {
        // 전부 끄기
        for (int j = 0; j < LED_COUNT; j++) {
            leds[j].brightness = 0;
            leds[j].r = 0;
            leds[j].g = 0;
            leds[j].b = 0;
        }
        // i번째만 켜기
        leds[i].brightness = 31;
        leds[i].r = 0;
        leds[i].g = 1;
        leds[i].b = 0;

        apa102_update(leds, LED_COUNT);
        HAL_Delay(200); // 점등 간격
    }
}
