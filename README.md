# POSRouter Android Demo

Reference app for **partners integrating the POSRouter SDK** — ordering UI, Connect, and Pay (local or cross-network via NATS).

| | |
|---|---|
| Package | `com.posrouter.demo` |
| Module | `:app` |

Production payment terminal (B-side) is **[posrouter-kiosk](../posrouter-kiosk)**.

## Configuration

Copy `local.properties.example` to `local.properties`:

```properties
PARTICIPANT_KEY=...
TERMINAL_ID=TID001
EZYPOS_MID=1FRD9Z
```

Use the **same `TERMINAL_ID`** as the kiosk terminal on the same lane.

## SDK integration (AAR or composite)

Partners use the **AAR** bundled under `app/libs/posrouter-release.aar` (default).

| `useCompositeSdk` | Source | Who |
|---|---|---|
| `false` (default) | `app/libs/posrouter-release.aar` | Partners / release builds |
| `true` | `includeBuild("../sdk-android")` | Internal SDK development |

Set in `gradle.properties` or override in `local.properties`:

```properties
useCompositeSdk=false
```

When using the AAR, Gradle also adds SDK runtime dependencies (`jnats`, `kotlinx-coroutines-android`). No Java/Kotlin API changes are required.

### Refresh the AAR (POSRouter maintainers)

```bash
cd ../sdk-android
./gradlew :posrouter:assembleRelease
cp posrouter/build/outputs/aar/posrouter-release.aar ../demo-android/app/libs/
```

## Build & install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Typical integration flow (shown in code)

1. `POSRouter.initialize(context, config)`
2. `POSRouter.connect()` — Gateway/NATS (+ local Ezypos connect when applicable)
3. `POSRouter.pay(request, callback)` — local acquirer or NATS to terminal
4. Handle `gomenu://pay_result` callback / await pay callback for final status

See `DemoConfig.kt` and `MainActivity.kt`.
