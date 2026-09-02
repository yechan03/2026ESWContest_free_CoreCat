/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.c
  * @brief          : Main program body
  ******************************************************************************
  * @attention
  *
  * Copyright (c) 2026 STMicroelectronics.
  * All rights reserved.
  *
  * This software is licensed under terms that can be found in the LICENSE file
  * in the root directory of this software component.
  * If no LICENSE file comes with this software, it is provided AS-IS.
  *
  ******************************************************************************
  */
/* USER CODE END Header */
/* Includes ------------------------------------------------------------------*/
#include "main.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */
#include "wizchip_conf.h"
#include "socket.h"
#include <string.h>
#include <stdio.h>
#include "apa102.h"
/* USER CODE END Includes */

/* Private typedef -----------------------------------------------------------*/
/* USER CODE BEGIN PTD */

/* USER CODE END PTD */

/* Private define ------------------------------------------------------------*/
/* USER CODE BEGIN PD */

/* USER CODE END PD */

/* Private macro -------------------------------------------------------------*/
/* USER CODE BEGIN PM */

/* USER CODE END PM */

/* Private variables ---------------------------------------------------------*/

IWDG_HandleTypeDef hiwdg;

UART_HandleTypeDef hlpuart1;
UART_HandleTypeDef huart1;

RAMCFG_HandleTypeDef hramcfg_SRAM1;

RNG_HandleTypeDef hrng;

RTC_HandleTypeDef hrtc;

SPI_HandleTypeDef hspi3;

/* USER CODE BEGIN PV */
typedef struct {
    uint8_t device_id;
    int16_t temp1_x100;
    int16_t temp2_x100;
    int16_t angle_x_x100;
    int16_t angle_y_x100;
    int16_t angle_z_x100;
    int16_t core_temp_x100;   /* mfg_data[13..14] : ESP core_temp x100 (int16 LE) */
} ble_msg_t;

static uint8_t mqtt_ready = 0;
static apa102_led_t led_state[LED_COUNT];

/* --- LED 타임아웃 관리 --- */
#define LED_TIMEOUT_MS   5000U
static uint32_t led_last_seen[LED_COUNT] = {0};
static uint8_t  led_active[LED_COUNT]    = {0};

/* --- 게이트웨이 연결 상태 관리 --- */
typedef enum {
    GW_STATE_INIT,
    GW_STATE_CONNECTED,
    GW_STATE_DISCONNECTED
} gw_state_t;

static gw_state_t gw_state = GW_STATE_INIT;
static uint32_t   last_health_check_tick   = 0;
static uint32_t   last_reconnect_attempt   = 0;

#define HEALTH_CHECK_INTERVAL_MS     1000U
#define RECONNECT_RETRY_INTERVAL_MS  5000U

/* 오프라인 버퍼 코드
#define OFFLINE_BUF_SIZE 20
static ble_msg_t offline_buf[OFFLINE_BUF_SIZE];
static uint8_t   offline_buf_head = 0;
static uint8_t   offline_buf_count = 0;
*/

/* USER CODE END PV */

/* Private function prototypes -----------------------------------------------*/
void SystemClock_Config(void);
void PeriphCommonClock_Config(void);
static void MX_GPIO_Init(void);
static void MX_LPUART1_UART_Init(void);
static void MX_SPI3_Init(void);
static void MX_USART1_UART_Init(void);
static void MX_IWDG_Init(void);
/* USER CODE BEGIN PFP */
extern int W5500_Init(void);
extern int mqtt_network_init(void);
extern int mqtt_connect_broker(void);
extern void mqtt_publish(char *topic, const char* payload);
extern void mqtt_yield(void);

extern int ble_queue_pop(ble_msg_t *msg);

int _write(int file, char *ptr, int len)
{
    (void)file;
    HAL_UART_Transmit(&huart1, (uint8_t*)ptr, len, HAL_MAX_DELAY);
    return len;
}
extern int mqtt_is_connected(void);

static void check_led_timeouts(void);
static void handle_ble_messages(void);
static void gateway_health_check(void);
static void gateway_try_reconnect(void);

