# POSRouter Android Demo

Reference app for **partners integrating the POSRouter SDK** — ordering UI, Connect, and Pay (local or cross-network via NATS).

| | |
|---|---|
| Package | `com.posrouter.demo` |
| Module | `:app` |

Production payment terminal (B-side) is **[posrouter-kiosk](../posrouter-kiosk)**.

## Configuration

Copy `local.properties.example` to `local.properties`.

### Participant identity (A-side)

| Field | Value | Purpose |
|-------|-------|---------|
| `PARTICIPANT_KEY` | **GPOS** alliance secret | Gateway `/init?code=GPOS` → NATS token `TOKEN_GPOS_*` |
| Code in app | `GPOS` (fixed in `DemoConfig`) | Ordering / initiator role |
| `acquirerCode` in app | `SUPY` (fixed) | Lensing subject rail `lensing.SUPY.{merchant}.{tid}.*` |

```properties
PARTICIPANT_KEY=<GPOS alliance key from Gateway / Portal>
TERMINAL_ID=TID001
EZYPOS_MID=1FRD9Z
```

Use the **same `TERMINAL_ID` and `EZYPOS_MID`** as **posrouter-kiosk** on the same lane. Terminal and merchant can also be changed in the in-app **Connect** dialog (saved in app preferences).

### Gateway: seed both participants

In Upstash (or `npm run seed:participant`), register **two** keys:

```redis
SET client:key:GPOS "<gpos-secret>"
SET client:key:SUPY "<supy-secret>"
```

demo-android uses the **GPOS** key only. posrouter-kiosk uses the **SUPY** key only.

## Connect options (in-app)

Tap **Connect** to open:

| Field | Description |
|-------|-------------|
| Terminal ID | Lensing namespace terminal (must match kiosk on the lane) |
| Merchant ID | `merchantId` segment on Lensing subjects |
| Route preference | `auto` · `local_first` · `remote_first` · `local_only` · `remote_only` |

Route preference applies to **connect**, **pay**, and **refund** until changed on the next Connect.

## SDK integration

Three ways to depend on the SDK (set in `gradle.properties` or override in `local.properties`):

| Mode | Flags | Source | Who |
|---|---|---|---|
| **AAR** (default) | `useCompositeSdk=false`, `useMavenSdk=false` | `app/libs/posrouter-release.aar` | Partners without GitHub access |
| **Maven** | `useMavenSdk=true` | GitHub Packages `com.posrouter:posrouter` | Partners with org/repo access |
| **Composite** | `useCompositeSdk=true` | `includeBuild("../sdk-android")` | POSRouter internal SDK dev |

Only one mode should be active. Composite takes precedence if both SDK flags are set.

### AAR mode (default)

Bundled file: `app/libs/posrouter-release.aar`. Gradle also adds runtime deps (`jnats`, `kotlinx-coroutines-android`).

Maintainers refresh the AAR:

```bash
cd ../sdk-android
./gradlew :posrouter:assembleRelease
cp posrouter/build/outputs/aar/posrouter-release.aar ../demo-android/app/libs/
```

### Maven mode (GitHub Packages)

The SDK is published to a **private** registry — not Maven Central. Download requires a GitHub account with access to `posrouter/sdk-android` and a Personal Access Token (PAT).

**1. Get access**

- Ask POSRouter to add your GitHub user to the `posrouter` org or `sdk-android` repo, **or**
- Receive an AAR build instead (no GitHub needed).

**2. Create a Classic PAT**

GitHub → Settings → Developer settings → Personal access tokens → **Generate new token (classic)**

Scopes:

- `read:packages` (required)
- `repo` (required if the repository is private)

**3. Add to `local.properties`**

```properties
useMavenSdk=true
useCompositeSdk=false
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=ghp_...
```

**4. Set SDK version** in `gradle.properties`:

```properties
posrouterVersion=1.6.3
```

**5. Build**

```bash
./gradlew :app:assembleDebug
```

This project already wires the GitHub Packages repository in `settings.gradle.kts` when `useMavenSdk=true`. In your own app, add the same repository block:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/posrouter/sdk-android")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.posrouter:posrouter:1.6.3")
}
```

POM includes transitive dependencies — no need to declare `jnats` or `coroutines` manually.

## Build & install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Typical integration flow (shown in code)

1. `POSRouter.initialize(context, config)`
2. `POSRouter.setTerminalListener(...)` — Lensing status dot / remote-pay gating (see below)
3. `POSRouter.connect()` — Gateway/Lensing (+ local Ezypos connect when applicable)
4. `POSRouter.pay(request, callback)` — local acquirer or Lensing to terminal
5. Optional: `POSRouter.voidPayment(orderId)` while pay is in flight — publishes `.void` to the terminal; pay callback completes with `CANCELLED` and `cancelReason=initiator_void`
6. Handle `gomenu://pay_result` callback / await pay callback for final status

See `DemoConfig.kt` and `MainActivity.kt`.

## Lensing connection status

The SDK exposes **Lensing** (Gateway / NATS) readiness — separate from local Ezypos.

**Push (UI updates):**

```kotlin
POSRouter.setTerminalListener(object : POSRouterTerminalListener {
    override fun onLensingStateChanged(state: LensingConnectionState) {
        lensingDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(POSRouter.lensingIndicatorColor(state))
        }
    }
})
```

**Pull (before pay / void):**

```kotlin
if (POSRouter.currentLensingState() != LensingConnectionState.CONNECTED) {
    showOfflineMessage()
    return
}
```

Canonical indicator colors are defined in the SDK (`LensingConnectionIndicator`) so demo, kiosk, and partner apps stay consistent.

Detailed guide: [../sdk-android/docs/integration-lensing-status.md](../sdk-android/docs/integration-lensing-status.md)

## SDK upgrade (1.6.3+)

If you integrated on SDK **≤ 1.0.2**, update code when replacing the AAR — not a drop-in swap:

| Old | New |
|---|---|
| `NatsConnectionState` | `LensingConnectionState` |
| `onNatsStateChanged` | `onLensingStateChanged` |
| `currentNatsState()` | `currentLensingState()` |

Migration checklist: [../sdk-android/docs/MIGRATION.md](../sdk-android/docs/MIGRATION.md)

Bump Maven / AAR to **1.6.3** or newer. Version scheme: [../sdk-android/docs/VERSIONING.md](../sdk-android/docs/VERSIONING.md).
