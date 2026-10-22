package com.vesper.flipper.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE reconnaissance against arbitrary target devices via the phone's own Bluetooth adapter.
 *
 * This is intentionally decoupled from [FlipperBleService]: recon scans arbitrary devices, opens
 * short-lived GATT connections, and disconnects — sharing state with the Flipper's persistent
 * connection would create confusing failure modes. The two services happen to talk to the same
 * system-level [BluetoothAdapter] but manage entirely separate connection pools and callbacks.
 *
 * Runtime permission checks live here rather than at the executor: if a caller lacks
 * BLUETOOTH_SCAN or BLUETOOTH_CONNECT, every method returns [Result.failure] with a
 * user-actionable message rather than throwing. MainActivity handles the request UX at app
 * start; recon assumes the user has already been prompted.
 */
@Singleton
class BleReconService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Serializes GATT operations to a single target at a time — the Android BLE stack does not
     * take kindly to overlapping connect/read/write against the same device from the same client.
     */
    private val gattMutex = Mutex()
    @Volatile private var activeOperation: UUID? = null
    @Volatile private var negotiatedMtu = 23

    data class ScannedDevice(
        val address: String,
        val name: String?,
        val rssi: Int,
        val serviceUuids: List<String>,
        val manufacturerData: Map<Int, ByteArray>,
    )

    data class GattProfile(
        val services: List<GattService>,
    )

    data class GattService(
        val uuid: String,
        val characteristics: List<GattCharacteristic>,
    )

    data class GattCharacteristic(
        val uuid: String,
        val properties: List<String>,
    )

    // ─── Permission gate ──────────────────────────────────────────────────────

    private fun missingBlePermissions(): List<String> {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private suspend inline fun <T> guarded(block: suspend () -> Result<T>): Result<T> {
        try {
        val missing = missingBlePermissions()
        if (missing.isNotEmpty()) {
            return Result.failure(
                SecurityException(
                    "Missing Bluetooth permission(s): ${missing.joinToString(", ")}. " +
                        "Grant them in Settings → Apps → Vesper → Permissions."
                )
            )
        }
        val a = adapter
            ?: return Result.failure(IllegalStateException("Bluetooth is unavailable on this device."))
        if (!a.isEnabled) {
            return Result.failure(IllegalStateException("Bluetooth is turned off."))
        }
        return block()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return Result.failure(e) }
    }

    // ─── Scan ─────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission") // gated by guarded()
    suspend fun scan(
        durationMs: Long,
        nameFilter: String?,
        rssiThreshold: Int,
    ): Result<List<ScannedDevice>> = guarded {
        val scanner = adapter?.bluetoothLeScanner
            ?: return@guarded Result.failure(IllegalStateException("BLE scanner unavailable."))
        val results = ConcurrentHashMap<String, ScannedDevice>()
        val failed = CompletableDeferred<Unit>()
        val callback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                failed.completeExceptionally(IllegalStateException("BLE scan failed: $errorCode"))
            }
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                mergeResult(results, result, nameFilter, rssiThreshold)
            }

            override fun onBatchScanResults(scanResults: MutableList<ScanResult>) {
                scanResults.forEach { mergeResult(results, it, nameFilter, rssiThreshold) }
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        withContext(Dispatchers.IO) {
            try {
                scanner.startScan(null, settings, callback)
                kotlinx.coroutines.withTimeoutOrNull(durationMs.coerceIn(500L, 60_000L)) { failed.await() }
            } finally {
                try { scanner.stopScan(callback) } catch (_: SecurityException) { }
            }
        }
        Result.success(results.values.sortedByDescending { it.rssi })
    }

    private fun mergeResult(
        into: ConcurrentHashMap<String, ScannedDevice>,
        result: ScanResult,
        nameFilter: String?,
        rssiThreshold: Int,
    ) {
        if (result.rssi < rssiThreshold || into.size >= 256) return
        val record = result.scanRecord
        // Prefer scan-record name; some peripherals only expose it there.
        @SuppressLint("MissingPermission")
        val name = record?.deviceName ?: try {
            result.device.name
        } catch (_: SecurityException) {
            null
        }
        if (!nameFilter.isNullOrEmpty()) {
            val n = name ?: return
            if (!n.contains(nameFilter, ignoreCase = true)) return
        }
        val serviceUuids = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
        val mfg = mutableMapOf<Int, ByteArray>()
        record?.manufacturerSpecificData?.let { arr ->
            for (i in 0 until arr.size()) {
                mfg[arr.keyAt(i)] = arr.valueAt(i)
            }
        }
        into[result.device.address] = ScannedDevice(
            address = result.device.address,
            name = name,
            rssi = result.rssi,
            serviceUuids = serviceUuids,
            manufacturerData = mfg,
        )
    }

    // ─── GATT ops (one connection per call) ───────────────────────────────────

    suspend fun enumerate(address: String, timeoutMs: Long): Result<GattProfile> =
        withGatt(address, timeoutMs) { gatt ->
            GattProfile(
                services = gatt.services.map { svc ->
                    GattService(
                        uuid = svc.uuid.toString(),
                        characteristics = svc.characteristics.map { ch ->
                            GattCharacteristic(
                                uuid = ch.uuid.toString(),
                                properties = propertyNames(ch.properties),
                            )
                        },
                    )
                },
            )
        }

    suspend fun readCharacteristic(
        address: String,
        uuid: UUID,
        timeoutMs: Long,
    ): Result<ByteArray> = withGatt(address, timeoutMs) { gatt ->
        val (svc, ch) = findCharacteristic(gatt, uuid)
            ?: throw IllegalStateException("Characteristic $uuid not found on $address")
        require(ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) { "Characteristic is not readable" }
        val deferred = CompletableDeferred<ByteArray>()
        pendingReads[address to ch.uuid] = deferred
        @SuppressLint("MissingPermission")
        val started = gatt.readCharacteristic(ch)
        if (!started) {
            pendingReads.remove(address to ch.uuid)
            throw IllegalStateException("readCharacteristic($uuid) refused by stack (service=$svc)")
        }
        withTimeout(timeoutMs.coerceIn(500L, 60_000L)) { deferred.await() }
    }

    suspend fun writeCharacteristic(
        address: String,
        uuid: UUID,
        bytes: ByteArray,
        withResponse: Boolean,
        timeoutMs: Long,
    ): Result<Unit> = withGatt(address, timeoutMs) { gatt ->
        val (_, ch) = findCharacteristic(gatt, uuid)
            ?: throw IllegalStateException("Characteristic $uuid not found on $address")
        requireBleWriteFits(bytes.size, withResponse, negotiatedMtu)
        val requiredProperty = if (withResponse) BluetoothGattCharacteristic.PROPERTY_WRITE else BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        require(ch.properties and requiredProperty != 0) { "Characteristic does not support requested write mode" }
        val deferred = CompletableDeferred<Boolean>()
        pendingWrites[address to ch.uuid] = deferred
        @SuppressLint("MissingPermission")
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            gatt.writeCharacteristic(ch, bytes, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(ch)
        }
        if (!started) {
            pendingWrites.remove(address to ch.uuid)
            throw IllegalStateException("writeCharacteristic($uuid) refused by stack")
        }
        val ok = withTimeout(timeoutMs.coerceIn(500L, 60_000L)) { deferred.await() }
        if (!ok) throw IllegalStateException("writeCharacteristic($uuid) failed")
    }

    suspend fun subscribe(
        address: String,
        uuid: UUID,
        listenMs: Long,
        connectTimeoutMs: Long,
    ): Result<List<ByteArray>> = withGatt(address, connectTimeoutMs) { gatt ->
        val (_, ch) = findCharacteristic(gatt, uuid)
            ?: throw IllegalStateException("Characteristic $uuid not found on $address")
        val collected = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        require(ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) { "Characteristic cannot notify or indicate" }
        val cccd = ch.getDescriptor(CCCD_UUID)
            ?: throw IllegalStateException("Characteristic lacks notification descriptor")
        // Read the actual peripheral state, not Android's potentially stale descriptor cache.
        val previous = CompletableDeferred<ByteArray>()
        pendingDescriptorReads[address] = previous
        @SuppressLint("MissingPermission")
        check(gatt.readDescriptor(cccd)) { "Cannot read original notification configuration" }
        val original = withTimeout(connectTimeoutMs.coerceIn(500L, 60_000L)) { previous.await() }.copyOf()
        require(original.size == 2) { "Invalid original notification configuration" }
        val cccdValue = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        var enableAcknowledged = false
        withRestoredBleSubscription(
            enable = {
                notificationSinks[address to ch.uuid] = { synchronized(collected) { if (collected.size < 256) collected += it.copyOf() } }
                @SuppressLint("MissingPermission")
                check(gatt.setCharacteristicNotification(ch, true)) { "Local notification routing refused" }
                writeDescriptorAndAwait(gatt, address, cccd, cccdValue, connectTimeoutMs)
                enableAcknowledged = true
            },
            restore = {
                notificationSinks.remove(address to ch.uuid)
                try {
                    writeDescriptorAndAwait(gatt, address, cccd, original, 2_500L)
                    // After an unacknowledged enable, a late callback cannot prove restore success.
                    check(enableAcknowledged) { "Descriptor enable was unacknowledged; remote restoration cannot be confirmed" }
                } finally {
                    @SuppressLint("MissingPermission")
                    check(gatt.setCharacteristicNotification(ch, false)) { "Local notification cleanup refused" }
                }
            },
            collect = {
                delay(listenMs.coerceIn(500L, 120_000L))
                synchronized(collected) { collected.toList() }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeDescriptorAndAwait(
        gatt: BluetoothGatt, address: String, descriptor: BluetoothGattDescriptor,
        value: ByteArray, timeoutMs: Long,
    ) {
        val result = CompletableDeferred<Boolean>()
        pendingDescriptors[address] = result
        try {
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            check(started && withTimeout(timeoutMs.coerceIn(500L, 60_000L)) { result.await() }) {
                "Notification descriptor write failed"
            }
        } finally { pendingDescriptors.remove(address, result) }
    }

    // ─── GATT plumbing ────────────────────────────────────────────────────────

    private val pendingDescriptors = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingDescriptorReads = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    private val pendingReads =
        ConcurrentHashMap<Pair<String, UUID>, CompletableDeferred<ByteArray>>()
    private val pendingWrites =
        ConcurrentHashMap<Pair<String, UUID>, CompletableDeferred<Boolean>>()
    private val notificationSinks =
        ConcurrentHashMap<Pair<String, UUID>, (ByteArray) -> Unit>()

    private suspend fun <T> withGatt(
        address: String,
        timeoutMs: Long,
        block: suspend (BluetoothGatt) -> T,
    ): Result<T> = guarded {
        val a = adapter!!
        val device: BluetoothDevice = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return@guarded Result.failure(IllegalArgumentException("Invalid BLE address '$address'"))
        }
        gattMutex.withBoundedGattQueue(timeoutMs.coerceIn(500L, 60_000L)) {
            val operationId = UUID.randomUUID()
            activeOperation = operationId
            negotiatedMtu = 23
            val mtuReady = CompletableDeferred<Unit>()
            val connected = CompletableDeferred<BluetoothGatt>()
            val servicesReady = CompletableDeferred<BluetoothGatt>()
            val disconnected = CompletableDeferred<Unit>()
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (activeOperation != operationId) return
                    if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                        disconnected.complete(Unit)
                        val error = IllegalStateException("BLE disconnected or failed: $status")
                        connected.completeExceptionally(error)
                        servicesReady.completeExceptionally(error)
                        pendingReads.values.forEach { it.completeExceptionally(error) }
                        pendingWrites.values.forEach { it.completeExceptionally(error) }
                        pendingDescriptors.values.forEach { it.completeExceptionally(error) }
                        pendingDescriptorReads.values.forEach { it.completeExceptionally(error) }
                        mtuReady.completeExceptionally(error)
                        return
                    }
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            @SuppressLint("MissingPermission")
                            if (!gatt.discoverServices()) servicesReady.completeExceptionally(IllegalStateException("Service discovery refused"))
                            if (!connected.isCompleted) connected.complete(gatt)
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (!disconnected.isCompleted) disconnected.complete(Unit)
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (activeOperation != operationId) return
                    if (status == BluetoothGatt.GATT_SUCCESS && !servicesReady.isCompleted) {
                        servicesReady.complete(gatt)
                    } else if (status != BluetoothGatt.GATT_SUCCESS && !servicesReady.isCompleted) {
                        servicesReady.completeExceptionally(
                            IllegalStateException("discoverServices failed: status=$status")
                        )
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    if (activeOperation != operationId) return
                    val key = gatt.device.address to characteristic.uuid
                    val d = pendingReads.remove(key) ?: return
                    if (status == BluetoothGatt.GATT_SUCCESS) d.complete(value)
                    else d.completeExceptionally(IllegalStateException("read status=$status"))
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                    onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (activeOperation != operationId) return
                    if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu.coerceIn(23, 517)
                    mtuReady.complete(Unit)
                }

                override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) {
                    if (activeOperation != operationId || descriptor.uuid != CCCD_UUID) return
                    val pending = pendingDescriptorReads.remove(gatt.device.address) ?: return
                    if (status == BluetoothGatt.GATT_SUCCESS) pending.complete(value.copyOf())
                    else pending.completeExceptionally(IllegalStateException("Descriptor read failed: $status"))
                }

                @Suppress("DEPRECATION")
                override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                    onDescriptorRead(gatt, descriptor, status, descriptor.value ?: byteArrayOf())
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (activeOperation != operationId || descriptor.uuid != CCCD_UUID) return
                    pendingDescriptors.remove(gatt.device.address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (activeOperation != operationId) return
                    val key = gatt.device.address to characteristic.uuid
                    val d = pendingWrites.remove(key) ?: return
                    d.complete(status == BluetoothGatt.GATT_SUCCESS)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    if (activeOperation != operationId) return
                    val key = gatt.device.address to characteristic.uuid
                    notificationSinks[key]?.invoke(value)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                    onCharacteristicChanged(gatt, characteristic, characteristic.value ?: ByteArray(0))
                }
            }

            @SuppressLint("MissingPermission")
            val gatt = device.connectGatt(context, false, callback)
                ?: return@withBoundedGattQueue Result.failure(IllegalStateException("connectGatt returned null"))
            try {
                withTimeout(timeoutMs.coerceIn(500L, 60_000L)) {
                    connected.await()
                    servicesReady.await()
                    @SuppressLint("MissingPermission")
                    if (gatt.requestMtu(517)) {
                        kotlinx.coroutines.withTimeoutOrNull(2_000L) { mtuReady.await() }
                    }
                }
                val out = kotlinx.coroutines.coroutineScope {
                    val task = async { block(gatt) }
                    kotlinx.coroutines.selects.select<T> {
                        task.onAwait { it }
                        disconnected.onAwait { throw IllegalStateException("Target disconnected") }
                    }
                }
                Result.success(out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                activeOperation = null
                pendingReads.clear()
                pendingWrites.clear()
                pendingDescriptors.clear()
                pendingDescriptorReads.clear()
                notificationSinks.clear()
                @SuppressLint("MissingPermission")
                try { gatt.disconnect() } catch (_: SecurityException) { }
                @SuppressLint("MissingPermission")
                try { gatt.close() } catch (_: SecurityException) { }

            }
        }
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        uuid: UUID,
    ): Pair<UUID, BluetoothGattCharacteristic>? {
        val matches = gatt.services.flatMap { svc -> svc.characteristics.filter { it.uuid == uuid }.map { svc.uuid to it } }
        require(matches.size <= 1) { "Ambiguous characteristic UUID; multiple instances exist" }
        return matches.singleOrNull()
    }

    private fun propertyNames(props: Int): List<String> = buildList {
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-no-response")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        if (props and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("signed-write")
        if (props and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("broadcast")
        if (props and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) add("extended")
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
