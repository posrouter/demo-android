package com.posrouter.demo

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.posrouter.LocalRouteMethod
import com.posrouter.LensingConnectionIndicator
import com.posrouter.LensingConnectionState
import com.posrouter.PaymentCancelReason
import com.posrouter.RoutePreference
import com.posrouter.POSRouter
import com.posrouter.POSRouterCallback
import com.posrouter.POSRouterError
import com.posrouter.POSRouterTerminalListener
import com.posrouter.PaymentRequest
import com.posrouter.PaymentResult
import com.posrouter.PaymentStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val orderItems = mutableListOf<Product>()
    private lateinit var orderSummary: TextView
    private lateinit var totalAmount: TextView
    private lateinit var sdkStatus: TextView
    private lateinit var lensingStatusLabel: TextView
    private lateinit var lensingStatusDot: View
    private lateinit var lensingStatusContainer: View
    private var lensingStatusLabelPinned = false
    private var appliedLensingState: LensingConnectionState? = null
    private var lensingDotPulseAnimator: ObjectAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLensingState: LensingConnectionState? = null
    private val applyLensingStatusRunnable = Runnable {
        val state = pendingLensingState ?: return@Runnable
        if (isFinishing || isDestroyed) return@Runnable
        applyLensingStatus(state)
    }
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnVoidPayment: MaterialButton
    private var orderCounter = 1
    private var pendingOrderId: String? = null
    private var voidInProgress = false
    private var isConnected = false
    private var awaitingKioskRelay = false
    private var pausedAtElapsedMs = 0L

    private val terminalListener = object : POSRouterTerminalListener {
        override fun onLensingStateChanged(state: LensingConnectionState) {
            updateLensingStatus(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState != null) {
            orderCounter = savedInstanceState.getInt(STATE_ORDER_COUNTER, 1)
            pendingOrderId = savedInstanceState.getString(STATE_PENDING_ORDER_ID)
            voidInProgress = savedInstanceState.getBoolean(STATE_VOID_IN_PROGRESS, false)
            isConnected = savedInstanceState.getBoolean(STATE_EZYPOS_CONNECTED, false)
            awaitingKioskRelay = savedInstanceState.getBoolean(STATE_AWAITING_KIOSK_RELAY, false)
            lensingStatusLabelPinned = savedInstanceState.getBoolean(STATE_LENSING_LABEL_PINNED, false)
        } else {
            isConnected = ConnectStateStore.isEzyposConnected(this)
        }

        POSRouter.initialize(this, DemoConfig.routerConfig(this))
        applySavedRoutePreference()

        orderSummary = findViewById(R.id.orderSummary)
        totalAmount = findViewById(R.id.totalAmount)
        sdkStatus = findViewById(R.id.sdkStatus)
        lensingStatusLabel = findViewById(R.id.lensingStatusLabel)
        lensingStatusDot = findViewById(R.id.lensingStatusDot)
        lensingStatusContainer = findViewById(R.id.lensingStatusContainer)
        lensingStatusContainer.setOnClickListener {
            lensingStatusLabelPinned = true
            lensingStatusLabel.visibility = View.VISIBLE
        }
        btnConnect = findViewById(R.id.btnConnect)
        btnVoidPayment = findViewById(R.id.btnVoidPayment)
        POSRouter.setTerminalListener(terminalListener)
        listOf(
            R.id.btnRemoteKiosk,
            R.id.btnLocalKioskDeepLink,
            R.id.btnLocalKioskSdk,
            R.id.btnLocalKioskIntent,
            R.id.btnPayCard,
            R.id.btnPayQr,
            R.id.btnPaySkyzer,
            R.id.btnVoidPayment
        ).forEach { id -> compactPayButton(findViewById(id)) }
        applyConnectButtonStyle()
        updateLensingStatus(POSRouter.currentLensingState())
        updateVoidButtonState()

        findViewById<RecyclerView>(R.id.productList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = ProductAdapter(ICE_CREAM_MENU) { product ->
                orderItems.add(product)
                refreshOrder()
            }
        }

        btnConnect.setOnClickListener { showConnectOptionsDialog() }
        findViewById<Button>(R.id.btnPayCard).setOnClickListener { onPay(DemoConfig.PAY_METHOD_CARD) }
        findViewById<Button>(R.id.btnPayQr).setOnClickListener { onPay(DemoConfig.PAY_METHOD_QR) }
        findViewById<Button>(R.id.btnPaySkyzer).setOnClickListener { onPay(DemoConfig.PAY_METHOD_SKYZER) }
        findViewById<Button>(R.id.btnRemoteKiosk).setOnClickListener { onRemoteKiosk() }
        findViewById<Button>(R.id.btnLocalKioskDeepLink).setOnClickListener { onLocalKioskDeepLink() }
        findViewById<Button>(R.id.btnLocalKioskSdk).setOnClickListener { onLocalKioskSdk() }
        findViewById<Button>(R.id.btnLocalKioskIntent).setOnClickListener { onLocalKioskIntent() }
        findViewById<MaterialButton>(R.id.btnClearOrder).setOnClickListener { clearOrder() }
        btnVoidPayment.setOnClickListener { onVoidPayment() }
        refreshLocalKioskButtons()

        handlePayResultIntent(intent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(applyLensingStatusRunnable)
        stopLensingDotPulse()
        POSRouter.setTerminalListener(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ORDER_COUNTER, orderCounter)
        outState.putString(STATE_PENDING_ORDER_ID, pendingOrderId)
        outState.putBoolean(STATE_VOID_IN_PROGRESS, voidInProgress)
        outState.putBoolean(STATE_EZYPOS_CONNECTED, isConnected)
        outState.putBoolean(STATE_AWAITING_KIOSK_RELAY, awaitingKioskRelay)
        outState.putBoolean(STATE_LENSING_LABEL_PINNED, lensingStatusLabelPinned)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePayResultIntent(intent)
    }

    override fun onPause() {
        pausedAtElapsedMs = SystemClock.elapsedRealtime()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val backgroundMs = if (pausedAtElapsedMs > 0L) {
            SystemClock.elapsedRealtime() - pausedAtElapsedMs
        } else {
            0L
        }
        POSRouter.refreshLensingConnection(backgroundMs)
        mainHandler.removeCallbacks(applyLensingStatusRunnable)
        applyLensingStatus(POSRouter.currentLensingState())
        refreshLocalKioskButtons()
    }

    /** Disable Local Kiosk buttons when POSRouter Kiosk is not installed (SDK-only devices). */
    private fun refreshLocalKioskButtons() {
        val available = POSRouter.isLocalKioskAvailable(this)
        findViewById<MaterialButton>(R.id.btnLocalKioskDeepLink).isEnabled = available
        findViewById<MaterialButton>(R.id.btnLocalKioskSdk).isEnabled = available
        findViewById<MaterialButton>(R.id.btnLocalKioskIntent).isEnabled = available
    }

    private fun showConnectOptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_connect_options, null)
        val terminalInput = dialogView.findViewById<TextInputEditText>(R.id.inputTerminalId)
        val merchantInput = dialogView.findViewById<TextInputEditText>(R.id.inputMerchantId)
        val participantCodeDisplay = dialogView.findViewById<TextInputEditText>(R.id.displayParticipantCode)
        val participantKeyDisplay = dialogView.findViewById<TextInputEditText>(R.id.displayParticipantKey)
        val routeSpinner = dialogView.findViewById<Spinner>(R.id.routePreferenceSpinner)

        terminalInput.setText(ConnectStateStore.getTerminalId(this))
        merchantInput.setText(ConnectStateStore.getMerchantId(this))
        participantCodeDisplay.setText(DemoConfig.participantCode())
        participantKeyDisplay.setText(
            DemoConfig.participantKey().takeIf { it.isNotBlank() }?.let { SecretMask.maskMiddle(it) }
                ?: getString(R.string.connect_participant_key_missing)
        )

        val routeLabels = resources.getStringArray(R.array.route_preference_labels)
        val routeValues = resources.getStringArray(R.array.route_preference_values)
        routeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            routeLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val currentRoute = ConnectStateStore.getRoutePreference(this)
        routeSpinner.setSelection(routeValues.indexOf(currentRoute).coerceAtLeast(0))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connect_options_title)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_save_and_connect, null)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btnReconnectLensing).setOnClickListener {
            dialog.dismiss()
            reconnectLensingOnly()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val terminalId = terminalInput.text?.toString()?.trim().orEmpty()
                val merchantId = merchantInput.text?.toString()?.trim().orEmpty()
                if (terminalId.isBlank() || merchantId.isBlank()) {
                    Toast.makeText(this, R.string.connect_missing_fields, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (DemoConfig.participantKey().isBlank()) {
                    Toast.makeText(this, R.string.connect_participant_not_configured, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val routePreference = routeValues.getOrElse(routeSpinner.selectedItemPosition) {
                    RoutePreference.AUTO
                }
                if (!ConnectStateStore.saveConnectSettings(
                        this,
                        terminalId,
                        merchantId,
                        routePreference
                    )
                ) {
                    Toast.makeText(this, R.string.connect_save_failed, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                performConnect()
            }
        }
        dialog.show()
    }

    private fun performConnect() {
        val terminalId = ConnectStateStore.getTerminalId(this)
        val merchantId = ConnectStateStore.getMerchantId(this)
        val participantCode = DemoConfig.participantCode()
        val routePreference = ConnectStateStore.getRoutePreference(this)
        POSRouter.initialize(this, DemoConfig.routerConfig(terminalId, merchantId))
        POSRouter.reconnectLensing()
        appendSdkStatus(
            "Connect requested — participant=$participantCode terminal=$terminalId merchant=$merchantId route=$routePreference"
        )
        if (POSRouter.currentLensingState() != LensingConnectionState.CONNECTED) {
            appendSdkStatus(getString(R.string.connect_waiting_lensing))
        }
        POSRouter.connect(
            this,
            routerCallback { result, error ->
                when {
                    result != null -> reportConnectResult(result)
                    error != null && error.code != "CONNECTING" ->
                        appendSdkStatus("Connect error [${error.code}]: ${error.message}")
                }
            },
            routePreference = routePreference
        )
    }

    private fun reconnectLensingOnly() {
        appendSdkStatus(getString(R.string.connect_reconnect_lensing))
        POSRouter.reconnectLensing()
    }

    /** NATS → remote terminal method picker (`POSRouter.pay` + `remote_only`). */
    private fun onRemoteKiosk() {
        if (POSRouter.currentLensingState() != LensingConnectionState.CONNECTED) {
            Toast.makeText(this, R.string.pay_remote_kiosk_lensing_offline, Toast.LENGTH_LONG).show()
            return
        }
        onPay(
            method = DemoConfig.PAY_METHOD_SELECTION,
            routePreference = RoutePreference.REMOTE_ONLY
        )
    }

    /**
     * Same-device kiosk via implicit deeplink:
     * `Intent(ACTION_VIEW, posrouter-kiosk://charge?…)`.
     */
    private fun onLocalKioskDeepLink() {
        if (!ensureLocalKioskInstalled()) return
        val prepared = prepareLocalKioskCharge("Deep Link") ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, prepared.chargeUri))
            Toast.makeText(this, R.string.pay_kiosk_launching, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            clearKioskRelayFlow()
            reportLocalKioskUnavailable(e)
        }
    }

    /**
     * Same-device kiosk via SDK:
     * `POSRouter.pay(..., routePreference = LOCAL_POSROUTER_KIOSK)`.
     */
    private fun onLocalKioskSdk() {
        if (!ensureLocalKioskInstalled()) return
        onPay(
            method = DemoConfig.PAY_METHOD_SELECTION,
            routePreference = RoutePreference.LOCAL_POSROUTER_KIOSK
        )
    }

    /**
     * Same-device kiosk via explicit component Intent
     * (`setClassName` to [DemoConfig.KIOSK_ACTIVITY] + charge URI data).
     */
    private fun onLocalKioskIntent() {
        if (!ensureLocalKioskInstalled()) return
        val prepared = prepareLocalKioskCharge("Intent") ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, prepared.chargeUri).apply {
                setClassName(DemoConfig.KIOSK_PACKAGE, DemoConfig.KIOSK_ACTIVITY)
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(intent)
            Toast.makeText(this, R.string.pay_kiosk_launching, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            clearKioskRelayFlow()
            reportLocalKioskUnavailable(e)
        }
    }

    private fun ensureLocalKioskInstalled(): Boolean {
        if (POSRouter.isLocalKioskAvailable(this)) return true
        refreshLocalKioskButtons()
        reportLocalKioskUnavailable(null)
        return false
    }

    private fun reportLocalKioskUnavailable(error: Exception?) {
        val message = getString(R.string.pay_kiosk_local_not_installed)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        appendSdkStatus(
            if (error != null) "$message (${error.javaClass.simpleName}: ${error.message})"
            else message
        )
    }

    private data class PreparedLocalKioskCharge(
        val orderId: String,
        val chargeUri: Uri
    )

    private fun prepareLocalKioskCharge(label: String): PreparedLocalKioskCharge? {
        if (orderItems.isEmpty()) {
            Toast.makeText(this, R.string.order_empty, Toast.LENGTH_SHORT).show()
            return null
        }

        val totalCents = orderItems.sumOf { it.priceCents }
        val orderId = nextOrderId()
        pendingOrderId = orderId
        voidInProgress = false
        updateVoidButtonState()
        val remark = orderItems.groupingBy { it.name }.eachCount()
            .entries.joinToString(", ") { (name, count) -> "$count x $name" }

        val chargeUri = DemoDeeplinks.buildKioskChargeUri(
            orderId = orderId,
            amountCents = totalCents,
            currency = DemoConfig.CURRENCY,
            remark = remark
        )
        appendSdkStatus("Local Kiosk ($label) — order=$orderId amount=${totalCents / 100.0}")
        appendSdkStatus("Launch: $chargeUri")
        appendSdkStatus("Await relay: ${DemoDeeplinks.PAY_RESULT_URI}")
        awaitingKioskRelay = true
        ConnectStateStore.setKioskRelayOrderId(this, orderId)
        return PreparedLocalKioskCharge(orderId, chargeUri)
    }

    private fun applySavedRoutePreference() {
        POSRouter.setRoutePreference(ConnectStateStore.getRoutePreference(this))
    }

    private fun clearKioskRelayFlow() {
        awaitingKioskRelay = false
        pendingOrderId = null
        ConnectStateStore.setKioskRelayOrderId(this, null)
        updateVoidButtonState()
    }

    private fun onPay(method: String?, routePreference: String? = null) {
        if (orderItems.isEmpty()) {
            Toast.makeText(this, R.string.order_empty, Toast.LENGTH_SHORT).show()
            return
        }

        applySavedRoutePreference()
        routePreference?.let { POSRouter.setRoutePreference(it) }

        val totalCents = orderItems.sumOf { it.priceCents }
        val orderId = nextOrderId()
        pendingOrderId = orderId
        voidInProgress = false
        updateVoidButtonState()
        val remark = orderItems.groupingBy { it.name }.eachCount()
            .entries.joinToString(", ") { (name, count) -> "$count x $name" }

        val methodLabel = method ?: "(terminal picks)"
        val effectiveRoute = routePreference ?: POSRouter.getRoutePreference()
        appendSdkStatus(
            "Pay requested — order=$orderId method=$methodLabel amount=${totalCents / 100.0} route=$effectiveRoute"
        )
        if (effectiveRoute == RoutePreference.LOCAL_POSROUTER_KIOSK) {
            awaitingKioskRelay = true
            ConnectStateStore.setKioskRelayOrderId(this, orderId)
            appendSdkStatus("Await local-kiosk relay: ${DemoDeeplinks.PAY_RESULT_URI}")
        }

        POSRouter.pay(
            this,
            PaymentRequest(
                terminalId = ConnectStateStore.getTerminalId(this@MainActivity),
                amount = totalCents,
                orderId = orderId,
                remark = remark,
                method = method
            ),
            payRouterCallback(
                onUserCancelled = { result ->
                    appendSdkStatus("onUserCancelled — order=${result.orderId}")
                    finishPayOutcome(result, showDialog = true)
                },
                onInitiatorVoided = { result ->
                    appendSdkStatus("onInitiatorVoided — order=${result.orderId}")
                    // Staff already pressed VOID — clear cart, skip result dialog.
                    orderItems.clear()
                    refreshOrder()
                    finishPayOutcome(result, showDialog = false)
                },
                onResult = { result ->
                    // Skip cancel types already handled by the optional hooks above.
                    if (isUserCancelledResult(result) || isInitiatorVoidResult(result)) return@payRouterCallback
                    reportPayResult(result)
                },
                onError = { error ->
                    pendingOrderId = null
                    voidInProgress = false
                    updateVoidButtonState()
                    POSRouter.cancelPendingPayment(orderId)
                    appendSdkStatus("Pay error [${error.code}]: ${error.message}")
                }
            )
        )
    }

    private fun onVoidPayment() {
        val orderId = pendingOrderId
        if (orderId.isNullOrBlank()) {
            Toast.makeText(this, R.string.void_no_pending, Toast.LENGTH_SHORT).show()
            return
        }
        if (voidInProgress) {
            Toast.makeText(this, R.string.void_already_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        if (POSRouter.currentLensingState() != LensingConnectionState.CONNECTED) {
            appendSdkStatus(getString(R.string.void_failed_lensing))
            Toast.makeText(this, R.string.void_failed_lensing, Toast.LENGTH_LONG).show()
            return
        }

        appendSdkStatus("Void requested — order=$orderId")
        if (POSRouter.voidPayment(orderId)) {
            voidInProgress = true
            updateVoidButtonState()
            appendSdkStatus(getString(R.string.void_published_waiting))
        } else {
            appendSdkStatus(getString(R.string.void_failed_no_session))
            Toast.makeText(this, R.string.void_failed_no_session, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateVoidButtonState() {
        val hasPending = !pendingOrderId.isNullOrBlank()
        btnVoidPayment.isEnabled = hasPending && !voidInProgress
        btnVoidPayment.text = if (voidInProgress) {
            getString(R.string.btn_void_waiting)
        } else {
            getString(R.string.btn_void_payment)
        }
    }

    private fun updateLensingStatus(state: LensingConnectionState) {
        if (!::lensingStatusDot.isInitialized) return
        pendingLensingState = state
        mainHandler.removeCallbacks(applyLensingStatusRunnable)
        if (isFinishing || isDestroyed) return
        mainHandler.postDelayed(applyLensingStatusRunnable, LENSING_STATUS_DEBOUNCE_MS)
    }

    private fun applyLensingStatus(state: LensingConnectionState) {
        if (!::lensingStatusDot.isInitialized || isFinishing || isDestroyed) return
        if (state == appliedLensingState) return
        appliedLensingState = state
        val labelRes = when (state) {
            LensingConnectionState.CONNECTED -> R.string.lensing_label_connected
            LensingConnectionState.DISCOVERING,
            LensingConnectionState.CONNECTING,
            LensingConnectionState.RECONNECTING -> R.string.lensing_label_connecting
            LensingConnectionState.FAILED -> R.string.lensing_label_failed
            LensingConnectionState.OFFLINE -> R.string.lensing_label_offline
        }
        lensingStatusLabel.setText(labelRes)
        lensingStatusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(POSRouter.lensingIndicatorColor(state))
        }
        updateLensingDotPulse(isLensingConnecting(state))
        lensingStatusLabel.visibility = if (lensingStatusLabelPinned) View.VISIBLE else View.GONE
    }

    private fun isLensingConnecting(state: LensingConnectionState): Boolean = when (state) {
        LensingConnectionState.DISCOVERING,
        LensingConnectionState.CONNECTING,
        LensingConnectionState.RECONNECTING -> true
        else -> false
    }

    private fun updateLensingDotPulse(connecting: Boolean) {
        if (connecting) {
            if (lensingDotPulseAnimator?.isRunning == true) return
            stopLensingDotPulse()
            lensingStatusDot.alpha = LensingConnectionIndicator.PULSE_ALPHA_MAX
            lensingDotPulseAnimator = ObjectAnimator.ofFloat(
                lensingStatusDot,
                View.ALPHA,
                LensingConnectionIndicator.PULSE_ALPHA_MAX,
                LensingConnectionIndicator.PULSE_ALPHA_MIN
            ).apply {
                duration = LensingConnectionIndicator.PULSE_HALF_CYCLE_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } else {
            stopLensingDotPulse()
        }
    }

    private fun stopLensingDotPulse() {
        lensingDotPulseAnimator?.cancel()
        lensingDotPulseAnimator = null
        if (::lensingStatusDot.isInitialized) {
            lensingStatusDot.alpha = LensingConnectionIndicator.PULSE_ALPHA_MAX
        }
    }

    private fun reportConnectResult(result: PaymentResult) {
        val route = routeLabel(result.localRouteMethod)
        when (result.localRouteMethod) {
            LocalRouteMethod.NETWORK -> {
                appendSdkStatus(
                    buildString {
                        append(getString(R.string.connect_lensing_ready))
                        if (route != null) append("\n  $route")
                        result.message?.let { append("\n  $it") }
                    }
                )
            }
            LocalRouteMethod.EXPLICIT_INTENT, LocalRouteMethod.DEEP_LINK -> {
                appendSdkStatus(
                    buildString {
                        append(getString(R.string.connect_ezypos_launched))
                        if (route != null) append("\n  $route")
                        result.message?.let { append("\n  $it") }
                    }
                )
            }
            null -> appendSdkStatus("Connect returned without route info")
        }
        applyConnectButtonStyle()
    }

    private fun markEzyposConnected() {
        isConnected = true
        ConnectStateStore.setEzyposConnected(this, true)
        appendSdkStatus(getString(R.string.connect_ezypos_confirmed))
        applyConnectButtonStyle()
    }

    private fun compactPayButton(button: MaterialButton) {
        button.insetTop = 0
        button.insetBottom = 0
    }

    private fun applyConnectButtonStyle() {
        btnConnect.isEnabled = true
        if (isConnected) {
            btnConnect.text = getString(R.string.btn_connected)
            btnConnect.strokeWidth = 0
            btnConnect.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(btnConnect, com.google.android.material.R.attr.colorPrimary)
            )
            btnConnect.setTextColor(
                MaterialColors.getColor(btnConnect, com.google.android.material.R.attr.colorOnPrimary)
            )
        } else {
            btnConnect.text = getString(R.string.btn_connect)
            btnConnect.strokeWidth = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
            btnConnect.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btnConnect.setTextColor(
                MaterialColors.getColor(btnConnect, com.google.android.material.R.attr.colorPrimary)
            )
        }
    }

    private fun reportPayResult(result: PaymentResult) {
        if (result.status == PaymentStatus.APPROVED) {
            orderItems.clear()
            refreshOrder()
        }
        finishPayOutcome(result, showDialog = true)
    }

    /** Shared cleanup after a terminal pay outcome (hooks + generic onResult). */
    private fun finishPayOutcome(result: PaymentResult, showDialog: Boolean) {
        awaitingKioskRelay = false
        ConnectStateStore.setKioskRelayOrderId(this, null)
        voidInProgress = false
        val route = routeLabel(result.localRouteMethod)
        val cancelReason = result.metadata["cancelReason"]
        appendSdkStatus(
            buildString {
                append("Payment ${result.status}")
                result.orderId?.let { append(" — order=$it") }
                if (route != null) append(" via $route")
                cancelReason?.let { append("\n  cancelReason=$it") }
                result.transactionId?.let { append("\n  txn=$it") }
                result.message?.let { append("\n  $it") }
            }
        )
        if (showDialog) {
            showPayResultDialog(result)
        }
        pendingOrderId = null
        updateVoidButtonState()
    }

    private fun isInitiatorVoidResult(result: PaymentResult): Boolean =
        result.status == PaymentStatus.CANCELLED &&
            result.metadata["cancelReason"] == PaymentCancelReason.INITIATOR_VOID

    private fun isUserCancelledResult(result: PaymentResult): Boolean =
        result.status == PaymentStatus.CANCELLED &&
            result.metadata["cancelReason"] == PaymentCancelReason.USER_CANCEL

    private fun showPayResultDialog(result: PaymentResult) {
        val cancelReason = result.metadata["cancelReason"]
        val titleRes = when (result.status) {
            PaymentStatus.APPROVED -> R.string.pay_banner_title_approved
            PaymentStatus.CANCELLED -> R.string.pay_banner_title_cancelled
            PaymentStatus.DECLINED -> R.string.pay_banner_title_declined
            PaymentStatus.ERROR -> R.string.pay_banner_title_error
        }
        val amountText = String.format(Locale.US, "%.2f", result.amount / 100.0)
        val message = buildString {
            append(
                getString(
                    R.string.pay_banner_message,
                    result.orderId ?: "—",
                    amountText,
                    result.currency
                )
            )
            cancelReason?.let { append("\n\nReason: $it") }
            result.transactionId?.let { append("\n\nTxn: $it") }
            result.message?.takeIf { it.isNotBlank() }?.let { append("\n\n$it") }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(R.string.pay_banner_ok, null)
            .show()
    }

    private fun handlePayResultIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (!DemoDeeplinks.isPayResultCallback(data)) return

        val type = data.getQueryParameter("type")?.uppercase(Locale.US).orEmpty()
        val status = data.getQueryParameter("status")?.uppercase(Locale.US).orEmpty()

        if (type == "CONNECT") {
            appendSdkStatus("Ezypos connect callback: ${formatCallbackUri(data)}")
            if (status == "SUCCESS") {
                markEzyposConnected()
            } else {
                clearEzyposConnected()
                appendSdkStatus("Connect failed — status=$status")
            }
            clearLaunchIntent()
            return
        }

        val orderId = data.getQueryParameter("orderid") ?: data.getQueryParameter("orderId")
        val kioskRelayOrderId = pendingKioskRelayOrderId()
        val inKioskChargeFlow = kioskRelayOrderId != null && orderId == kioskRelayOrderId
        if (inKioskChargeFlow) {
            if (data.getQueryParameter("relay") != "kiosk") {
                appendSdkStatus("Forwarding acquirer callback to kiosk — ${formatCallbackUri(data)}")
                val forwarded = data.buildUpon()
                    .scheme(DemoDeeplinks.KIOSK_SCHEME)
                    .authority(DemoDeeplinks.PAY_RESULT_HOST)
                    .build()
                startActivity(Intent(Intent.ACTION_VIEW, forwarded))
                clearLaunchIntent()
                moveTaskToBack(true)
                return
            }
        }

        if (type != "PAY" && type.isNotEmpty()) {
            clearLaunchIntent()
            return
        }

        val result = if (inKioskChargeFlow) {
            appendSdkStatus("Kiosk relay: ${formatCallbackUri(data)}")
            val delivered = POSRouter.deliverAcquirerCallback(data)
            if (delivered?.metadata?.get(POSRouter.META_PAY_CALLBACK_DELIVERED) == "1") {
                // POSRouter.pay(..., local_posrouter_kiosk) already reported via pay callback.
                delivered
            } else {
                delivered ?: DemoDeeplinks.parsePartnerRelayResult(data)
            }
        } else {
            appendSdkStatus("Ezypos callback: ${formatCallbackUri(data)}")
            POSRouter.deliverAcquirerCallback(data)
        }
        if (result != null &&
            result.metadata[POSRouter.META_PAY_CALLBACK_DELIVERED] != "1"
        ) {
            reportPayResult(result)
        } else if (result == null) {
            appendSdkStatus("Pay callback received but could not parse result")
        }
        clearLaunchIntent()
    }

    private fun pendingKioskRelayOrderId(): String? {
        pendingOrderId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return ConnectStateStore.getKioskRelayOrderId(this)
    }

    private fun clearLaunchIntent() {
        setIntent(Intent(this, javaClass))
    }

    private fun clearEzyposConnected() {
        isConnected = false
        ConnectStateStore.setEzyposConnected(this, false)
        applyConnectButtonStyle()
    }

    private fun formatCallbackUri(uri: Uri): String =
        uri.queryParameterNames
            .sorted()
            .joinToString(" | ") { name ->
                "$name=${uri.getQueryParameter(name)}"
            }
            .ifBlank { uri.toString() }

    private fun nextOrderId(): String {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        return "GM$stamp${orderCounter++}"
    }

    private fun appendSdkStatus(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] ${normalizeSdkStatusLine(line)}"
        val current = sdkStatus.text?.toString().orEmpty()
        sdkStatus.text = if (current == getString(R.string.sdk_status_hint)) {
            entry
        } else {
            "$current\n$entry"
        }
        sdkStatus.post {
            (sdkStatus.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun normalizeSdkStatusLine(line: String): String =
        line.replace(Regex("\\bNATS\\b", RegexOption.IGNORE_CASE), "Lensing")
            .replace("Network track connected", "Lensing connected")

    private fun routeLabel(method: LocalRouteMethod?): String? = when (method) {
        LocalRouteMethod.EXPLICIT_INTENT -> getString(R.string.route_explicit_intent)
        LocalRouteMethod.DEEP_LINK -> getString(R.string.route_deep_link)
        LocalRouteMethod.NETWORK -> getString(R.string.route_network)
        null -> null
    }

    private fun clearOrder() {
        if (orderItems.isEmpty()) return
        orderItems.clear()
        refreshOrder()
    }

    private fun refreshOrder() {
        if (orderItems.isEmpty()) {
            orderSummary.text = getString(R.string.order_empty_hint)
            totalAmount.text = getString(R.string.total_zero)
            return
        }

        val lines = orderItems.groupingBy { it.name }.eachCount()
            .entries.joinToString("\n") { (name, count) -> "$count x $name" }

        orderSummary.text = lines
        val totalCents = orderItems.sumOf { it.priceCents }
        totalAmount.text = getString(R.string.total_format, totalCents / 100.0)
    }

    private fun routerCallback(
        handler: (PaymentResult?, POSRouterError?) -> Unit
    ) = object : POSRouterCallback {
        override fun onResult(result: PaymentResult) {
            runOnUiThread { handler(result, null) }
        }

        override fun onError(error: POSRouterError) {
            runOnUiThread { handler(null, error) }
        }
    }

    private fun payRouterCallback(
        onUserCancelled: (PaymentResult) -> Unit,
        onInitiatorVoided: (PaymentResult) -> Unit,
        onResult: (PaymentResult) -> Unit,
        onError: (POSRouterError) -> Unit
    ) = object : POSRouterCallback {
        override fun onUserCancelled(result: PaymentResult) {
            runOnUiThread { onUserCancelled(result) }
        }

        override fun onInitiatorVoided(result: PaymentResult) {
            runOnUiThread { onInitiatorVoided(result) }
        }

        override fun onResult(result: PaymentResult) {
            runOnUiThread { onResult(result) }
        }

        override fun onError(error: POSRouterError) {
            runOnUiThread { onError(error) }
        }
    }

    private class ProductAdapter(
        private val products: List<Product>,
        private val onAdd: (Product) -> Unit
    ) : RecyclerView.Adapter<ProductAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.productName)
            val price: TextView = view.findViewById(R.id.productPrice)
            val addBtn: Button = view.findViewById(R.id.btnAdd)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_product, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val product = products[position]
            holder.name.text = product.name
            holder.price.text = holder.itemView.context.getString(
                R.string.price_format,
                product.priceCents / 100.0
            )
            holder.addBtn.setOnClickListener { onAdd(product) }
        }

        override fun getItemCount() = products.size
    }

    companion object {
        private const val STATE_ORDER_COUNTER = "order_counter"
        private const val STATE_PENDING_ORDER_ID = "pending_order_id"
        private const val STATE_VOID_IN_PROGRESS = "void_in_progress"
        private const val STATE_EZYPOS_CONNECTED = "ezypos_connected"
        private const val STATE_AWAITING_KIOSK_RELAY = "awaiting_kiosk_relay"
        private const val STATE_LENSING_LABEL_PINNED = "lensing_label_pinned"
        private const val LENSING_STATUS_DEBOUNCE_MS = 300L
    }
}
