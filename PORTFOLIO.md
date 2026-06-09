# FastFive — Android 물리 시뮬레이션 앱 포트폴리오

## 프로젝트 개요

기기의 가속도 센서(Accelerometer)를 활용해 공이 중력에 반응하며 실시간으로 움직이는 물리 시뮬레이션 Android 앱입니다.  
Jetpack Compose 기반의 MVVM 아키텍처로 구현했으며, 초기 구현의 성능 문제를 분석하고 아키텍처 개선을 통해 해결했습니다.

---

## 기술 스택

| 분류 | 기술 |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Architecture | MVVM |
| Concurrency | Kotlin Coroutines (`LaunchedEffect`, `withFrameNanos`) |
| Sensor | Android SensorManager (TYPE_ACCELEROMETER, SENSOR_DELAY_GAME) |
| Performance | `adb shell dumpsys gfxinfo` |

---

## 초기 구현 — 문제 분석

### 구조

```
센서 이벤트 발생 (~50Hz, 불규칙)
    → StateFlow 업데이트
        → Composable recomposition 트리거
            → 물리 시뮬레이션 계산 (Composable 내부 실행)
                → Canvas 렌더링
```

### 문제 코드

```kotlin
// ❌ Composable 안에서 물리 계산 실행 (side effect)
@Composable
fun TiltBallContent(tiltX: Float, tiltY: Float, ...) {
    BoxWithConstraints(...) {
        // 센서값이 바뀔 때마다 recomposition → 물리 계산이 여기서 실행됨
        repeat(subSteps) {
            balls.forEach { ball ->
                ball.velocityX += tiltX * sensitivity / subSteps
                // ...
            }
        }
        Canvas(...) { /* 렌더링 */ }
    }
}
```

### 문제점 요약

| 문제 | 설명 |
|---|---|
| 비결정적 실행 타이밍 | 센서 이벤트는 ~50Hz로 불규칙하게 발생 → 프레임 간격이 일정하지 않음 |
| UI 스레드 블로킹 | recomposition 중 O(n²) 충돌 계산이 메인 스레드를 점유 |
| Composable 내 side effect | 상태 변경이 composition 단계에서 발생 — Compose 설계 원칙 위반 |
| 센서 폭주 시 recomposition 중첩 | 물리 계산이 누적되어 프레임 드랍 심화 |

---

## 개선 — 아키텍처 재설계

### 개선된 구조

```
[물리 게임 루프] LaunchedEffect + withFrameNanos (vsync 동기화)
    → 물리 계산 실행
        → Ball 위치 상태 업데이트 (SnapshotStateList)

[센서 이벤트] 센서 스레드
    → tiltX / tiltY 값만 업데이트 (@Volatile)

[Compose] vsync마다
    → Ball 위치 읽기
        → Canvas 렌더링만 담당
```

### 파일 구조 변경

```
Before                          After
──────────────────────────────  ──────────────────────────────
MainActivity.kt                 MainActivity.kt        (렌더링 + 게임 루프 트리거)
  ├── Ball (inner class)   →    Ball.kt                (모델 분리)
  ├── TiltBallScreen           PhysicsViewModel.kt    (물리 + 센서)
  ├── TiltBallContent
  └── [물리 시뮬레이션 로직]
SensorViewModel.kt         →   (PhysicsViewModel에 통합)
```

---

## 주요 개선 포인트

### 1. vsync 동기화 게임 루프 (`MainActivity.kt`)

`delay(16L)` 기반 고정 주기 루프는 OS 타이머 기준으로 실행되어 실제 화면 갱신(vsync)과 위상 차이가 생깁니다.  
`withFrameNanos`를 `LaunchedEffect` 안에서 사용하면 Compose의 `MonotonicFrameClock`에 동기화되어 렌더링 직전에 정확히 물리가 갱신됩니다.

```kotlin
// ❌ 이전 — OS 타이머 기준, vsync와 위상 차이 발생
viewModelScope.launch(Dispatchers.Main) {
    while (isActive) {
        updatePhysics()
        delay(16L)
    }
}

// ✅ 개선 — Compose 프레임 클럭에 동기화, 렌더링 직전에 정확히 1회 실행
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos {
            if (viewModel.containerWidth > 0f) viewModel.updatePhysics()
        }
    }
}
```

### 2. 스레드 안전성 (`@Volatile`)

센서 스레드에서 쓰이고 메인 스레드에서 읽히는 `tiltX` / `tiltY` 필드에 `@Volatile`을 적용해 가시성을 보장했습니다.

```kotlin
@Volatile private var tiltX = 0f
@Volatile private var tiltY = 0f

// 센서 스레드에서 저주파 필터(Low-pass filter) 적용 후 기록
override fun onSensorChanged(event: SensorEvent) {
    tiltX = tiltX + SENSOR_ALPHA * (event.values[0] - tiltX)
    tiltY = tiltY + SENSOR_ALPHA * (event.values[1] - tiltY)
}
```

### 3. 물리 로직 함수 분리 (`PhysicsViewModel.kt`)

