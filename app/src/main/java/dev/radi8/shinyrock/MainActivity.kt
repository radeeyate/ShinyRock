package dev.radi8.shinyrock

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private val TAG = "PlaydateMirror"
    private val ACTION_USB_PERMISSION = "dev.radi8.shinyrock.USB_PERMISSION"

    // playdate USB IDs
    private val VENDOR_ID = 0x1331
    private val PRODUCT_ID = 0x5740

    private val SCREEN_WIDTH = 400
    private val SCREEN_HEIGHT = 240
    private val BYTES_PER_ROW = SCREEN_WIDTH / 8
    private val SCREEN_BUFFER_SIZE = BYTES_PER_ROW * SCREEN_HEIGHT

    private lateinit var usbManager: UsbManager
    private var connectedPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var isStreamingActive = false
    private val pendingDataBuffer = ByteArrayOutputStream()
    private var playdateScreenBuffer = ByteArray(SCREEN_BUFFER_SIZE)
    private lateinit var playdateBitmap: Bitmap
    private lateinit var pixelBuffer: IntArray
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pokeHandler = Handler(Looper.getMainLooper())
    private var pokeRunnable: Runnable? = null
    private val POKE_INTERVAL_MS = 800L

    private lateinit var buttonConnectDisconnect: Button
    private lateinit var textViewStatus: TextView
    private lateinit var playdateScreenView: PlaydateScreenView

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.parcelableExtraCompat(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d(TAG, "Permission granted. Connecting.")
                            connectToDevice(it)
                        }
                    } else {
                        Log.e(TAG, "Permission denied for device $device")
                        updateStatus("USB permission denied.")
                        runOnUiThread { buttonConnectDisconnect.isEnabled = true }
                    }
                }
            }
        }
    }

    inline fun <reified T : Any> Intent.parcelableExtraCompat(key: String): T? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(key, T::class.java)
        else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonConnectDisconnect = findViewById(R.id.buttonConnectDisconnect)
        textViewStatus = findViewById(R.id.textViewStatus)
        playdateScreenView = findViewById(R.id.playdateScreenView)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        playdateBitmap = Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
        pixelBuffer = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)
        clearScreen()

        val intentFilter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, intentFilter)
        }

        buttonConnectDisconnect.setOnClickListener {
            if (connectedPort == null) {
                findAndConnectPlaydate()
            } else {
                disconnect()
            }
        }
        updateStatus("Status: Disconnected")

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = intent.parcelableExtraCompat(UsbManager.EXTRA_DEVICE)
            device?.let {
                if (it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID) {
                    Log.d(TAG, "App launched/resumed by Playdate attach intent.")

                    // connect directly if permission exists, otherwise request
                    if (usbManager.hasPermission(it)) {
                        connectToDevice(it)
                    } else {
                        requestUsbPermission(it)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        stopPokeTimer()
        disconnect()
        backgroundExecutor.shutdown()
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            textViewStatus.text = message
        }
    }

    private fun clearScreen() {
        playdateScreenBuffer.fill(0) // 0 = white in our buffer
        renderScreenBufferToBitmap()
        runOnUiThread {
            playdateScreenView.updateScreenBitmap(playdateBitmap)
        }
    }

    private fun findAndConnectPlaydate() {
        updateStatus("Status: Searching...")
        buttonConnectDisconnect.isEnabled = false
        val foundDevice = usbManager.deviceList.values.find { it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID }

        if (foundDevice == null) {
            updateStatus("Status: Playdate not found.")
            Toast.makeText(this, "Playdate not found", Toast.LENGTH_LONG).show()
            runOnUiThread { buttonConnectDisconnect.isEnabled = true }
            return
        }

        if (!usbManager.hasPermission(foundDevice)) {
            requestUsbPermission(foundDevice)
        } else {
            connectToDevice(foundDevice)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        updateStatus("Status: Requesting USB permission...")
        val flags = PendingIntent.FLAG_IMMUTABLE
        val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun connectToDevice(device: UsbDevice) {
        if (connectedPort != null) {
            Log.w(TAG, "Already connected, ignoring connect request.")
            return
        }
        updateStatus("Status: Connecting...")
        runOnUiThread { buttonConnectDisconnect.isEnabled = false } // prevent multiple clicks

        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            updateStatus("Status: No compatible driver.")
            Log.e(TAG,"No driver found")
            runOnUiThread { buttonConnectDisconnect.isEnabled = true }
            return
        }
        if (driver.ports.isEmpty()) {
            updateStatus("Status: No serial ports.")
            Log.e(TAG,"No ports found")
            runOnUiThread { buttonConnectDisconnect.isEnabled = true }
            return
        }

        val port = driver.ports[0]
        val connection: UsbDeviceConnection? = usbManager.openDevice(driver.device)
        if (connection == null) {
            updateStatus("Status: Failed to open device.")
            Log.e(TAG,"Failed to open connection")
            if (!usbManager.hasPermission(driver.device)) {
                updateStatus("Status: USB permission missing?")
                // maybe request permission again here, but it should have been granted
            }
            runOnUiThread { buttonConnectDisconnect.isEnabled = true }
            return
        }

        try {
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            connectedPort = port
            startSerialReader() // start listening for data before enabling stream

            if (!sendCommand("stream enable")) {
                throw IOException("Failed to send 'stream enable' command.")
            }

            isStreamingActive = true
            updateStatus("Status: Connected & Streaming")
            Log.i(TAG, "Playdate connected and stream enabled.")
            clearScreen() // clear screen on successful connect
            startPokeTimer() // keep the stream alive

            runOnUiThread {
                buttonConnectDisconnect.text = "Disconnect"
                buttonConnectDisconnect.isEnabled = true
            }

        } catch (e: IOException) {
            Log.e(TAG, "Error connecting or enabling stream", e)
            updateStatus("Status: Connection Error: ${e.message}")
            disconnect()
            runOnUiThread { buttonConnectDisconnect.isEnabled = true }
        }
    }

    private fun disconnect() {
        Log.d(TAG, "Disconnect called")
        stopPokeTimer()
        stopSerialReader()

        if (connectedPort != null) {
            if (isStreamingActive) {
                sendCommand("stream disable")
                isStreamingActive = false
            }
            try {
                connectedPort?.close()
                Log.i(TAG, "Serial port closed.")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing serial port", e)
            }
            connectedPort = null
        }
        isStreamingActive = false
        pendingDataBuffer.reset()

        updateStatus("Status: Disconnected")
        runOnUiThread {
            buttonConnectDisconnect.text = "Connect to Playdate"
            buttonConnectDisconnect.isEnabled = true
            clearScreen()
            playdateScreenView.updateScreenBitmap(null)
        }
    }

    private fun startSerialReader() {
        if (connectedPort != null && serialIoManager == null) {
            serialIoManager = SerialInputOutputManager(connectedPort!!, this)
            serialIoManager?.readTimeout = 1000
            serialIoManager?.setListener(this)

            serialIoManager?.start()
            Log.d(TAG, "Serial IO Manager submitted to executor.")
        }
    }

    private fun stopSerialReader() {
        if (serialIoManager != null) {
            Log.d(TAG, "Stopping Serial IO Manager.")
            serialIoManager?.stop()
            serialIoManager = null
        }
    }

    override fun onNewData(data: ByteArray) {
        if (!isStreamingActive) return // ignore data if not streaming

        synchronized(pendingDataBuffer) {
            pendingDataBuffer.write(data)
            processBufferedData()
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial read/write error", e)
        updateStatus("Status: Connection Error: ${e.message}")
        mainHandler.post { disconnect() }
    }

    private fun processBufferedData() {
        val buffer = pendingDataBuffer.toByteArray()
        var currentOffset = 0
        val bufferSize = buffer.size

        while (currentOffset + 4 <= bufferSize) {
            val headerBuffer = ByteBuffer.wrap(buffer, currentOffset, 4).order(ByteOrder.LITTLE_ENDIAN)
            val messageType = headerBuffer.short.toInt() and 0xFFFF // read unsigned short
            val payloadLength = headerBuffer.short.toInt() and 0xFFFF

            val messageEndOffset = currentOffset + 4 + payloadLength
            if (messageEndOffset <= bufferSize) {
                val payload = buffer.sliceArray(currentOffset + 4 until messageEndOffset)
                processStreamMessage(messageType, payload)

                currentOffset = messageEndOffset
            } else {
                break
            }
        }

        if (currentOffset > 0) {
            pendingDataBuffer.reset()
            if (currentOffset < bufferSize) {
                pendingDataBuffer.write(buffer, currentOffset, bufferSize - currentOffset)
            }
        }
    }

    private fun processStreamMessage(type: Int, payload: ByteArray) {
        //Log.d(TAG, "Processing msg Type: 0x${type.toString(16)}, Len: ${payload.size}")
        when (type) {
            0x0001 -> {
                // input state
                // could parse and display button/crank state if desired
            }
            0x000A, 0x000D -> {} // new frame
            0x000B -> {} // end frame

            0x000C -> { // update screen line
                if (payload.size == 52) {
                    val lineNumByte = payload[0].toInt() and 0xFF
                    val actualLineNum = reverseBits(lineNumByte) // 1-based line number

                    if (actualLineNum >= 1 && actualLineNum <= SCREEN_HEIGHT) {
                        val rowIndex = actualLineNum - 1 // 0-based index
                        val byteOffset = rowIndex * BYTES_PER_ROW
                        // copy the 50 bytes of line data into our screen buffer
                        System.arraycopy(payload, 1, playdateScreenBuffer, byteOffset, BYTES_PER_ROW)

                        renderScreenBufferToBitmap()
                        mainHandler.post {
                            playdateScreenView.updateScreenBitmap(playdateBitmap)
                        }
                    } else {
                        Log.w(TAG, "Invalid screen line number byte: 0x${lineNumByte.toString(16)} (Decoded: $actualLineNum)")
                    }
                } else {
                    Log.w(TAG, "Invalid payload size for Screen Line Update: ${payload.size}")
                }
            }
            0x0014 -> {} // audio frames (multiple)
            0x0015 -> { // audio format switch ack
                val fmtBuffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val flags = fmtBuffer.short.toInt() and 0xFFFF
                Log.d(TAG, "Audio Format Ack: Flags=0x${flags.toString(16)}")
            }
            0x0016 -> {} // audio frames (fill single)

            else -> Log.w(TAG, "Unknown message type received: 0x${type.toString(16)}")
        }
    }

    private fun reverseBits(byteInput: Int): Int {
        var byte = byteInput
        var result = 0
        for (i in 0..7) {
            result = (result shl 1) or (byte and 1)
            byte = byte shr 1
        }
        return result
    }

    private fun renderScreenBufferToBitmap() {
        val black = Color.rgb(49, 47, 40)
        val white = Color.rgb(177, 175, 168)

        for (y in 0 until SCREEN_HEIGHT) {
            val rowOffsetBytes = y * BYTES_PER_ROW
            val rowOffsetPixels = y * SCREEN_WIDTH

            for (byteIndex in 0 until BYTES_PER_ROW) {
                val currentByte = playdateScreenBuffer[rowOffsetBytes + byteIndex].toInt() and 0xFF
                val pixelOffset = rowOffsetPixels + byteIndex * 8

                for (bitIndex in 0..7) {
                    val bitToCheck = 7 - bitIndex
                    val isBlack = ((currentByte shr bitToCheck) and 1) == 1

                    pixelBuffer[pixelOffset + bitIndex] = if (isBlack) white else black
                }
            }
        }

        playdateBitmap.setPixels(pixelBuffer, 0, SCREEN_WIDTH, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
    }


    private fun startPokeTimer() {
        stopPokeTimer()
        Log.d(TAG, "Starting poke timer")
        pokeRunnable = Runnable {
            if (connectedPort != null && isStreamingActive) {
                Log.v(TAG, "Sending stream poke")
                if (!sendCommand("stream poke")) {
                    Log.w(TAG, "Failed to send stream poke, connection might be lost.")
                }

                pokeHandler.postDelayed(pokeRunnable!!, POKE_INTERVAL_MS)
            }
        }
        pokeHandler.postDelayed(pokeRunnable!!, POKE_INTERVAL_MS)
    }

    private fun stopPokeTimer() {
        Log.d(TAG, "Stopping poke timer")
        pokeRunnable?.let { pokeHandler.removeCallbacks(it) }
        pokeRunnable = null
    }

    private fun sendCommand(command: String): Boolean {
        if (connectedPort == null) {
            Log.w(TAG, "Cannot send command, port is null.")
            return false
        }
        return try {
            val commandBytes = (command + "\n").toByteArray()
            connectedPort?.write(commandBytes, 500)
            Log.v(TAG, "Sent command: $command")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error sending command '$command'", e)
            mainHandler.post { updateStatus("Status: Write Error") }
            false
        }
    }
}