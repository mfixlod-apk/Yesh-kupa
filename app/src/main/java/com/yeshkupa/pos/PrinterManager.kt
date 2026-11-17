package com.yeshkupa.pos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Basic ESC/POS printer discovery and test printing.
 * USB: Android USB Host + bulk OUT endpoint.
 * Bluetooth: already-paired classic Bluetooth printers using SPP.
 */
class PrinterManager(
    private val activity: Activity,
    private val onStatus: (String) -> Unit
) {
    private val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val bluetoothAdapter = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val permissionAction = activity.packageName + ".USB_PRINTER_PERMISSION"
    private val main = Handler(Looper.getMainLooper())
    private var receiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device == null) {
                status("לא התקבל זיהוי של מדפסת USB")
                return
            }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                printUsbTest(device)
            } else {
                status("לא אושרה גישה למדפסת USB")
            }
        }
    }

    fun scan() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        val usbDevices = usbManager.deviceList.values.toList()
        usbDevices.forEach { device ->
            options.add("USB • ${device.productName ?: "התקן USB"} (VID ${device.vendorId}, PID ${device.productId})" to {
                connectUsb(device)
            })
        }

        if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
            }
            showOptions(options, "USB")
            return
        }

        try {
            bluetoothAdapter?.bondedDevices?.forEach { device ->
                options.add("Bluetooth • ${device.name ?: "מדפסת Bluetooth"} • ${device.address}" to {
                    connectBluetooth(device)
                })
            }
        } catch (_: SecurityException) {
            status("אין הרשאה לסריקת Bluetooth")
        }
        showOptions(options, "USB או Bluetooth")
    }

    private fun showOptions(options: List<Pair<String, () -> Unit>>, source: String) {
        main.post {
            if (options.isEmpty()) {
                status("לא נמצאה מדפסת מחוברת ב-$source. בדוק כבל USB/OTG או צימוד Bluetooth ולחץ סריקה שוב.")
                return@post
            }
            AlertDialog.Builder(activity)
                .setTitle("מדפסות והתקנים שנמצאו")
                .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second.invoke() }
                .setNegativeButton("סגור", null)
                .show()
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
            printUsbTest(device)
        }
    }

    private fun printUsbTest(device: UsbDevice) {
        thread {
            var connection: UsbDeviceConnection? = null
            try {
                connection = usbManager.openDevice(device) ?: throw IllegalStateException("לא ניתן לפתוח את התקן ה-USB")
                var selectedInterface: android.hardware.usb.UsbInterface? = null
                var output: android.hardware.usb.UsbEndpoint? = null
                search@ for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    for (e in 0 until intf.endpointCount) {
                        val endpoint = intf.getEndpoint(e)
                        if (endpoint.direction == UsbConstants.USB_DIR_OUT &&
                            endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            selectedInterface = intf
                            output = endpoint
                            break@search
                        }
                    }
                }
                val intf = selectedInterface ?: throw IllegalStateException("לא נמצא ערוץ USB להדפסה (Bulk OUT)")
                val endpoint = output ?: throw IllegalStateException("לא נמצא ערוץ יציאה למדפסת")
                if (!connection.claimInterface(intf, true)) throw IllegalStateException("לא ניתן לקבל שליטה על ממשק המדפסת")
                val bytes = escPosTest()
                val sent = connection.bulkTransfer(endpoint, bytes, bytes.size, 5000)
                if (sent > 0) status("נשלחה הדפסת ניסיון דרך USB ($sent בתים)")
                else status("המדפסת זוהתה ב-USB אך לא קיבלה נתוני הדפסה")
                connection.releaseInterface(intf)
            } catch (e: Exception) {
                status("שגיאת USB: ${e.message ?: "לא ידועה"}")
            } finally {
                connection?.close()
            }
        }
    }

    private fun connectBluetooth(device: android.bluetooth.BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
            status("נדרשת הרשאת Bluetooth. אשר אותה ולחץ שוב על סריקה.")
            return
        }
        status("מתחבר למדפסת Bluetooth…")
        thread {
            var socket: android.bluetooth.BluetoothSocket? = null
            try {
                bluetoothAdapter?.cancelDiscovery()
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                socket.outputStream.use { stream ->
                    stream.write(escPosTest())
                    stream.flush()
                }
                status("נשלחה הדפסת ניסיון דרך Bluetooth")
            } catch (e: Exception) {
                status("חיבור/הדפסה ב-Bluetooth נכשלו: ${e.message ?: "בדוק שהמדפסת תומכת SPP"}")
            } finally {
                try { socket?.close() } catch (_: Exception) { }
            }
        }
    }

    private fun escPosTest(): ByteArray {
        val header = byteArrayOf(0x1B, 0x40)
        val body = "YESH KUPA\nPRINTER TEST\nUSB / BLUETOOTH\n\n\n".toByteArray(Charsets.US_ASCII)
        val feedAndCut = byteArrayOf(0x1D, 0x56, 0x00)
        return header + body + feedAndCut
    }

    private fun status(message: String) = main.post { onStatus(message) }

    companion object {
        const val REQUEST_BLUETOOTH = 741
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