하나의 함수에 섞여 있던 물리 로직을 역할별로 분리했습니다.

```kotlin
fun updatePhysics() {
    repeat(SUB_STEPS) {
        applyForces()
        repeat(COLLISION_ITERATIONS) {
            resolveBallCollisions()
            resolveWallCollisions()
        }
    }
}

private fun applyForces() { /* 가속도 적용, 속도 제한, 위치 이동 */ }
private fun resolveBallCollisions() { /* 공 간 충돌 감지 및 반발 */ }
private fun resolveWallCollisions() { /* 벽 충돌 및 반사 */ }
```

### 4. 물리 상수 튜닝 및 `companion object` 집중 관리

매직 넘버를 모두 `companion object`로 이름을 붙여 정의하고, 자연스러운 물리감을 위해 값을 조정했습니다.

| 상수 | 초기값 | 최종값 | 변경 이유 |
|---|---|---|---|
| `SENSITIVITY` | 45f | **60f** | 기울기가 속도에 더 빠르게 반영 |
| `MAX_SPEED` | 15f | **15f** | 유지 (과도한 속도 방지) |
| `DAMPING` | 0.995f | **0.992f** | 감쇠를 약간 높여 제어감 개선 |
| `SUB_STEPS` | 6 | **8** | 시뮬레이션 정밀도 향상 |
| `COLLISION_ITERATIONS` | 5 | **6** | 충돌 해소 정확도 향상 |
| `SEPARATION_STRENGTH` | 0.5f | **0.3f** | 충돌 시 튀는 정도 감소 |
| `WALL_BOUNCE` | -0.85f | **-0.72f** | 벽 반사 에너지 손실 증가 |
| `WALL_FRICTION` | 0.98f | **0.99f** | 벽 접촉 시 수직 속도 보존율 향상 |

```kotlin
companion object {
    private const val BALL_COUNT = 13
    private const val BALL_RADIUS = 60f
    private const val BALL_SPACING = 180f
    private const val DAMPING = 0.992f
    private const val SENSITIVITY = 60f
    private const val MAX_SPEED = 15f
    private const val SUB_STEPS = 8
    private const val COLLISION_ITERATIONS = 6
    private const val SEPARATION_STRENGTH = 0.3f
    private const val WALL_BOUNCE = -0.72f
    private const val WALL_FRICTION = 0.99f
    private const val SENSOR_ALPHA = 0.1f
}
```

### 5. Composable 단순화 (`MainActivity.kt`)

`BoxWithConstraints` + `LaunchedEffect` 조합을 제거하고, `onSizeChanged` Modifier 하나로 컨테이너 크기를 ViewModel에 전달했습니다.

```kotlin
// ✅ Composable은 렌더링만 담당
@Composable
fun TiltBallScreen(viewModel: PhysicsViewModel) {
    Canvas(
        modifier = Modifier
            .size(width = 300.dp, height = 400.dp)
            .onSizeChanged { size ->
                viewModel.setBounds(size.width.toFloat(), size.height.toFloat())
            }
    ) {
        viewModel.balls.forEach { ball ->
            // 위치를 읽어서 그리기만 함 — 계산 없음
            drawImage(...)
        }
    }
}
```

---

## 실측 성능 비교

> **측정 환경**: Android Emulator (sdk_gphone64_arm64), `adb shell dumpsys gfxinfo`, 각 30초 안정화 후 측정

### 프레임 시간 분포

| 지표 | 이전 코드 | 개선 코드 | 개선율 |
|---|---|---|---|
| **50th percentile** | 32ms | **16ms** | **50% 감소** |
| **90th percentile** | 150ms | **17ms** | **89% 감소** |
| **95th percentile** | 400ms | **17ms** | **96% 감소** |
| **99th percentile** | 900ms | **32ms** | **96% 감소** |

### 품질 지표

| 지표 | 이전 코드 | 개선 코드 | 개선율 |
|---|---|---|---|
| **총 렌더링 프레임 (30초)** | 500 | 1,213 | +143% |
| **Janky frames (legacy)** | 82.60% | **2.97%** | **97% 감소** |
| **Slow UI thread** | 284회 | 34회 | 88% 감소 |
| **Missed Vsync** | 36회 | 12회 | 67% 감소 |

### 분석

- **50th percentile 32ms → 16ms**: 이전 코드는 평균 프레임조차 60fps 기준(16.6ms)을 초과했습니다. 개선 후 평균 프레임이 60fps 타이밍에 정확히 맞춰집니다.
- **99th percentile 900ms → 32ms**: 이전 코드는 센서 이벤트 폭주 시 약 1초짜리 프레임 드랍이 발생했습니다. 사용자 입장에서 앱이 순간적으로 멈추는 것처럼 느껴지는 수준입니다.
- **Janky frames 82.60% → 2.97%**: 실제 사용자가 인지하는 끊김 빈도가 97% 감소했습니다.
- **총 프레임 2.4배 증가**: 게임 루프가 안정적으로 60fps를 유지하며 이전보다 2.4배 많은 프레임을 생성합니다.
