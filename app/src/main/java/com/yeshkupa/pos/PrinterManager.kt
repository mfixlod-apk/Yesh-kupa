package com.yeshkupa.pos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.util.UUID
import kotlin.concurrent.thread

/** USB Host and paired classic Bluetooth SPP transport for ESC/POS printers. */
class PrinterManager(
    private val activity: Activity,
    private val onStatus: (String) -> Unit
) {
    private val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val bluetoothAdapter = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val permissionAction = activity.packageName + ".USB_PRINTER_PERMISSION"
    private val main = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    @Volatile private var selectedUsb: UsbDevice? = null
    @Volatile private var selectedBluetooth: BluetoothDevice? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device == null) { status("לא התקבל זיהוי של מדפסת USB"); return }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                selectedUsb = device
                selectedBluetooth = null
                status("מדפסת USB נבחרה. שולח דף בדיקה…")
                sendUsb(device, testBytes())
            } else status("לא אושרה גישה למדפסת USB")
        }
    }

    fun scan() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        usbManager.deviceList.values.forEach { device ->
            options.add("USB • ${device.productName ?: "התקן USB"} (VID ${device.vendorId}, PID ${device.productId})" to { connectUsb(device) })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
            showOptions(options, "USB")
            return
        }
        try {
            bluetoothAdapter?.bondedDevices?.forEach { device ->
                options.add("Bluetooth • ${device.name ?: "מדפסת"} • ${device.address}" to { connectBluetooth(device) })
            }
        } catch (_: SecurityException) { status("אין הרשאה לסריקת Bluetooth") }
        showOptions(options, "USB או Bluetooth")
    }

    fun printReceipt(text: String) {
        val bytes = renderEscPos(text)
        val usb = selectedUsb
        val bluetooth = selectedBluetooth
        when {
            usb != null -> sendUsb(usb, bytes)
            bluetooth != null -> sendBluetooth(bluetooth, bytes)
            else -> status("לא נבחרה מדפסת. היכנס להגדרות וסרוק מדפסות תחילה.")
        }
    }

    private fun showOptions(options: List<Pair<String, () -> Unit>>, source: String) {
        main.post {
            if (options.isEmpty()) {
                status("לא נמצאה מדפסת ב-$source. בדוק כבל USB/OTG או צימוד Bluetooth.")
                return@post
            }
            AlertDialog.Builder(activity)
                .setTitle("בחר מדפסת")
                .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second.invoke() }
                .setNegativeButton("סגור", null).show()
        }
    }

    private fun connectUsb(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            val intent = Intent(permissionAction).setPackage(activity.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(activity, device.deviceId, intent, flags)
            if (!receiverRegistered) {
                val filter = IntentFilter(permissionAction)
                if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else activity.registerReceiver(usbPermissionReceiver, filter)
                receiverRegistered = true
            }
            usbManager.requestPermission(device, pending)
            status("מבקש הרשאה למדפסת USB…")
        } else {
            selectedUsb = device
            selectedBluetooth = null
            status("מדפסת USB נבחרה. שולח דף בדיקה…")
            sendUsb(device, testBytes())
        }
    }

    private fun sendUsb(device: UsbDevice, bytes: ByteArray) {
        thread {
            var connection: UsbDeviceConnection? = null
            var claimed: android.hardware.usb.UsbInterface? = null
            try {
                connection = usbManager.openDevice(device) ?: throw IllegalStateException("לא ניתן לפתוח את התקן ה-USB")
                var endpoint: android.hardware.usb.UsbEndpoint? = null
                search@ for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    for (e in 0 until intf.endpointCount) {
                        val candidate = intf.getEndpoint(e)
                        if (candidate.direction == UsbConstants.USB_DIR_OUT && candidate.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            claimed = intf
                            endpoint = candidate
                            break@search
                        }
                    }
                }
                val intf = claimed ?: throw IllegalStateException("לא נמצא ערוץ USB מסוג Bulk OUT")
                val out = endpoint ?: throw IllegalStateException("לא נמצא ערוץ יציאה")
                if (!connection.claimInterface(intf, true)) throw IllegalStateException("לא ניתן לקבל שליטה על ממשק המדפסת")
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(16 * 1024, bytes.size - offset)
                    val chunk = bytes.copyOfRange(offset, offset + count)
                    val sent = connection.bulkTransfer(out, chunk, chunk.size, 10000)
                    if (sent <= 0) throw IllegalStateException("המדפסת לא קיבלה נתונים")
                    offset += sent
                }
                status("הנתונים נשלחו למדפסת USB")
            } catch (e: Exception) {
                status("שגיאת USB: ${e.message ?: "לא ידועה"}")
            } finally {
                try { if (claimed != null) connection?.releaseInterface(claimed) } catch (_: Exception) { }
                connection?.close()
            }
        }
    }

    private fun connectBluetooth(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
            status("נדרשת הרשאת Bluetooth. אשר אותה ולחץ שוב על סריקה.")
            return
        }
        selectedBluetooth = device
        selectedUsb = null
        status("מדפסת Bluetooth נבחרה. שולח דף בדיקה…")
        sendBluetooth(device, testBytes())
    }

    private fun sendBluetooth(device: BluetoothDevice, bytes: ByteArray) {
        thread {
            var socket: android.bluetooth.BluetoothSocket? = null
            try {
                bluetoothAdapter?.cancelDiscovery()
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                socket.outputStream.use { it.write(bytes); it.flush() }
                status("הנתונים נשלחו למדפסת Bluetooth")
            } catch (e: Exception) {
                status("שגיאת Bluetooth: ${e.message ?: "בדוק תמיכה בחיבור SPP"}")
            } finally { try { socket?.close() } catch (_: Exception) { } }
        }
    }

    /** Render Hebrew/RTL text to a monochrome ESC/POS raster image (80mm, 576 dots). */
    private fun renderEscPos(text: String): ByteArray {
        val width = 576
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 28)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(true)
            .build()
        val height = maxOf(layout.height + 24, 64)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.translate(14f, 12f)
        layout.draw(canvas)
        canvas.restore()

        val rowBytes = width / 8
        val raster = ByteArray(rowBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (Color.red(bitmap.getPixel(x, y)) < 160) {
                    val index = y * rowBytes + x / 8
                    raster[index] = (raster[index].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }
        bitmap.recycle()
        val header = byteArrayOf(
            0x1B, 0x40,
            0x1D, 0x76, 0x30, 0x00,
            (rowBytes and 0xFF).toByte(), ((rowBytes shr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte()
        )
        return header + raster + byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x00)
    }

    private fun testBytes(): ByteArray = renderEscPos("יש קופה\nבדיקת מדפסת\nUSB / BLUETOOTH\n")
    private fun status(message: String) = main.post { onStatus(message) }

    companion object {
        const val REQUEST_BLUETOOTH = 741
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
