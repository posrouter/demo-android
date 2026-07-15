# Local Kiosk：Cancel 后停在「One moment…」、未回到伙伴 App

面向：**GoMenu**（及其他同机 `local_posrouter_kiosk` 集成方）  
对照：**POS Router Demo** 在同一路径下可正确收到 cancel 并结束订单。

---

## 1. 现象

同机使用 `RoutePreference.LOCAL_POSROUTER_KIOSK` 发起支付后，在 Kiosk **支付方式菜单**点 **Cancel**：

| 侧 | 表现 |
|----|------|
| Kiosk | 进入 **「One moment…」** 交移动画（表示已开始向伙伴回传） |
| GoMenu | 若未正确收尾 → **回不到前台 / loading 不关 / 订单挂起** |
| Demo | 能截获 cancel，订单正常结束 |

**结论：** Kiosk 多半已经发出回调；问题更可能在伙伴 App 对回跳 URI / SDK callback 的处理不完整，而不只是「onCancel 没发出来」。

---

## 2. Kiosk 实际回传内容

Cancel 后会向 `POSRouterConfig.callbackUrl`（必须是 `…://pay_result`）发起 `ACTION_VIEW`，形如：

```text
gomenu://pay_result?relay=kiosk&type=PAY&status=CANCEL&orderid=ORDER123&attemptid=...&amount=100&currency=NZD&message=...
```

| 参数 | 预期值 | 说明 |
|------|--------|------|
| `relay` | `kiosk` | 本地 Kiosk 回传（不是 Ezypos 直回） |
| `type` | `PAY` | 取消也是一笔支付业务结果，**不是** `CONNECT` |
| `status` | `CANCEL` | 不是 `SUCCESS` / `FAILED` |
| `orderid` | 发起 pay 时的订单号 | 须与 pending 一致 |

Kiosk 回传后会停在交移动画，**等待伙伴 App 被拉到前台**。若 GoMenu 没有消费这条 Intent / 没有回到前台，就会表现为「卡在 One moment…」。

---

## 3. 定位步骤（请先打日志）

在处理 `pay_result` 的 `onCreate` / `onNewIntent` **最前面**打日志。

### 3.1 是否收到 URI？

- **有 URI** → 问题在解析或 SDK / UI 收尾链  
- **没有 URI** → 查 Manifest `intent-filter`（`scheme` + `host=pay_result`）、`launchMode`、是否被其他 Activity 抢走  

### 3.2 参数是否完整？

确认同时存在：

- `relay=kiosk`
- `type=PAY`
- `status=CANCEL`
- `orderid` 与当前 pending 订单一致  

### 3.3 是否调用了 SDK？

本地 Kiosk 支付路径**必须**调用：

```kotlin
POSRouter.deliverAcquirerCallback(uri)
```

否则 `POSRouter.pay(...)` 登记的 pending callback **不会结束**（loading / 业务状态会一直挂住）。

### 3.4 是否只处理了成功？

常见坑：只处理 `status == SUCCESS`，把 `CANCEL` / `DECLINED` / `ERROR` 直接丢掉 → UI 不收尾，Kiosk 也回不来。

### 3.5 是否忽略了可选 hook？

用户取消时，SDK 可能：

1. 先调用 `onUserCancelled(result)`（`cancelReason ≈ user_cancel`）  
2. **仍会再调用一次** `onResult(result)`（兼容旧集成）  

若只在「成功」分支关 loading，或 `onUserCancelled` 空实现且 `onResult` 又提前 `return` 掉 `CANCELLED`，就会表现为卡住。

---

## 4. 推荐处理（与 Demo 对齐）

### 4.1 Activity 回跳入口

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handlePayResult(intent.data)
}

fun handlePayResult(uri: Uri?) {
    if (uri == null || !uri.host.equals("pay_result", ignoreCase = true)) return

    when (uri.getQueryParameter("type")?.uppercase()) {
        "CONNECT" -> {
            POSRouter.deliverAcquirerCallback(uri)
            // 关掉 connect loading
            return
        }
    }

    // Local kiosk：成功 / 取消 / 拒付 / 错误都走这里
    val result = POSRouter.deliverAcquirerCallback(uri)
    // deliver 会触发 pay() 时注册的 POSRouterCallback
    // 即使 result == null，也应打日志保留完整 URI

    // 避免同一 Intent 被重复消费
    setIntent(Intent(this, this::class.java))
}
```

> `singleTop` / `singleTask`：**必须在 `onNewIntent` 处理**，不能只写在 `onCreate`。

### 4.2 `pay()` 回调

```kotlin
POSRouter.pay(
    activity,
    request,
    object : POSRouterCallback {
        override fun onUserCancelled(result: PaymentResult) {
            // 关支付 loading、恢复菜单、清 pending order
            finishPaymentUi(result)
        }

        override fun onResult(result: PaymentResult) {
            // 若已在 onUserCancelled 收尾，这里跳过，避免弹两次
            if (result.status == PaymentStatus.CANCELLED) return
            finishPaymentUi(result)
        }

        override fun onError(error: POSRouterError) {
            finishPaymentUiError(error)
        }
    },
    routePreference = RoutePreference.LOCAL_POSROUTER_KIOSK
)
```

**`finishPaymentUi` 对 `CANCELLED` / `DECLINED` / `APPROVED` / `ERROR` 都要走**，至少：

- 关闭转圈 / 支付遮罩  
- 清理 pending order  
- 回到点餐页 / 将 Activity 带到前台  

---

## 5. 与 Ezypos 本机路径的差异

| | Ezypos 本机 | Local Kiosk |
|--|-------------|-------------|
| 回跳 | 常无 `relay=kiosk` | **有** `relay=kiosk` |
| Cancel 的 `status` | 视实现而定 | **`CANCEL`** |
| 是否必须 `deliverAcquirerCallback` | 要 | **要**（SDK `pay` 路径） |

旧逻辑若是：

- 「非 `SUCCESS` 就当异常丢弃」  
- 「看到 `relay=kiosk` 就忽略，只认 Ezypos」  

Cancel 会刚好踩中。

---

## 6. 自测清单

1. **Demo + 同机 Kiosk**：菜单 Cancel → Demo 日志出现 `onUserCancelled` / `Payment CANCELLED`，回到 Demo。  
2. **GoMenu**：同样操作，日志应出现完整  
   `gomenu://pay_result?...&relay=kiosk&type=PAY&status=CANCEL&orderid=...`  
3. 确认调用了 `POSRouter.deliverAcquirerCallback(uri)`。  
4. 确认对 `CANCELLED` 做了 UI 收尾后，GoMenu 回到前台，Kiosk「One moment…」被盖住或退到后台。  

### 若日志证明「根本没收到 URI」

再查：

- `callbackUrl` 是否与 Manifest 中的 scheme 一致（connect / charge 使用同一个）  
- Activity 是否 `exported=true`，且 `intent-filter` 含正确 `scheme` + `host=pay_result`  
- Android 11+ 对**回跳到本 App**一般不需要额外 `<queries>`；缺的是 **intent-filter / onNewIntent**  

---

## 7. 一句话

Kiosk 在菜单 Cancel 后会发送：

```text
type=PAY & status=CANCEL & relay=kiosk
```

GoMenu **必须** `POSRouter.deliverAcquirerCallback(uri)`，并在 `onUserCancelled` / `onResult(CANCELLED)` **关闭支付 UI 并回到前台**。  
停在「One moment…」通常表示伙伴侧没有吃完回调，而不是 Kiosk 没有发出 cancel。
