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
| Concurrency | Kotlin Coroutines (`viewModelScope`, `Dispatchers.Main`) |
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
[물리 게임 루프] Dispatchers.Main 코루틴 (고정 16ms 주기)
    → 물리 계산 실행
        → Ball 위치 상태 업데이트 (SnapshotStateList)

[센서 이벤트] 센서 스레드
    → tiltX / tiltY 값만 업데이트 (@Volatile)

[Compose] 프레임마다
    → Ball 위치 읽기
        → Canvas 렌더링만 담당
```

### 파일 구조 변경

```
Before                          After
──────────────────────────────  ──────────────────────────────
MainActivity.kt                 MainActivity.kt        (렌더링 전용)
  ├── Ball (inner class)   →    Ball.kt                (모델 분리)
  ├── TiltBallScreen           PhysicsViewModel.kt    (물리 + 센서)
  ├── TiltBallContent
  └── [물리 시뮬레이션 로직]
SensorViewModel.kt         →   (PhysicsViewModel에 통합)
```

---

## 주요 개선 포인트

### 1. 고정 주기 게임 루프 (`PhysicsViewModel.kt`)

센서 이벤트에 의존하던 불규칙한 업데이트를 `Dispatchers.Main` 코루틴 기반의 고정 16ms 게임 루프로 교체했습니다.

```kotlin
// ✅ ViewModel에서 독립적인 게임 루프 실행
private fun startGameLoop() {
    viewModelScope.launch(Dispatchers.Main) {
        while (isActive) {
            if (containerWidth > 0f) updatePhysics()
            delay(16L)  // ~60fps 고정 주기
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
private fun updatePhysics() {
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

### 4. 상수 집중 관리 (`companion object`)

매직 넘버를 모두 `companion object`로 이름을 붙여 정의했습니다.

```kotlin
companion object {
    private const val BALL_COUNT = 13
    private const val BALL_RADIUS = 60f
    private const val DAMPING = 0.995f
    private const val SENSITIVITY = 45f
    private const val MAX_SPEED = 15f
    private const val SUB_STEPS = 6
    private const val COLLISION_ITERATIONS = 5
    private const val SEPARATION_STRENGTH = 0.5f
    private const val WALL_BOUNCE = -0.85f
    private const val WALL_FRICTION = 0.98f
    private const val SENSOR_ALPHA = 0.1f
    private const val FRAME_DELAY_MS = 16L
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
