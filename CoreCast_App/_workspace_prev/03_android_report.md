# 앱 구현 보고: AI 예측 온도 카드에 `core_temp` 연결

작성: ingps-android-engineer / 2026-08-31 (재작성)
입력: `_workspace/01b_scope_correction.md`

> **이 문서는 이전 "BLE 직접 스캔" 버전 보고서를 전량 대체한다.** 해당 구현은 아키텍처
> 오판으로 되돌려졌고(`git checkout` + 신규 파일 삭제), `com/example/in_gps/ble/` 패키지가
> 존재하지 않음을 확인했다. 이번 작업은 그것과 무관한, **기존 Retrofit REST 파이프라인에
> 필드 하나를 추가로 수신해 기존 죽은 뷰에 연결**하는 최소 변경이다.

## 변경 파일

| 파일 | 계층 | 변경 내용 |
|------|------|----------|
| `app/src/main/java/com/example/in_gps/model/TemperatureModel.java` | 모델(DTO) | `@SerializedName("core_temp") public Float coreTemp;` 필드 추가 (wrapper `Float`) |
| `app/src/main/java/com/example/in_gps/fragment/SensorDetailFragment.java` | Fragment(View) | `tvAiTemp` 필드 선언 + `R.id.tv_ai_temp` 바인딩 + `getTemperatureData()` 콜백에 null 분기 렌더링 |
| `app/src/main/res/layout/fragment_sensor_detail.xml` | 리소스 | (선택 항목) 카드 제목 `"AI 예측 온도"` → `"AI 예측 온도 (추정치)"` |

### 3번째 파일(layout)을 건드린 이유

지시서의 "필수 아님" 선택 항목(§3 UI 라벨)을 수행한 것이다. 이 값은 벤치 데이터로 학습된
모델의 추정치이고 현 하드웨어에서 정확도가 검증되지 않았으므로, 실측 온도 카드
(표면/외부)와 시각적으로 동일한 위계로 표시되면 사용자가 실측으로 오인할 여지가 있다.
**기존 TextView의 `android:text` 한 곳만 수정**했고 뷰 추가·ID 변경·레이아웃 구조 변경은
없다. 불필요하다고 판단되면 이 한 줄만 되돌리면 되며 Java 코드에 영향이 없다.

`ApiService`, `RetrofitClient`, `TemperatureRepository`, `SensorDetailViewModel`,
`chip_ai_status`/`chipEventStatus` 로직, MPAndroidChart 관련 코드는 **한 줄도 건드리지
않았다.**

## API 연동

| 엔드포인트 | ApiService 메서드 | 응답 모델 클래스 | 필드 매핑 확인 |
|-----------|------------------|-----------------|---------------|
| `GET /temperature?device_id=&limit=1` | `getTemperature(deviceId, limit)` (기존, 무변경) | `TemperatureResponse.items[0]` → `TemperatureModel` | `core_temp` → `coreTemp` (`@SerializedName` 명시) |

Repository 경로 확인 결과, `TemperatureRepository.fetchLatest()`가
`response.body().items.get(0)`를 **가공 없이 그대로** `MutableLiveData<TemperatureModel>`에
`postValue`하고, `SensorDetailViewModel.getTemperatureData()`가 이를 그대로 노출한다.
중간에 필드를 복사하는 매핑 계층이 없으므로 **모델에 필드를 추가하는 것만으로 Fragment까지
값이 도달한다.** Repository/ViewModel 수정은 불필요했다.

## 모델 필드 매핑

### `TemperatureModel`

| 서버 필드 | 모델 필드 | @SerializedName | 타입 |
|----------|----------|-----------------|------|
| `core_temp` | `coreTemp` | `@SerializedName("core_temp")` | **`Float` (wrapper, nullable)** |

기존 필드(참고 — 무변경): `id`/`device_id`/`temp1`/`temp2`/`rms_x`/`rms_y`/`rms_z`/`event`/
`created_at`, 집계 전용 `temp1_max`/`temp2_max`/`temp1_min`/`temp2_min`.

## null 처리 확인 결과

이번 작업의 핵심 요구사항이며, 각 단계를 코드로 확인했다.

| 단계 | 처리 | 확인 방법 |
|------|------|----------|
| JSON `"core_temp": null` → Gson 역직렬화 | `coreTemp == null` 유지 | 필드 타입이 wrapper `Float`. primitive `float`였다면 Gson이 필드를 건드리지 않아 Java 기본값 `0.0f`가 남고 "0.0°C"로 렌더링됨 — **이 프로젝트 `temp1`/`temp2`가 실제로 걸려 있는 함정.** `coreTemp`는 처음부터 회피 |
| JSON에 `core_temp` 키 자체가 없을 때 (서버 배포 전) | `coreTemp == null` | 미지정 필드는 Gson이 초기화하지 않음 → 객체 필드 기본값 `null` |
| Repository/ViewModel 통과 | 참조 그대로 전달, null 소실·치환 없음 | `fetchLatest()`가 파싱된 인스턴스를 그대로 `postValue` |
| UI 렌더링 | `null` → `"측정 불가"`, 값 존재 → `"%.1f°C"` | Fragment 콜백에 명시적 `if (data.coreTemp != null)` 분기 |