/* 오프라인 버퍼 코드 주석
static void offline_buf_push(ble_msg_t *msg);
static int  offline_buf_pop(ble_msg_t *out);
static void flush_offline_buffer(void);
*/
static void publish_sensor_data(ble_msg_t *msg);

/* USER CODE END PFP */

/* Private user code ---------------------------------------------------------*/
/* USER CODE BEGIN 0 */

/* USER CODE END 0 */

/**
  * @brief  The application entry point.
  * @retval int
  */
int main(void)
{

  /* USER CODE BEGIN 1 */

  /* USER CODE END 1 */

  /* MCU Configuration--------------------------------------------------------*/

  /* Reset of all peripherals, Initializes the Flash interface and the Systick. */
  HAL_Init();
  /* Config code for STM32_WPAN (HSE Tuning must be done before system clock configuration) */
  MX_APPE_Config();

  /* USER CODE BEGIN Init */

  /* USER CODE END Init */

  /* Configure the system clock */
  SystemClock_Config();

  /* Configure the peripherals common clocks */
  PeriphCommonClock_Config();

  /* USER CODE BEGIN SysInit */

  /* USER CODE END SysInit */

  /* Initialize all configured peripherals */
  MX_GPIO_Init();
  MX_ICACHE_Init();
  MX_LPUART1_UART_Init();
  MX_RNG_Init();
  MX_RAMCFG_Init();
  MX_RTC_Init();
  MX_SPI3_Init();
  MX_USART1_UART_Init();
  MX_IWDG_Init();
  /* USER CODE BEGIN 2 */
  if (W5500_Init() == 0 && mqtt_network_init() == 0 && mqtt_connect_broker() == 0)
  {
      mqtt_ready = 1;
  }
  /* USER CODE END 2 */

  /* Init code for STM32_WPAN */
  MX_APPE_Init(NULL);

  /* Infinite loop */
  /* USER CODE BEGIN WHILE */
  apa102_reset(led_state, LED_COUNT);

  if (mqtt_ready)
  {
      gw_state = GW_STATE_CONNECTED;
  }
  else
  {
      gw_state = GW_STATE_DISCONNECTED;
  }

  while (1)
  {
    /* USER CODE END WHILE */
	HAL_IWDG_Refresh(&hiwdg);
    MX_APPE_Process();

    /* USER CODE BEGIN 3 */
    gateway_health_check();     /* 1초 주기: 링크/MQTT 상태 갱신 */
    gateway_try_reconnect();    /* 끊겼으면 5초 주기로 재연결 시도 */

    handle_ble_messages();      /* BLE 큐 비우기 + LED + publish */
    check_led_timeouts();       /* 5초 이상 조용한 LED 소등 */

    if (gw_state == GW_STATE_CONNECTED)
    {
        mqtt_yield();
    }
  }
  /* USER CODE END 3 */
}

/**
  * @brief System Clock Configuration
  * @retval None
  */
