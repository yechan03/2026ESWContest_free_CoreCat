#ifndef APA102_H
#define APA102_H

#include "main.h"  // HAL_GPIO 관련 include, 프로젝트 환경에 맞게 수정

#define LED_COUNT   20

// APA102 프레임 포맷: 1 1 1 [5bit 밝기] [8bit B] [8bit G] [8bit R]
typedef struct {
    uint8_t brightness; // 0~31
    uint8_t r;
    uint8_t g;
    uint8_t b;
} apa102_led_t;

void apa102_update(apa102_led_t *leds, int count);
void apa102_sequential_test(void);
void apa102_notify_device(apa102_led_t *leds, int count, uint8_t device_id,
                           uint8_t brightness, uint8_t r, uint8_t g, uint8_t b);
void apa102_reset(apa102_led_t *leds, int count);
#endif // APA102_H
