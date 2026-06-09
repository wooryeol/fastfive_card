# FastFive — 성능 개선 보고서

## 개요

FastFive는 기기 가속도 센서(Accelerometer)를 활용해 공이 중력에 반응하며 움직이는 물리 시뮬레이션 Android 앱입니다.
초기 구현에서 발생한 애니메이션 끊김 문제를 분석하고, 아키텍처 개선을 통해 해결한 과정을 기록합니다.

---

## 문제 분석

### 이전 코드 구조

```
센서 이벤트 발생 (~50Hz, 불규칙)
    → StateFlow 업데이트
        → Composable recomposition 트리거
            → 물리 시뮬레이션 계산 (Composable 내부 실행)
                → Canvas 렌더링
```

### 근본 원인

물리 시뮬레이션 로직이 Composable 함수 내부에서 직접 실행되고 있었습니다.

```kotlin
// ❌ 이전 코드 — Composable 안에서 물리 계산 실행 (side effect)
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

이 구조의 문제점:

| 문제 | 설명 |
|---|---|
| 비결정적 실행 타이밍 | 센서 이벤트는 ~50Hz로 불규칙하게 발생 → 프레임 간격이 일정하지 않음 |
| UI 스레드 블로킹 | recomposition 중 O(n²) 충돌 계산이 메인 스레드를 점유 |
| Composable 내 side effect | 상태 변경이 composition 단계에서 발생 — Compose 설계 원칙 위반 |
| 센서 폭주 시 recomposition 중첩 | 물리 계산이 누적되어 프레임 드랍 심화 |

---

## 개선 방향

### 개선된 코드 구조

```
[물리 게임 루프] Dispatchers.Main 코루틴 (고정 16ms 주기)
    → 물리 계산 실행
        → Ball 위치 상태 업데이트 (SnapshotStateList)

[센서 이벤트] 센서 스레드
    → tiltX / tiltY 값만 업데이트

[Compose] 프레임마다
    → Ball 위치 읽기
        → Canvas 렌더링만 담당
```

### 핵심 변경 — PhysicsViewModel

```kotlin
// ✅ 개선 코드 — ViewModel에서 독립적인 게임 루프 실행
class PhysicsViewModel(application: Application) : AndroidViewModel(application) {

    val balls = mutableStateListOf<Ball>()  // Compose가 관찰하는 상태

    private fun startGameLoop() {
        viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                if (containerWidth > 0f) updatePhysics()
                delay(16L)  // ~60fps 고정 주기
            }
        }
    }
}
```

### 핵심 변경 — Composable 단순화

```kotlin
// ✅ 개선 코드 — Composable은 렌더링만 담당
@Composable
fun TiltBallScreen(viewModel: PhysicsViewModel) {
    Canvas(modifier = Modifier.fillMaxSize()) {
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

### 주요 포인트

- **50th percentile 32ms → 16ms**: 이전 코드는 평균 프레임조차 60fps 기준(16.6ms)을 초과했습니다. 개선 후 평균 프레임이 60fps 타이밍에 정확히 맞춰집니다.
- **99th percentile 900ms → 32ms**: 이전 코드는 센서 이벤트 폭주 시 약 1초짜리 프레임 드랍이 발생했습니다. 이는 사용자 입장에서 앱이 순간적으로 멈추는 것처럼 느껴지는 수준입니다.
- **Janky frames (legacy) 82.60% → 2.97%**: 실제 사용자가 인지하는 끊김 빈도가 97% 감소했습니다.
- **총 프레임 2.4배 증가**: 게임 루프가 안정적으로 60fps를 유지하며 이전보다 2.4배 많은 프레임을 생성합니다.

---

## 파일 구조 변경

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

## 기술 스택

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Architecture**: MVVM
- **Concurrency**: Kotlin Coroutines (`viewModelScope`, `Dispatchers.Main`)
- **Sensor**: Android SensorManager (TYPE_ACCELEROMETER)
- **Performance Measurement**: `adb shell dumpsys gfxinfo`