void SystemClock_Config(void)
{
  RCC_OscInitTypeDef RCC_OscInitStruct = {0};
  RCC_ClkInitTypeDef RCC_ClkInitStruct = {0};

  /** Configure the main internal regulator output voltage
  */
  if (HAL_PWREx_ControlVoltageScaling(PWR_REGULATOR_VOLTAGE_SCALE1) != HAL_OK)
  {
    Error_Handler();
  }

  /** Configure LSE Drive Capability
  */
  HAL_PWR_EnableBkUpAccess();
  __HAL_RCC_LSEDRIVE_CONFIG(RCC_LSEDRIVE_MEDIUMLOW);

  /** Initializes the CPU, AHB and APB busses clocks
  */
  RCC_OscInitStruct.OscillatorType = RCC_OSCILLATORTYPE_HSI|RCC_OSCILLATORTYPE_LSI
                              |RCC_OSCILLATORTYPE_HSE|RCC_OSCILLATORTYPE_LSE;
  RCC_OscInitStruct.HSEState = RCC_HSE_ON;
  RCC_OscInitStruct.HSEDiv = RCC_HSE_DIV1;
  RCC_OscInitStruct.LSEState = RCC_LSE_ON;
  RCC_OscInitStruct.HSIState = RCC_HSI_ON;
  RCC_OscInitStruct.HSICalibrationValue = RCC_HSICALIBRATION_DEFAULT;
  RCC_OscInitStruct.LSIState = RCC_LSI1_ON;
  RCC_OscInitStruct.LSIDiv = RCC_LSI_DIV1;
  RCC_OscInitStruct.PLL1.PLLState = RCC_PLL_ON;
  RCC_OscInitStruct.PLL1.PLLSource = RCC_PLLSOURCE_HSE;
  RCC_OscInitStruct.PLL1.PLLM = 1;
  RCC_OscInitStruct.PLL1.PLLN = 9;
  RCC_OscInitStruct.PLL1.PLLP = 2;
  RCC_OscInitStruct.PLL1.PLLQ = 2;
  RCC_OscInitStruct.PLL1.PLLR = 2;
  RCC_OscInitStruct.PLL1.PLLFractional = 0;
  if (HAL_RCC_OscConfig(&RCC_OscInitStruct) != HAL_OK)
  {
    Error_Handler();
  }

  /** Initializes the CPU, AHB and APB busses clocks
  */
  RCC_ClkInitStruct.ClockType = RCC_CLOCKTYPE_HCLK|RCC_CLOCKTYPE_SYSCLK
                              |RCC_CLOCKTYPE_PCLK1|RCC_CLOCKTYPE_PCLK2
                              |RCC_CLOCKTYPE_PCLK7|RCC_CLOCKTYPE_HCLK5;
  RCC_ClkInitStruct.SYSCLKSource = RCC_SYSCLKSOURCE_PLLCLK;
  RCC_ClkInitStruct.AHBCLKDivider = RCC_SYSCLK_DIV1;
  RCC_ClkInitStruct.APB1CLKDivider = RCC_HCLK_DIV1;
  RCC_ClkInitStruct.APB2CLKDivider = RCC_HCLK_DIV2;
  RCC_ClkInitStruct.APB7CLKDivider = RCC_HCLK_DIV1;
  RCC_ClkInitStruct.AHB5_PLL1_CLKDivider = RCC_SYSCLK_PLL1_DIV3;
  RCC_ClkInitStruct.AHB5_HSEHSI_CLKDivider = RCC_SYSCLK_HSEHSI_DIV1;

  if (HAL_RCC_ClockConfig(&RCC_ClkInitStruct, FLASH_LATENCY_2) != HAL_OK)
  {
    Error_Handler();
  }

   /* Select SysTick source clock */
  HAL_SYSTICK_CLKSourceConfig(SYSTICK_CLKSOURCE_LSE);

   /* Re-Initialize Tick with new clock source */
  if (HAL_InitTick(TICK_INT_PRIORITY) != HAL_OK)
  {
    Error_Handler();
  }
}

/**
  * @brief Peripherals Common Clock Configuration
  * @retval None
  */
void PeriphCommonClock_Config(void)
{
  RCC_PeriphCLKInitTypeDef PeriphClkInit = {0};

  /** Initializes the peripherals clock
  */
  PeriphClkInit.PeriphClockSelection = RCC_PERIPHCLK_RADIOST;
  PeriphClkInit.RadioSlpTimClockSelection = RCC_RADIOSTCLKSOURCE_LSE;

  if (HAL_RCCEx_PeriphCLKConfig(&PeriphClkInit) != HAL_OK)
  {
    Error_Handler();
  }
}

/**
  * @brief ICACHE Initialization Function
  * @param None
  * @retval None
  */
void MX_ICACHE_Init(void)
{

  /* USER CODE BEGIN ICACHE_Init 0 */

  /* USER CODE END ICACHE_Init 0 */

  /* USER CODE BEGIN ICACHE_Init 1 */

  /* USER CODE END ICACHE_Init 1 */

  /** Full retention for ICACHE in stop mode
  */
  LL_PWR_SetICacheRAMStopRetention(LL_PWR_ICACHERAM_STOP_FULL_RETENTION);

  /** Enable instruction cache in 1-way (direct mapped cache)
  */
  LL_ICACHE_SetMode(LL_ICACHE_1WAY);
  LL_ICACHE_Enable();
  /* USER CODE BEGIN ICACHE_Init 2 */

  /* USER CODE END ICACHE_Init 2 */

}

