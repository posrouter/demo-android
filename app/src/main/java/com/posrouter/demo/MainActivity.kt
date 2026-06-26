package com.posrouter.demo

import android.content.res.ColorStateList
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.posrouter.NatsConnectionState
import com.posrouter.PaymentCancelReason
import com.posrouter.RoutePreference
import com.posrouter.POSRouter
import com.posrouter.POSRouterCallback
import com.posrouter.POSRouterError
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
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnVoidPayment: MaterialButton
    private var orderCounter = 1
    private var pendingOrderId: String? = null
    private var voidInProgress = false
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState != null) {
            orderCounter = savedInstanceState.getInt(STATE_ORDER_COUNTER, 1)
            pendingOrderId = savedInstanceState.getString(STATE_PENDING_ORDER_ID)
            voidInProgress = savedInstanceState.getBoolean(STATE_VOID_IN_PROGRESS, false)
            isConnected = savedInstanceState.getBoolean(STATE_EZYPOS_CONNECTED, false)
        } else {
            isConnected = ConnectStateStore.isEzyposConnected(this)
        }

        POSRouter.initialize(this, DemoConfig.routerConfig(this))

        orderSummary = findViewById(R.id.orderSummary)
        totalAmount = findViewById(R.id.totalAmount)
        sdkStatus = findViewById(R.id.sdkStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnVoidPayment = findViewById(R.id.btnVoidPayment)
        applyConnectButtonStyle()
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
        findViewById<MaterialButton>(R.id.btnClearOrder).setOnClickListener { clearOrder() }
        btnVoidPayment.setOnClickListener { onVoidPayment() }

        handlePayResultIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ORDER_COUNTER, orderCounter)
        outState.putString(STATE_PENDING_ORDER_ID, pendingOrderId)
        outState.putBoolean(STATE_VOID_IN_PROGRESS, voidInProgress)
        outState.putBoolean(STATE_EZYPOS_CONNECTED, isConnected)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePayResultIntent(intent)
    }

    override fun onResume() {
        super.onResume()
    }

    private fun showConnectOptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_connect_options, null)
        val terminalInput = dialogView.findViewById<TextInputEditText>(R.id.inputTerminalId)
        val merchantInput = dialogView.findViewById<TextInputEditText>(R.id.inputMerchantId)
        val routeSpinner = dialogView.findViewById<Spinner>(R.id.routePreferenceSpinner)

        terminalInput.setText(ConnectStateStore.getTerminalId(this))
        merchantInput.setText(ConnectStateStore.getMerchantId(this))

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
            .setPositiveButton(R.string.btn_connect, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val terminalId = terminalInput.text?.toString()?.trim().orEmpty()
                val merchantId = merchantInput.text?.toString()?.trim().orEmpty()
                if (terminalId.isBlank() || merchantId.isBlank()) {
                    Toast.makeText(this, R.string.connect_missing_fields, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val routePreference = routeValues.getOrElse(routeSpinner.selectedItemPosition) {
                    RoutePreference.AUTO
                }
                ConnectStateStore.saveConnectSettings(
                    this,
                    terminalId,
                    merchantId,
                    routePreference
                )
                dialog.dismiss()
                performConnect(terminalId, merchantId, routePreference)
            }
        }
        dialog.show()
    }

    private fun performConnect(terminalId: String, merchantId: String, routePreference: String) {
        POSRouter.initialize(this, DemoConfig.routerConfig(terminalId, merchantId))
        appendSdkStatus(
            "Connect requested — terminal=$terminalId merchant=$merchantId route=$routePreference"
        )
        POSRouter.connect(
            this,
            routerCallback { result, error ->
                when {
                    result != null -> reportConnectResult(result)
                    error != null -> appendSdkStatus("Connect error [${error.code}]: ${error.message}")
                }
            },
            routePreference = routePreference
        )
    }

    private fun onPay(method: String) {
        if (orderItems.isEmpty()) {
            Toast.makeText(this, R.string.order_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val totalCents = orderItems.sumOf { it.priceCents }
        val orderId = nextOrderId()
        pendingOrderId = orderId
        voidInProgress = false
        updateVoidButtonState()
        val remark = orderItems.groupingBy { it.name }.eachCount()
            .entries.joinToString(", ") { (name, count) -> "$count x $name" }

        appendSdkStatus("Pay requested — order=$orderId method=$method amount=${totalCents / 100.0}")

        POSRouter.pay(
            this,
            PaymentRequest(
                terminalId = ConnectStateStore.getTerminalId(this@MainActivity),
                amount = totalCents,
                orderId = orderId,
                remark = remark,
                method = method
            ),
            routerCallback { result, error ->
                when {
                    result != null -> reportPayResult(result)
                    error != null -> {
                        pendingOrderId = null
                        voidInProgress = false
                        updateVoidButtonState()
                        POSRouter.cancelPendingPayment(orderId)
                        appendSdkStatus("Pay error [${error.code}]: ${error.message}")
                    }
                }
            }
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
        if (POSRouter.currentNatsState() != NatsConnectionState.CONNECTED) {
            appendSdkStatus(getString(R.string.void_failed_nats))
            Toast.makeText(this, R.string.void_failed_nats, Toast.LENGTH_LONG).show()
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

    private fun reportConnectResult(result: PaymentResult) {
        val route = routeLabel(result.localRouteMethod)
        when (result.localRouteMethod) {
            LocalRouteMethod.NETWORK -> {
                appendSdkStatus(
                    buildString {
                        append(getString(R.string.connect_nats_ready))
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
        if (result.status == PaymentStatus.APPROVED) {
            orderItems.clear()
            refreshOrder()
        } else if (result.status == PaymentStatus.CANCELLED &&
            cancelReason == PaymentCancelReason.INITIATOR_VOID
        ) {
            orderItems.clear()
            refreshOrder()
        }
        showPayResultDialog(result)
        pendingOrderId = null
        updateVoidButtonState()
    }

    private fun showPayResultDialog(result: PaymentResult) {
        val cancelReason = result.metadata["cancelReason"]
        val titleRes = when (result.status) {
            PaymentStatus.APPROVED -> R.string.pay_banner_title_approved
            PaymentStatus.CANCELLED -> when (cancelReason) {
                PaymentCancelReason.INITIATOR_VOID -> R.string.pay_banner_title_voided
                else -> R.string.pay_banner_title_cancelled
            }
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
        if (data.scheme != "gomenu" || data.host != "pay_result") return

        val type = data.getQueryParameter("type")?.uppercase(Locale.US).orEmpty()
        val status = data.getQueryParameter("status")?.uppercase(Locale.US).orEmpty()

        appendSdkStatus("Ezypos callback: ${formatCallbackUri(data)}")

        if (type == "CONNECT") {
            if (status == "SUCCESS") {
                markEzyposConnected()
            } else {
                clearEzyposConnected()
                appendSdkStatus("Connect failed — status=$status")
            }
            setIntent(Intent(this, javaClass))
            return
        }

        if (type == "PAY" || type.isEmpty()) {
            val result = POSRouter.deliverAcquirerCallback(data)
            if (result != null) {
                reportPayResult(result)
            } else {
                appendSdkStatus("Pay callback received but could not parse result")
            }
            setIntent(Intent(this, javaClass))
        }
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
        val entry = "[$time] $line"
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
    }
}
