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
| Route preference | `auto` · `local_first` · `remote_first` · `local_only` · `local_posrouter_kiosk` · `remote_only` |

Route preference applies to **connect**, **pay**, and **refund** until changed on the next Connect. The kiosk launch demo buttons below force their own route (`remote_only` / `local_posrouter_kiosk`) and do not depend on this spinner.

## Kiosk launch demos (UI)

The deeper magenta **2×2** button block is one group:

| Button | Channel | How it starts the kiosk |
|--------|---------|-------------------------|
| **Remote Kiosk** | NATS / Lensing | `POSRouter.pay(..., method=selection, remote_only)` |
| **Local Kiosk (SDK)** | Same-device via SDK | `POSRouter.pay(..., method=selection, local_posrouter_kiosk)` — **recommended** |
| **Local Kiosk (Deep Link)** | Same-device URI | `startActivity(Intent(ACTION_VIEW, posrouter-kiosk://charge?…))` |
| **Local Kiosk (Intent)** | Same-device explicit | `Intent(ACTION_VIEW, chargeUri).setClassName(kioskPackage, KioskActivity)` |

No prior **Kiosk connect** step is required for local launches: the charge URI (or SDK) includes `callback_url` so the kiosk can relay the result back to this app.

If POSRouter Kiosk is **not installed**, the three Local Kiosk buttons are disabled. The SDK path returns `LOCAL_KIOSK_UNAVAILABLE` via `callback.onError` (does not crash). Check with `POSRouter.isLocalKioskAvailable(context)`.

### 1. Remote Kiosk (SDK + NATS)

```kotlin
POSRouter.pay(
    this,
    PaymentRequest(
        terminalId = terminalId,
        amount = totalCents,
        orderId = orderId,
        method = PaymentRequest.METHOD_SELECTION
    ),
    callback,
    routePreference = RoutePreference.REMOTE_ONLY
)
```

Requires Lensing **CONNECTED**. The remote terminal shows the payment-method picker.

### 2. Local Kiosk (SDK) — recommended

```kotlin
if (!POSRouter.isLocalKioskAvailable(this)) {
    // Handle missing kiosk — do not call pay
    return
}

POSRouter.pay(
    this,
    PaymentRequest(
        terminalId = terminalId,
        amount = totalCents,
        orderId = orderId,
        method = PaymentRequest.METHOD_SELECTION
    ),
    callback,
    routePreference = RoutePreference.LOCAL_POSROUTER_KIOSK
)
```

SDK builds `posrouter-kiosk://charge` (scheme overridable via `POSRouterConfig.localKioskScheme`) and delivers the result through the same `POSRouterCallback` when the partner `pay_result` URI is passed to `POSRouter.deliverAcquirerCallback`. If the kiosk is missing, `onError(LOCAL_KIOSK_UNAVAILABLE)` is invoked instead of throwing.

### 3. Local Kiosk (Deep Link)

```kotlin
val chargeUri = Uri.parse("posrouter-kiosk://charge").buildUpon()
    .appendQueryParameter("amount", totalCents.toString())
    .appendQueryParameter("currency", "NZD")
    .appendQueryParameter("orderid", orderId)
    .appendQueryParameter("method", "selection")
    .appendQueryParameter("callback_url", "posrouter-pos-demo-android://pay_result")
    .appendQueryParameter("partner_scheme", "posrouter-pos-demo-android")
    .build()

try {
    startActivity(Intent(Intent.ACTION_VIEW, chargeUri))
} catch (e: ActivityNotFoundException) {
    // Kiosk not installed
}
```

Demo helper: `DemoDeeplinks.buildKioskChargeUri(...)`.

### 4. Local Kiosk (Intent)

Same charge URI as Deep Link, but pinned to the kiosk component (works when multiple apps could handle the scheme):

```kotlin
val intent = Intent(Intent.ACTION_VIEW, chargeUri).apply {
    setClassName("com.posrouter.kiosk", "com.posrouter.kiosk.KioskActivity")
    addCategory(Intent.CATEGORY_DEFAULT)
    addCategory(Intent.CATEGORY_BROWSABLE)
}
try {
    startActivity(intent)
} catch (e: ActivityNotFoundException) {
    // Kiosk not installed
}
```

Result path for Deep Link / Intent: kiosk relays to `…://pay_result?relay=kiosk&…` → demo `handlePayResultIntent` → UI.  
Result path for SDK: same relay → `POSRouter.deliverAcquirerCallback` → pending `pay` callback.