렌더링 코드 (기존 `tvSurfaceTemp`/`tvExternalTemp`와 동일한 위치·스타일):

```java
viewModel.getTemperatureData().observe(getViewLifecycleOwner(), data -> {
    tvSurfaceTemp.setText(String.format(Locale.getDefault(), "%.1f°C", data.temp1));
    tvExternalTemp.setText(String.format(Locale.getDefault(), "%.1f°C", data.temp2));
    // AI 예측 코어 온도 — 서버가 null을 내려주면 값 없음으로 표시(0°C로 렌더링 금지)
    if (data.coreTemp != null) {
        tvAiTemp.setText(String.format(Locale.getDefault(), "%.1f°C", data.coreTemp));
    } else {
        tvAiTemp.setText("측정 불가");
    }
    updateEventChip(data.event);
});
```

`String.format`의 `%.1f`에 `Float` 객체를 넘기는 것은 안전하다(오토언박싱, 분기 안이라
NPE 불가). `Locale.getDefault()`와 `java.util.Locale` import는 기존 코드에 이미 존재하므로
신규 import는 없다.

## 차트 변경

**없음.** MPAndroidChart 관련 코드(`setupChart`, `RangeOverlay`, transformer, legend,
gesture listener)는 이번 스코프에서 전혀 수정하지 않았다. 따라서 기존 차트 제약
(software layer 금지 / 동일 X 두 entry 금지 / transformer는 layout 후 호출 /
오버레이 제스처 동기화 / legend 비활성)에 대한 신규 위험 없음.

## 디바이스 확인 필요 항목

- [ ] **서버가 실제로 `core_temp`를 내려주는지 (미검증)** — server-engineer의 서버 변경 +
      EC2 DDL(`temperature_log.core_temp FLOAT NULL`) 적용 + 서버 배포가 끝나야 확인 가능.
      확인 방법: `GET /temperature?device_id=esp_32_1&limit=1` 응답 JSON에 `core_temp` 키
      존재 여부. 키 자체가 없으면 앱은 조용히 "측정 불가"만 표시한다(크래시 없음).
- [ ] **값이 있을 때 정상 렌더링** — 센서 상세 화면 진입 후 AI 예측 온도 카드가
      `"41.2°C"` 형태로 갱신되는지. 5초 폴링마다 갱신되므로 값 변화도 따라와야 한다.
- [ ] **null일 때 "측정 불가" 표시 및 0°C로 새지 않는지** — 해당 디바이스의 `core_temp`가
      DB NULL인 레코드에서 확인. 이번 변경의 가장 중요한 검증 포인트.
- [ ] **"측정 불가" 텍스트 레이아웃** — `tv_ai_temp`는 32sp bold이고 `layout_weight="1"`로
      옆의 chip과 한 줄을 공유한다. 한글 4자가 chip을 밀어내거나 줄바꿈되지 않는지 육안 확인.
- [ ] **카드 제목 `"AI 예측 온도 (추정치)"` 줄바꿈 여부** — 12sp `wrap_content`라 문제
      가능성은 낮으나 좁은 화면에서 육안 확인 권장.
- [ ] **기존 표면/외부 온도, 이벤트 칩, 차트 회귀 없음** — 같은 콜백에 코드를 추가했으므로
      기존 3개 표시가 그대로 동작하는지 함께 확인.

빌드(Gradle)와 디바이스 실행은 사용자가 Android Studio에서 수행한다. 이 보고서 시점까지는
정적 검토만 완료된 상태이며, 위 항목은 모두 **미검증**이다.

## integration-qa 전달 사항

`core_temp` 키 이름 전 구간 일치 여부 교차검증 시, Android 쪽 기준점은 다음 한 곳이다:

- `TemperatureModel.coreTemp` — `@SerializedName("core_temp")`, 타입 `Float`

MQTT payload 키 → Pydantic DTO 필드 → DB 컬럼 → SELECT alias 가 모두 `core_temp`
(snake_case, alias 변형 없음)여야 한다. 서버가 어느 단계에서든 `coreTemp`/`core_temperature`
등으로 이름을 바꾸면 앱은 **컴파일 성공 + 크래시 없이 항상 "측정 불가"** 만 표시하므로
증상만으로는 원인 구분이 되지 않는다 — 응답 JSON 원문 확인이 필요하다.