/**
  * @brief IWDG Initialization Function
  * @param None
  * @retval None
  */
static void MX_IWDG_Init(void)
{

  /* USER CODE BEGIN IWDG_Init 0 */

  /* USER CODE END IWDG_Init 0 */

  /* USER CODE BEGIN IWDG_Init 1 */

  /* USER CODE END IWDG_Init 1 */
  hiwdg.Instance = IWDG;
  hiwdg.Init.Prescaler = IWDG_PRESCALER_256;
  hiwdg.Init.Window = 4095;
  hiwdg.Init.Reload = 1874;
  hiwdg.Init.EWI = 0;
  if (HAL_IWDG_Init(&hiwdg) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN IWDG_Init 2 */

  /* USER CODE END IWDG_Init 2 */

}

/**
  * @brief LPUART1 Initialization Function
  * @param None
  * @retval None
  */
static void MX_LPUART1_UART_Init(void)
{

  /* USER CODE BEGIN LPUART1_Init 0 */

  /* USER CODE END LPUART1_Init 0 */

  /* USER CODE BEGIN LPUART1_Init 1 */

  /* USER CODE END LPUART1_Init 1 */
  hlpuart1.Instance = LPUART1;
  hlpuart1.Init.BaudRate = 209700;
  hlpuart1.Init.WordLength = UART_WORDLENGTH_8B;
  hlpuart1.Init.StopBits = UART_STOPBITS_1;
  hlpuart1.Init.Parity = UART_PARITY_NONE;
  hlpuart1.Init.Mode = UART_MODE_TX_RX;
  hlpuart1.Init.HwFlowCtl = UART_HWCONTROL_NONE;
  hlpuart1.Init.OneBitSampling = UART_ONE_BIT_SAMPLE_DISABLE;
  hlpuart1.Init.ClockPrescaler = UART_PRESCALER_DIV1;
  hlpuart1.AdvancedInit.AdvFeatureInit = UART_ADVFEATURE_NO_INIT;
  hlpuart1.FifoMode = UART_FIFOMODE_DISABLE;
  if (HAL_UART_Init(&hlpuart1) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_SetTxFifoThreshold(&hlpuart1, UART_TXFIFO_THRESHOLD_1_8) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_SetRxFifoThreshold(&hlpuart1, UART_RXFIFO_THRESHOLD_1_8) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_DisableFifoMode(&hlpuart1) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN LPUART1_Init 2 */

  /* USER CODE END LPUART1_Init 2 */

}

/**
  * @brief USART1 Initialization Function
  * @param None
  * @retval None
  */
static void MX_USART1_UART_Init(void)
{

  /* USER CODE BEGIN USART1_Init 0 */

  /* USER CODE END USART1_Init 0 */

  /* USER CODE BEGIN USART1_Init 1 */

  /* USER CODE END USART1_Init 1 */
  huart1.Instance = USART1;
  huart1.Init.BaudRate = 115200;
  huart1.Init.WordLength = UART_WORDLENGTH_8B;
  huart1.Init.StopBits = UART_STOPBITS_1;
  huart1.Init.Parity = UART_PARITY_NONE;
  huart1.Init.Mode = UART_MODE_TX_RX;
  huart1.Init.HwFlowCtl = UART_HWCONTROL_NONE;
  huart1.Init.OverSampling = UART_OVERSAMPLING_16;
  huart1.Init.OneBitSampling = UART_ONE_BIT_SAMPLE_DISABLE;
  huart1.Init.ClockPrescaler = UART_PRESCALER_DIV1;
  huart1.AdvancedInit.AdvFeatureInit = UART_ADVFEATURE_NO_INIT;
  if (HAL_UART_Init(&huart1) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_SetTxFifoThreshold(&huart1, UART_TXFIFO_THRESHOLD_1_8) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_SetRxFifoThreshold(&huart1, UART_RXFIFO_THRESHOLD_1_8) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_UARTEx_DisableFifoMode(&huart1) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN USART1_Init 2 */

  /* USER CODE END USART1_Init 2 */

}

/**
  * @brief RAMCFG Initialization Function
  * @param None
  * @retval None
  */
void MX_RAMCFG_Init(void)
{

  /* USER CODE BEGIN RAMCFG_Init 0 */

  /* USER CODE END RAMCFG_Init 0 */

  /* USER CODE BEGIN RAMCFG_Init 1 */

  /* USER CODE END RAMCFG_Init 1 */

  /** Initialize RAMCFG SRAM1
  */
  hramcfg_SRAM1.Instance = RAMCFG_SRAM1;
  if (HAL_RAMCFG_Init(&hramcfg_SRAM1) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_RAMCFG_ConfigWaitState(&hramcfg_SRAM1, RAMCFG_WAITSTATE_0) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN RAMCFG_Init 2 */

  /* USER CODE END RAMCFG_Init 2 */

}

/**
  * @brief RNG Initialization Function
  * @param None
  * @retval None
  */
void MX_RNG_Init(void)
{

  /* USER CODE BEGIN RNG_Init 0 */

  /* USER CODE END RNG_Init 0 */

  /* USER CODE BEGIN RNG_Init 1 */

  /* USER CODE END RNG_Init 1 */
  hrng.Instance = RNG;
  hrng.Init.ClockErrorDetection = RNG_CED_DISABLE;
  if (HAL_RNG_Init(&hrng) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN RNG_Init 2 */

  /* USER CODE END RNG_Init 2 */

}

/**
  * @brief RTC Initialization Function
  * @param None
  * @retval None
  */
void MX_RTC_Init(void)
{

  /* USER CODE BEGIN RTC_Init 0 */

  /* USER CODE END RTC_Init 0 */

  RTC_PrivilegeStateTypeDef privilegeState = {0};
  RTC_AlarmTypeDef sAlarm = {0};

  /* USER CODE BEGIN RTC_Init 1 */

  /* USER CODE END RTC_Init 1 */

  /** Initialize RTC Only
  */
  hrtc.Instance = RTC;
  hrtc.Init.AsynchPrediv = 31;
  hrtc.Init.OutPut = RTC_OUTPUT_DISABLE;
  hrtc.Init.OutPutRemap = RTC_OUTPUT_REMAP_NONE;
  hrtc.Init.OutPutPolarity = RTC_OUTPUT_POLARITY_HIGH;
  hrtc.Init.OutPutType = RTC_OUTPUT_TYPE_OPENDRAIN;
  hrtc.Init.OutPutPullUp = RTC_OUTPUT_PULLUP_NONE;
  hrtc.Init.BinMode = RTC_BINARY_ONLY;
  if (HAL_RTC_Init(&hrtc) != HAL_OK)
  {
    Error_Handler();
  }
  privilegeState.rtcPrivilegeFull = RTC_PRIVILEGE_FULL_NO;
  privilegeState.backupRegisterPrivZone = RTC_PRIVILEGE_BKUP_ZONE_NONE;
  privilegeState.backupRegisterStartZone2 = RTC_BKP_DR0;
  privilegeState.backupRegisterStartZone3 = RTC_BKP_DR0;
  if (HAL_RTCEx_PrivilegeModeSet(&hrtc, &privilegeState) != HAL_OK)
  {
    Error_Handler();
  }

  /* USER CODE BEGIN Check_RTC_BKUP */

  /* USER CODE END Check_RTC_BKUP */

  /** Initialize RTC and set the Time and Date
  */
  if (HAL_RTCEx_SetSSRU_IT(&hrtc) != HAL_OK)
  {
    Error_Handler();
  }

  /** Enable the Alarm A
  */
  sAlarm.BinaryAutoClr = RTC_ALARMSUBSECONDBIN_AUTOCLR_NO;
  sAlarm.AlarmTime.SubSeconds = 0x0;
  sAlarm.AlarmMask = RTC_ALARMMASK_NONE;
  sAlarm.AlarmSubSecondMask = RTC_ALARMSUBSECONDBINMASK_NONE;
  sAlarm.Alarm = RTC_ALARM_A;
  if (HAL_RTC_SetAlarm_IT(&hrtc, &sAlarm, RTC_FORMAT_BCD) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN RTC_Init 2 */

  /* USER CODE END RTC_Init 2 */

}

/**
  * @brief SPI3 Initialization Function
  * @param None
  * @retval None
  */
static void MX_SPI3_Init(void)
{

  /* USER CODE BEGIN SPI3_Init 0 */

  /* USER CODE END SPI3_Init 0 */

  /* USER CODE BEGIN SPI3_Init 1 */

  /* USER CODE END SPI3_Init 1 */
  /* SPI3 parameter configuration*/
  hspi3.Instance = SPI3;
  hspi3.Init.Mode = SPI_MODE_MASTER;
  hspi3.Init.Direction = SPI_DIRECTION_2LINES;
  hspi3.Init.DataSize = SPI_DATASIZE_8BIT;
  hspi3.Init.CLKPolarity = SPI_POLARITY_LOW;
  hspi3.Init.CLKPhase = SPI_PHASE_1EDGE;
  hspi3.Init.NSS = SPI_NSS_SOFT;
  hspi3.Init.BaudRatePrescaler = SPI_BAUDRATEPRESCALER_256;
  hspi3.Init.FirstBit = SPI_FIRSTBIT_MSB;
  hspi3.Init.TIMode = SPI_TIMODE_DISABLE;
  hspi3.Init.CRCCalculation = SPI_CRCCALCULATION_DISABLE;
  hspi3.Init.CRCPolynomial = 0x7;
  hspi3.Init.NSSPMode = SPI_NSS_PULSE_DISABLE;
  hspi3.Init.NSSPolarity = SPI_NSS_POLARITY_LOW;
  hspi3.Init.FifoThreshold = SPI_FIFO_THRESHOLD_01DATA;
  hspi3.Init.MasterSSIdleness = SPI_MASTER_SS_IDLENESS_00CYCLE;
  hspi3.Init.MasterInterDataIdleness = SPI_MASTER_INTERDATA_IDLENESS_00CYCLE;
  hspi3.Init.MasterReceiverAutoSusp = SPI_MASTER_RX_AUTOSUSP_DISABLE;
  hspi3.Init.MasterKeepIOState = SPI_MASTER_KEEP_IO_STATE_DISABLE;
  hspi3.Init.IOSwap = SPI_IO_SWAP_DISABLE;
  hspi3.Init.ReadyMasterManagement = SPI_RDY_MASTER_MANAGEMENT_INTERNALLY;
  hspi3.Init.ReadyPolarity = SPI_RDY_POLARITY_HIGH;
  if (HAL_SPI_Init(&hspi3) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN SPI3_Init 2 */

  /* USER CODE END SPI3_Init 2 */

}

/**
  * @brief GPIO Initialization Function
  * @param None
  * @retval None
  */
static void MX_GPIO_Init(void)
{
  GPIO_InitTypeDef GPIO_InitStruct = {0};
  /* USER CODE BEGIN MX_GPIO_Init_1 */

  /* USER CODE END MX_GPIO_Init_1 */

  /* GPIO Ports Clock Enable */
  __HAL_RCC_GPIOB_CLK_ENABLE();
  __HAL_RCC_GPIOA_CLK_ENABLE();
  __HAL_RCC_GPIOC_CLK_ENABLE();

  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(GPIOA, RST_Pin|CS_Pin, GPIO_PIN_SET);

  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(GPIOB, LED_DATA_Pin|LED_SCK_Pin, GPIO_PIN_RESET);

  /*Configure GPIO pins : RST_Pin CS_Pin */
  GPIO_InitStruct.Pin = RST_Pin|CS_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_HIGH;
  HAL_GPIO_Init(GPIOA, &GPIO_InitStruct);

  /*Configure GPIO pins : LED_DATA_Pin LED_SCK_Pin */
  GPIO_InitStruct.Pin = LED_DATA_Pin|LED_SCK_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_HIGH;
  HAL_GPIO_Init(GPIOB, &GPIO_InitStruct);

  /* USER CODE BEGIN MX_GPIO_Init_2 */

  /* USER CODE END MX_GPIO_Init_2 */
}

/* USER CODE BEGIN 4 */

/* device_id별로 3초 이상 신호가 없으면 해당 LED를 끈다 */
static void check_led_timeouts(void)
{
    uint32_t now = HAL_GetTick();
    for (int i = 0; i < LED_COUNT; i++)
    {
        if (led_active[i] && (now - led_last_seen[i] > LED_TIMEOUT_MS))
        {
            led_active[i] = 0;
            apa102_notify_device(led_state, LED_COUNT, i, 0, 0, 0, 0);
            printf("[LED] dev=%d timeout -> OFF\r\n", i);
        }
    }
}

/* BLE 큐에 쌓인 메시지를 전부 비우고 publish + LED 갱신
   (큐를 한 개만 pop하면 처리 속도가 밀릴 때 큐가 계속 쌓이므로,
    루프 한 번에 전부 비워준다) */
static void handle_ble_messages(void)
{
    ble_msg_t msg;
    uint32_t now = HAL_GetTick();

    while (ble_queue_pop(&msg))
    {
        /* --- 최소한의 유효성 검사 --- */
        if (msg.device_id >= LED_COUNT)
        {
            printf("[WARN] invalid device_id=%d, drop\r\n", msg.device_id);
            continue;
        }

        /* --- LED: 꺼져 있을 때만 켜고, 이미 켜져 있으면 타임스탬프만 갱신 --- */
        led_last_seen[msg.device_id] = now;
        if (!led_active[msg.device_id])
        {
            led_active[msg.device_id] = 1;
            apa102_notify_device(led_state, LED_COUNT, msg.device_id, 31, 0, 1, 0);
        }

        /* --- MQTT publish는 연결되어 있을 때만 시도 --- */
        if (gw_state == GW_STATE_CONNECTED)
        {
        	publish_sensor_data(&msg);
        }
        else
        {
            //offline_buf_push(&msg);
            //printf("[BUFFER] dev=%d buffered (offline), count=%d\r\n",
            //       msg.device_id, offline_buf_count);
        }
    }
}

/* 1초마다 PHY 링크 + MQTT 연결 상태를 점검해서 gw_state 갱신 */
static void gateway_health_check(void)
{
    uint32_t now = HAL_GetTick();
    if (now - last_health_check_tick < HEALTH_CHECK_INTERVAL_MS)
    {
        return;
    }
    last_health_check_tick = now;

    if (wizphy_getphylink() == PHY_LINK_OFF)
    {
        if (gw_state == GW_STATE_CONNECTED)
        {
            printf("[HEALTH] PHY link DOWN detected\r\n");
        }
        gw_state = GW_STATE_DISCONNECTED;
        return;
    }

    if (!mqtt_is_connected())
    {
        if (gw_state == GW_STATE_CONNECTED)
        {
            printf("[HEALTH] MQTT disconnected\r\n");
        }
        gw_state = GW_STATE_DISCONNECTED;
        return;
    }

    gw_state = GW_STATE_CONNECTED;
}

/* 끊긴 상태일 때 일정 간격으로 재연결 시도 (너무 자주 시도해서 블로킹되지 않도록 throttle) */
static void gateway_try_reconnect(void)
{

	if (gw_state != GW_STATE_DISCONNECTED)
    {
        return;
    }

    uint32_t now = HAL_GetTick();
    if (now - last_reconnect_attempt < RECONNECT_RETRY_INTERVAL_MS)
    {
        return;
    }
    last_reconnect_attempt = now;

    if (wizphy_getphylink() == PHY_LINK_OFF)
    {
    	printf("[RECONNECT] PHY link still down, skip\r\n");
        return;
    }

    printf("[RECONNECT] attempting mqtt reconnect...\r\n");
    if (mqtt_network_init() == 0 && mqtt_connect_broker() == 0)
    {
        gw_state = GW_STATE_CONNECTED;
        printf("[RECONNECT] success\r\n");
        //flush_offline_buffer();   /* 추가: 끊긴 동안 쌓인 데이터 flush */
    }
    else
    {
        printf("[RECONNECT] failed, retry in %lums\r\n",
               (unsigned long)RECONNECT_RETRY_INTERVAL_MS);
    }
}

////끊긴 동안의 데이터를 작은 버퍼에 저장했다가, 재연결되면 flush
//static void offline_buf_push(ble_msg_t *msg)
//{
//    uint8_t tail = (offline_buf_head + offline_buf_count) % OFFLINE_BUF_SIZE;
//    offline_buf[tail] = *msg;
//
//    if (offline_buf_count < OFFLINE_BUF_SIZE)
//    {
//        offline_buf_count++;
//    }
//    else
//    {
//        /* 버퍼가 꽉 찼으면 가장 오래된 것부터 덮어씀 (drop-oldest) */
//        offline_buf_head = (offline_buf_head + 1) % OFFLINE_BUF_SIZE;
//    }
//}
//
//static int offline_buf_pop(ble_msg_t *out)
//{
//    if (offline_buf_count == 0) return 0;
//    *out = offline_buf[offline_buf_head];
//    offline_buf_head = (offline_buf_head + 1) % OFFLINE_BUF_SIZE;
//    offline_buf_count--;
//    return 1;
//}
//
////재연결 성공시 버퍼에 쌓인 걸 순서대로 publish하는 함수 호출
//static void flush_offline_buffer(void)
//{
//    ble_msg_t msg;
//    while (offline_buf_pop(&msg))
//    {
//        /* handle_ble_messages 안의 publish 로직과 동일한 걸
//           재사용하려면 publish 부분을 별도 함수로 빼는 게 좋음 */
//        publish_sensor_data(&msg);   /* 아래 방향 3에서 분리 제안 */
//    }
//}

static void publish_sensor_data(ble_msg_t *msg)
{
    int t1_int  = msg->temp1_x100 / 100;
    int t1_frac = (msg->temp1_x100 >= 0) ? (msg->temp1_x100 % 100) : ((-msg->temp1_x100) % 100);
    int t2_int  = msg->temp2_x100 / 100;
    int t2_frac = (msg->temp2_x100 >= 0) ? (msg->temp2_x100 % 100) : ((-msg->temp2_x100) % 100);
    int ct_int  = msg->core_temp_x100 / 100;
    int ct_frac = (msg->core_temp_x100 >= 0) ? (msg->core_temp_x100 % 100) : ((-msg->core_temp_x100) % 100);

    uint16_t rms_x = (uint16_t)msg->angle_x_x100;
    uint16_t rms_y = (uint16_t)msg->angle_y_x100;
    uint16_t rms_z = (uint16_t)msg->angle_z_x100;

    char mqtt_buf[256];
    int n = snprintf(mqtt_buf, sizeof(mqtt_buf),
        "{"
        "\"gateway_id\":\"GW_LN01\","
        "\"esp_byte_id\":%d,"
        "\"temp1\":%d.%02d,"
        "\"temp2\":%d.%02d,"
        "\"core_temp\":%d.%02d,"
        "\"rms_x\":%u,"
        "\"rms_y\":%u,"
        "\"rms_z\":%u"
        "}",
        msg->device_id, t1_int, t1_frac, t2_int, t2_frac, ct_int, ct_frac,
        rms_x, rms_y, rms_z
    );

    if (n > 0 && (size_t)n < sizeof(mqtt_buf))
    {
        mqtt_publish("ingps/sensor", mqtt_buf);
    }
    else
        printf("[WARN] mqtt_buf truncated, skip publish dev=%d\r\n", msg->device_id);

}


/* USER CODE END 4 */

/**
  * @brief  This function is executed in case of error occurrence.
  * @param None
  * @retval None
  */
void Error_Handler(void)
{
  /* USER CODE BEGIN Error_Handler_Debug */
  /* User can add his own implementation to report the HAL error return state */
  __disable_irq();
  while (1)
  {
  }
  /* USER CODE END Error_Handler_Debug */
}
#ifdef USE_FULL_ASSERT
/**
  * @brief  Reports the name of the source file and the source line number
  *         where the assert_param error has occurred.
  * @param  file: pointer to the source file name
  * @param  line: assert_param error line source number
  * @retval None
  */
void assert_failed(uint8_t *file, uint32_t line)
{
  /* USER CODE BEGIN 6 */
  /* User can add his own implementation to report the file name and line number,
     ex: printf("Wrong parameters value: file %s on line %d\r\n", file, line) */
  /* USER CODE END 6 */
}
#endif /* USE_FULL_ASSERT */