See `MainActivity.onRemoteKiosk`, `onLocalKioskSdk`, `onLocalKioskDeepLink`, `onLocalKioskIntent`.

## VOID button (initiator cancel)

While a remote pay is in flight (e.g. after **Remote Kiosk**), the **VOID** button asks the terminal to abandon the attempt. Demo keeps `pendingOrderId` from `pay` and enables VOID until a final pay result arrives.

```kotlin
// After POSRouter.pay(...), keep the orderId
pendingOrderId = orderId
btnVoid.isEnabled = true

fun onVoidPayment() {
    val orderId = pendingOrderId ?: return
    if (POSRouter.currentLensingState() != LensingConnectionState.CONNECTED) {
        // Void needs NATS — Setup / wait until Connected
        return
    }
    if (POSRouter.voidPayment(orderId)) {
        // Soft void published; wait for the original pay callback
        voidInProgress = true
    } else {
        // No open local session for this order
    }
}
```

SDK behaviour (`POSRouter.voidPayment`):

1. Looks up the open pay attempt for `orderId` on this device  
2. Publishes a Lensing **void** subject to the terminal  
3. Does **not** call your callback immediately — the terminal soft-acks with a cancelled result  
4. Your original `POSRouter.pay` callback then receives:

```kotlin
result.status == PaymentStatus.CANCELLED
result.metadata["cancelReason"] == PaymentCancelReason.INITIATOR_VOID  // "initiator_void"
```

Demo handles that in `MainActivity.reportPayResult` / `isInitiatorVoidResult`: log “Void acknowledged”, clear the order, **skip** the pay result dialog (VOID is already an intentional staff action). See `MainActivity.onVoidPayment`.

> VOID is for **remote / Lensing** flows. Local Deeplink / Intent kiosk charges are not voided through this API.

## Remote terminal cancelled (user cancel)

When the **terminal** (or acquirer UI) cancels — customer taps Cancel on the kiosk method picker, or cancels in Ezypos/Skyzer — the SDK delivers the pay outcome to your `POSRouterCallback`.

### Recommended: optional typed hooks

You do **not** have to inspect `cancelReason` in `onResult`. Override the default no-op methods:

```kotlin
POSRouter.pay(activity, request, object : POSRouterCallback {
    override fun onUserCancelled(result: PaymentResult) {
        // Remote / acquirer user cancelled — keep cart or prompt retry
    }

    override fun onInitiatorVoided(result: PaymentResult) {
        // Staff VOID acknowledged (from POSRouter.voidPayment)
    }

    override fun onResult(result: PaymentResult) {
        // Still called for every outcome (including cancels) — approvals, declines, etc.
        if (result.status == PaymentStatus.APPROVED) { /* … */ }
        pendingOrderId = null
    }

    override fun onError(error: POSRouterError) {
        pendingOrderId = null
    }
}, routePreference = RoutePreference.REMOTE_ONLY)
```

Call order for a cancel: `onUserCancelled` / `onInitiatorVoided` **then** `onResult` (same `PaymentResult`). Existing integrators that only implement `onResult` keep working.

### Equivalent payload (if you still parse in `onResult`)

```kotlin
result.status == PaymentStatus.CANCELLED
result.metadata["cancelReason"] == PaymentCancelReason.USER_CANCEL  // "user_cancel"
// or PaymentCancelReason.INITIATOR_VOID for VOID ack
```

Demo logs the optional hooks in `MainActivity.routerCallback`, and still uses `reportPayResult` / `showPayResultDialog` from `onResult`:

| `status` | `cancelReason` | Banner title |
|----------|----------------|--------------|
| `CANCELLED` | `initiator_void` | Payment voided (dialog skipped for VOID ack) |
| `CANCELLED` | `user_cancel` / other | Payment cancelled |
| `APPROVED` / `DECLINED` / `ERROR` | — | as usual |

Constants: SDK `PaymentCancelReason`.

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
5. Optional: **VOID** — `POSRouter.voidPayment(orderId)` while pay is in flight (see [VOID button](#void-button-initiator-cancel))
6. Await the same `pay` callback for terminal outcomes, including remote user cancel (`cancelReason=user_cancel` — see [Remote terminal cancelled](#remote-terminal-cancelled-user-cancel))
7. For local kiosk deeplink/Intent: handle partner `…://pay_result` (and `POSRouter.deliverAcquirerCallback` when using the SDK path)

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
