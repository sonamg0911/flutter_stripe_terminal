package com.stripe.stripe_terminal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.TerminalApplicationDelegate
import com.stripe.stripeterminal.external.callable.*
import com.stripe.stripeterminal.external.models.*
import com.stripe.stripeterminal.log.LogLevel
import io.flutter.app.FlutterActivityEvents
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

/** StripeTerminalPlugin */
class StripeTerminalPlugin : FlutterPlugin, MethodCallHandler,
        PluginRegistry.RequestPermissionsResultListener, ActivityAware, FlutterActivityEvents {

    private lateinit var channel: MethodChannel
    private var currentActivity: Activity? = null
    private val REQUEST_CODE_LOCATION = 1012
    private lateinit var tokenProvider: StripeTokenProvider
    private var cancelableDiscover: Cancelable? = null
    private var activeReaders: List<Reader> = arrayListOf()
    private var simulated = false
    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
        )
    }


    // Change this to other level soon
    private val logLevel = LogLevel.VERBOSE

    // Create your listener object. Override any methods that you want to be notified about
    val listener = object : TerminalListener {
        override fun onUnexpectedReaderDisconnect(reader: Reader) {
            // TODO: Trigger the user about the issue.
        }
    }


    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "stripe_terminal")
        channel.setMethodCallHandler(this)
    }


    fun _startStripe() {
        // Pass in the current application context, your desired logging level, your token provider, and the listener you created
        if (!Terminal.isInitialized()) {
            Terminal.initTerminal(
                    currentActivity!!.applicationContext,
                    logLevel,
                    tokenProvider,
                    listener
            )
        }

    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "init" -> {
                if (_isPermissionAllowed(result)) {
                    _startStripe()
                }
            }
            "discoverReaders#start" -> {
                val arguments = call.arguments as HashMap<*, *>
                simulated = arguments["simulated"] as Boolean
                val config = DiscoveryConfiguration(
                        isSimulated = simulated,
                        discoveryMethod = DiscoveryMethod.BLUETOOTH_SCAN
                )
                Log.d("discoverReaders", "[Stripe onUpdateDiscoveredReaders] Start onUpdateDiscoveredReaders process")
                cancelableDiscover =
                        Terminal.getInstance().discoverReaders(config, object : DiscoveryListener {

                            override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                                activeReaders = readers
                                val rawReaders = readers.map {
                                    it.rawJson()
                                }
                                Log.d("discoverReaders", "[Stripe onUpdateDiscoveredReaders] discovered readers: $rawReaders")
                                currentActivity?.runOnUiThread {
                                    channel.invokeMethod("onReadersFound", rawReaders)
                                }

                            }

                        }, object : Callback {
                            override fun onFailure(e: TerminalException) {
                                result.error(
                                        "stripeTerminal#unabelToDiscover",
                                        e.message,
                                        e.stackTraceToString()
                                )
                                cancelableDiscover = null
                            }

                            override fun onSuccess() {
                                cancelableDiscover = null
                                result.success(true)
                            }

                        })
            }
            "discoverReaders#stop" -> {
                if (cancelableDiscover == null) {
                    result.error(
                            "stripeTerminal#unabelToCancelDiscover",
                            "There is no discover action running to stop.",
                            null
                    )
                } else {
                    cancelableDiscover?.cancel(object : Callback {
                        override fun onFailure(e: TerminalException) {
                            result.error(
                                    "stripeTerminal#unabelToCancelDiscover",
                                    "Unable to stop the discover action because ${e.errorMessage}",
                                    e.stackTraceToString()
                            )
                        }

                        override fun onSuccess() {
                            result.success(true)
                        }
                    })

                }
            }
            "fetchConnectedReader" -> {
                result.success(Terminal.getInstance().connectedReader?.rawJson())
            }
            "connectionStatus" -> {
                result.success(handleConnectionStatus(Terminal.getInstance().connectionStatus))
            }
            "disconnectReader" -> {
                when (Terminal.getInstance().connectionStatus) {
                    ConnectionStatus.CONNECTED -> {

                        Terminal.getInstance().disconnectReader(
                                object : Callback {
                                    override fun onFailure(e: TerminalException) {
                                        result.error(
                                                "stripeTerminal#unableToDisconnect",
                                                e.errorMessage,
                                                e.stackTraceToString()
                                        )
                                    }

                                    override fun onSuccess() {
                                        result.success(true)
                                    }

                                })
                    }
                    ConnectionStatus.CONNECTING -> {
                        result.error(
                                "stripeTerminal#deviceConnecting",
                                "A new connection is being established with a device thus you cannot request to disconnect at the moment.",
                                null
                        )
                    }
                    ConnectionStatus.NOT_CONNECTED -> {
                        result.error(
                                "stripeTerminal#noDeviceConnected",
                                "There is currently no device connected",
                                null
                        )
                    }
                }
            }
            "connectToReader" -> {
                when (Terminal.getInstance().connectionStatus) {
                    ConnectionStatus.NOT_CONNECTED -> {
                        val arguments = call.arguments as HashMap<*, *>
                        val readerSerialNumber = arguments["readerSerialNumber"] as String

                        val reader = activeReaders.firstOrNull {
                            it.serialNumber == readerSerialNumber
                        }

                        if (reader == null) {
                            result.error(
                                    "stripeTerminal#readerNotFound",
                                    "Reader with provided serial number no longer exists",
                                    null
                            )
                            return
                        }


                        val locationId: String? = (arguments["locationId"]
                                ?: reader.location?.id) as String?

                        if (locationId == null) {
                            result.error(
                                    "stripeTerminal#locationNotProvided",
                                    "Either you have to provide the location id or device should be attached to a location",
                                    null
                            )
                            return
                        }
                        val connectionConfig =
                                ConnectionConfiguration.BluetoothConnectionConfiguration(
                                        locationId,
                                )
                        Terminal.getInstance().connectBluetoothReader(
                                reader,
                                connectionConfig,
                                object : BluetoothReaderListener {


                                },
                                object : ReaderCallback {
                                    override fun onFailure(e: TerminalException) {
                                        result.error(
                                                "stripeTerminal#unableToConnect",
                                                e.errorMessage,
                                                e.stackTraceToString()
                                        )
                                    }

                                    override fun onSuccess(reader: Reader) {
                                        result.success(true)
                                    }

                                })
                    }
                    ConnectionStatus.CONNECTING -> {
                        result.error(
                                "stripeTerminal#deviceConnecting",
                                "A new connection is being established with a device thus you cannot request a new connection at the moment.",
                                null
                        )
                    }
                    ConnectionStatus.CONNECTED -> {
                        result.error(
                                "stripeTerminal#deviceAlreadyConnected",
                                "A device with serial number ${Terminal.getInstance().connectedReader!!.serialNumber} is already connected",
                                null
                        )
                    }
                }
            }
            "readPaymentMethod" -> {
                if (Terminal.getInstance().connectedReader == null) {
                    result.error(
                            "stripeTerminal#deviceNotConnected",
                            "You must connect to a device before you can use it.",
                            null
                    )
                } else {
                    val params = ReadReusableCardParameters.Builder().build()

                    Terminal.getInstance().readReusableCard(params, object : PaymentMethodCallback {
                        override fun onFailure(e: TerminalException) {
                            result.error(
                                    "stripeTerminal#unabletToReadCardDetail",
                                    "Device was not able to read payment method details because ${e.errorMessage}",
                                    e.stackTraceToString()
                            )
                        }

                        override fun onSuccess(paymentMethod: PaymentMethod) {
                            result.success(paymentMethod.rawJson())
                        }

                    })
                }
            }
            "startPayment" -> {
                when (Terminal.getInstance().connectionStatus) {
                    ConnectionStatus.CONNECTED -> {
                        Log.d("StripePayment", "[Stripe Payment] startPayment Start processCardReaderPayment process")
                        val arguments = call.arguments as HashMap<*, *>
                        val amountText = arguments["amount"] as String
                        val amount = amountText.toLong()

                        val clientSecret = arguments["clientSecret"] as String?


                        // Start payment process without creating new paymentIntent
                        startPayment(amount, result, clientSecret)


                    }
                    ConnectionStatus.CONNECTING -> {
                        Log.d("StripePayment", "[Stripe Payment] startPayment ConnectionStatus.CONNECTING")
                        result.error(
                                "stripeTerminal#deviceConnecting",
                                "Card reader connecting...",
                                null
                        )
                    }
                    ConnectionStatus.NOT_CONNECTED -> {
                        Log.d("StripePayment", "[Stripe Payment] startPayment ConnectionStatus.NOT_CONNECTED")
                        result.error(
                                "stripeTerminal#deviceAlreadyConnected",
                                "Card reader not selected.",
                                null
                        )
                    }
                }
            }
            else -> result.notImplemented()
        }

    }

    private fun startPayment(amount: Long, resultCallback: Result, clientSecret: String?) {
        val params = PaymentIntentParameters.Builder(listOf(PaymentMethodType.CARD_PRESENT))
                .setAmount(amount)
                .setCurrency("usd")
                .build()

        result = resultCallback

        Log.d("StripePayment", "[Stripe Payment] PaymentIntentParameters.Builder amount: $amount $params")

        if (clientSecret != null && clientSecret.isNotEmpty()) {
            // If clientSecret is provided, no need to create a new paymentIntent
            // Instead, fetch the paymentIntent using the provided clientSecret
            // Step 1: Fetch the paymentIntent using the [clientSecret]
            Terminal.getInstance().retrievePaymentIntent(clientSecret, createPaymentIntentCallback)
        } else {
            // Step 1: create payment intent
            Terminal.getInstance().createPaymentIntent(params, createPaymentIntentCallback)
        }

    }

    // Step 2 - once we've created the payment intent, it's time to read the card
    private val createPaymentIntentCallback by lazy {
        object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                Log.d("StripePayment", "[Stripe Payment] Created Payment Intent successfully!!!")
                Terminal.getInstance()
                        .collectPaymentMethod(paymentIntent, collectPaymentMethodCallback)
            }

            override fun onFailure(e: TerminalException) {
                // Update UI w/ failure
                Log.d("StripePayment", "[Stripe Payment] Collect Payment Unsuccessful! " + e)
                result?.error(
                        "stripeTerminal#createPaymentIntentCallbackFailure",
                        "createPaymentIntentCallback failed with error $e",
                        e.stackTraceToString()
                )
            }
        }
    }

    // Step 3 - we've collected the payment method, so it's time to process the payment
    private val collectPaymentMethodCallback by lazy {
//        object : ReaderDisplayListener {
//            override fun onRequestReaderInput(options: ReaderInputOptions) {
//                // Placeholder for updating your app's checkout UI
//                currentActivity?.runOnUiThread {
//                    channel.invokeMethod("onRequestReaderInput", options.toString())
//                }
//            }
//        },
        object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                Log.d("StripePayment", "[Stripe Payment] Collected Payment successfully!!!")
                Terminal.getInstance().processPayment(paymentIntent, processPaymentCallback)
            }

            override fun onFailure(e: TerminalException) {
                // Update UI w/ failure
                Log.d("StripePayment", "[Stripe Payment] Collect Payment Unsuccessful! " + e)
                result?.error(
                        "stripeTerminal#collectPaymentMethodCallbackFailure",
                        "collectPaymentMethodCallback failed with error $e",
                        e.stackTraceToString()
                )
            }
        }
    }

    // Step 4 - we've processed the payment! Show a success screen
    private val processPaymentCallback by lazy {
        object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                Log.d("StripePayment", "[Stripe Payment] Payment Processed successfully!")

                val response = paymentIntentToJson(paymentIntent)

                result?.success(response)
            }

            override fun onFailure(e: TerminalException) {
                // Update UI w/ failure
                Log.d("StripePayment", "[Stripe Payment] Payment Process Unsuccessful! " + e)
                result?.error(
                        "stripeTerminal#processPaymentCallbackFailure",
                        "processPaymentCallback failed with error $e",
                        e.stackTraceToString()
                )
            }
        }
    }

    var result: Result? = null
    fun _isPermissionAllowed(result: Result): Boolean {
        val permissionStatus = permissions.map {
            ContextCompat.checkSelfPermission(currentActivity!!, it)
        }

        if (!permissionStatus.contains(PackageManager.PERMISSION_DENIED)) {
            result.success(true)
            return true
        }


        val cannotAskPermissions = permissions.map {
            ActivityCompat.shouldShowRequestPermissionRationale(currentActivity!!, it)
        }

        if (cannotAskPermissions.contains(true)) {
            result.error(
                    "stripeTerminal#permissionDeclinedPermanenty",
                    "You have declined the necessary permission, please allow from settings to continue.",
                    null
            )
            return false
        }

        this.result = result
        ActivityCompat.requestPermissions(currentActivity!!, permissions, REQUEST_CODE_LOCATION)
        return false
    }


        override fun onRequestPermissionsResult(
        requestCode: Int,
        permissionResults: Array<out String>,
        grantResults: IntArray
        ): Boolean {
            val permissionStatus = permissions.map {
                ContextCompat.checkSelfPermission(currentActivity!!, it)
            }
            if (!permissionStatus.contains(PackageManager.PERMISSION_DENIED)) {
                _startStripe()
                result?.success(true)
            } else {
                result?.error(
                        "stripeTerminal#insuffecientPermission",
                        "You have not provided enough permission for the scanner to work",
                        null
                )
            }
            return requestCode == REQUEST_CODE_LOCATION
        }


    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
            channel.setMethodCallHandler(null)
        }

        override fun onDetachedFromActivity() {
            currentActivity = null
        }

        override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
            currentActivity = binding.activity
            tokenProvider = StripeTokenProvider(currentActivity!!, channel)
            binding.addRequestPermissionsResultListener(this)
        }

        override fun onAttachedToActivity(binding: ActivityPluginBinding) {
            currentActivity = binding.activity
            TerminalApplicationDelegate.onCreate(currentActivity!!.application)
            tokenProvider = StripeTokenProvider(currentActivity!!, channel)
            binding.addRequestPermissionsResultListener(this)
        }

        override fun onDetachedFromActivityForConfigChanges() {
            currentActivity = null
        }


        /*
         These functions are stub functions that are not relevent to the plugin but needs to be defined in order to get the few necessary callbacks
        */

        override fun onCreate(savedInstanceState: Bundle?) {
            TODO("Not yet implemented")
        }

        override fun onNewIntent(intent: Intent?) {
            TODO("Not yet implemented")
        }

        override fun onPause() {
            TODO("Not yet implemented")
        }

        override fun onStart() {
            TODO("Not yet implemented")
        }

        override fun onResume() {
            TODO("Not yet implemented")
        }

        override fun onPostResume() {
            TODO("Not yet implemented")
        }

        override fun onDestroy() {
            TODO("Not yet implemented")
        }

        override fun onStop() {
            TODO("Not yet implemented")
        }

        override fun onBackPressed(): Boolean {
            TODO("Not yet implemented")
        }

        override fun onUserLeaveHint() {
            TODO("Not yet implemented")
        }

        override fun onConfigurationChanged(p0: Configuration) {
            TODO("Not yet implemented")
        }

        override fun onLowMemory() {
            TODO("Not yet implemented")
        }

        override fun onTrimMemory(p0: Int) {
            TerminalApplicationDelegate.onTrimMemory(currentActivity!!.application, p0)
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
            TODO("Not yet implemented")
        }


    }

    fun paymentIntentToJson(paymentIntent: PaymentIntent): HashMap<String, Any?> {
        val json = HashMap<String, Any?>()

        json["isSuccess"] = true
        json["paymentIntentId"] = paymentIntent.id
        json["amount"] = paymentIntent.amount
        json["amountReceived"] = paymentIntent.amountReceived
        json["amountCapturable"] = paymentIntent.amountCapturable
        json["captureMethod"] = paymentIntent.captureMethod
        json["currency"] = paymentIntent.currency
        json["description"] = paymentIntent.description
        json["clientSecret"] = paymentIntent.clientSecret
        json["confirmationMethod"] = paymentIntent.confirmationMethod
        json["invoice"] = paymentIntent.invoice
        json["metadata"] = paymentIntent.metadata
        json["paymentChargeId"] = paymentIntent.getCharges().firstOrNull()?.id
        json["last4"] = paymentIntent.getCharges().firstOrNull()?.paymentMethodDetails?.cardPresentDetails?.last4
        json["brand"] = paymentIntent.getCharges().firstOrNull()?.paymentMethodDetails?.cardPresentDetails?.brand

        return json
    }


    fun Reader.rawJson(): HashMap<String, Any?> {
        val json = HashMap<String, Any?>()
        json["locationStatus"] = locationStatus.ordinal
        json["batteryStatus"] = handleBatteryLevel(batteryLevel)
        json["deviceType"] = handleDeviceType(deviceType)
        json["originalJSON"] = rawReaderData
        json["simulated"] = isSimulated
        json["label"] = label
        json["availableUpdate"] = availableUpdate?.hasFirmwareUpdate ?: false
        json["locationId"] = location?.id
        json["serialNumber"] = serialNumber


        return json
    }

    fun handleConnectionStatus(connectionStatus: ConnectionStatus): Int {
        return when (connectionStatus) {
            ConnectionStatus.NOT_CONNECTED -> 0
            ConnectionStatus.CONNECTING -> 2
            ConnectionStatus.CONNECTED -> 1
            else -> 0
        }
    }

    fun handleBatteryLevel(batteryValue: Float?): Int {
        return when {
            batteryValue == null -> 0
            batteryValue <= .05 -> 1
            batteryValue <= .20 -> 2
            batteryValue > .20 -> 3
            else -> 0
        }
    }

    fun handleDeviceType(deviceType: DeviceType): Int {
        return when (deviceType) {
            DeviceType.CHIPPER_1X -> 5
            DeviceType.CHIPPER_2X -> 0
            DeviceType.STRIPE_M2 -> 3
            DeviceType.COTS_DEVICE -> 7
            DeviceType.VERIFONE_P400 -> 1
            DeviceType.WISECUBE -> 6
            DeviceType.WISEPAD_3 -> 7
            DeviceType.WISEPOS_E -> 4
            DeviceType.ETNA -> 7
            DeviceType.UNKNOWN -> 7
            else -> 7
        }

    }

    fun PaymentMethod.rawJson(): HashMap<String, Any?> {

        val json = HashMap<String, Any?>()
        json["id"] = id
        json["metadata"] = metadata
        json["billing_details"] = HashMap<Any, Any?>()
        json["object"] = "payment_method"
        json["created"] = System.currentTimeMillis() / 1000
        json["livemode"] = livemode
        json["type"] = "card"  // Not sure why there is no type object, probably M2 can only scan cards
        json["card"] = cardDetails?.rawJson()
        json["customer"] = customer
        return json

    }

    fun CardDetails.rawJson(): HashMap<String, Any?> {
        val json = HashMap<String, Any?>()

        json["brand"] = brand
        json["country"] = country
        json["exp_month"] = expMonth
        json["exp_year"] = expYear
        json["fingerprint"] = fingerprint
        json["funding"] = funding
        json["last4"] = last4
        return json
    }
