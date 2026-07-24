package com.pdtool.voltage

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import com.pdtool.voltage.databinding.ActivityMainBinding
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }
    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<String>()
    private val firmwareRepository = FirmwareRepository()
    private lateinit var firmwareCache: FirmwareCache

    @Volatile
    private var session: UsbHidSession? = null
    private var isBusy = false
    private var hasReadStatus = false
    private var lastStatus: PdUsbProtocol.DeviceStatus? = null
    private var bootloaderInfo: FirmwareProtocol.BootloaderInfo? = null
    private var firmwareRelease: FirmwareRepository.Release? = null
    private var firmwareImage: FirmwareProtocol.Image? = null
    private var firmwareFromCache = false
    private var awaitingMode: UsbMode? = null
    private var pendingPermissionDeviceName: String? = null

    private enum class UsbMode { APP, BOOTLOADER }

    private data class PendingSystemCommand(
        val command: Int,
        val label: String,
        val value: Int? = null,
        val waitForAck: Boolean = true,
        val causesPdReset: Boolean = false,
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    pendingPermissionDeviceName = null
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    binding.statusText.text = if (granted) {
                        getString(R.string.usb_permission_granted)
                    } else {
                        getString(R.string.usb_permission_denied)
                    }
                    appendLog(if (granted) "USB 权限已授予：${device?.let(::profileName) ?: "PD HID"}" else "USB 权限被拒绝")
                    refreshUsbDevices(autoConnect = granted)
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("检测到 USB 设备接入")
                    binding.root.postDelayed({ refreshUsbDevices(autoConnect = true) }, 300)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("USB 设备已拔出或正在重新枚举")
                    hasReadStatus = false
                    lastStatus = null
                    bootloaderInfo = null
                    pendingPermissionDeviceName = null
                    closeSessionAsync()
                    binding.root.postDelayed({ refreshUsbDevices(autoConnect = true) }, 500)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firmwareCache = FirmwareCache(this)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        registerUsbReceiver()
        setupSpinners()
        setupNavigation()
        setupInputPreview()
        setupActions()
        showPage(PAGE_CONTROL)
        refreshUsbDevices(autoConnect = true)
    }

    override fun onResume() {
        super.onResume()
        refreshUsbDevices(autoConnect = true)
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        ioExecutor.execute {
            session?.close()
            session = null
        }
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun setupNavigation() {
        binding.tabControl.setOnClickListener { showPage(PAGE_CONTROL) }
        binding.tabSystem.setOnClickListener { showPage(PAGE_SYSTEM) }
        binding.tabTools.setOnClickListener { showPage(PAGE_TOOLS) }
    }

    private fun showPage(page: Int) {
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        binding.pageFlipper.displayedChild = page
        styleTab(binding.tabControl, page == PAGE_CONTROL)
        styleTab(binding.tabSystem, page == PAGE_SYSTEM)
        styleTab(binding.tabTools, page == PAGE_TOOLS)
    }

    private fun styleTab(button: Button, selected: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (selected) "#E8EEF9" else "#FFFFFF"),
        )
        button.setTextColor(Color.parseColor(if (selected) "#1D4ED8" else "#667085"))
    }

    private fun setupSpinners() {
        bindSpinner(binding.languageSpinner, listOf(
            getString(R.string.language_system_default),
            "中文－简体", "中文－繁體", "日本語", "English", "한국어",
            "ไทย", "Tiếng Việt", "हिन्दी", "Bahasa Indonesia",
            "Français", "Deutsch", "Español", "Italiano", "Русский",
            "Português", "Nederlands",
        ))
        val selectedLanguage = LANGUAGE_TAGS.indexOf(AppLocale.currentTag(this)).coerceAtLeast(0)
        binding.languageSpinner.setSelection(selectedLanguage)
        bindSpinner(binding.prioritySpinner, listOf("Forward（轉發）", "Reply（應答）"))
        bindSpinner(binding.pdModeSpinner, listOf("SPR", "EPR", "PROP", "PD3.2"))
        bindSpinner(binding.reportSpinner, listOf("Std（標準）", "Mini（精簡）"))
        bindSpinner(binding.vbusModeSpinner, listOf("始終關閉", "始終開啟", "保持"))
        bindSpinner(binding.triggerHoldSpinner, listOf("關閉", "開啟", "簡單", "預設"))
        bindSpinner(binding.triggerTimingSpinner, listOf("立即", "延時"))
    }

    private fun bindSpinner(spinner: Spinner, values: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, values).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun setupInputPreview() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePowerPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        binding.voltageInput.addTextChangedListener(watcher)
        binding.currentInput.addTextChangedListener(watcher)
        updatePowerPreview()
    }

    private fun setupActions() {
        binding.applyLanguageButton.setOnClickListener {
            val tag = LANGUAGE_TAGS[binding.languageSpinner.selectedItemPosition]
            if (tag != AppLocale.currentTag(this)) {
                AppLocale.setTag(this, tag)
                recreate()
            }
        }
        binding.refreshButton.setOnClickListener { refreshUsbDevices(autoConnect = true) }
        binding.permissionButton.setOnClickListener { requestFirstSupportedDevicePermission() }
        binding.queryButton.setOnClickListener { queryDeviceStatus() }
        binding.applyButton.setOnClickListener { confirmAndApplyPreset() }
        binding.applySystemButton.setOnClickListener { confirmAndApplySystemSettings() }
        binding.mcuRebootButton.setOnClickListener { confirmMcuReboot() }
        binding.usbPdRebootButton.setOnClickListener { confirmUsbPdReboot() }
        binding.pullFirmwareButton.setOnClickListener { pullLatestFirmware() }
        binding.bootloaderButton.setOnClickListener { confirmBootloaderTransition() }
        binding.flashFirmwareButton.setOnClickListener { confirmFlashFirmware() }
        binding.clearLogButton.setOnClickListener {
            logLines.clear()
            binding.eventLogText.text = getString(R.string.log_empty)
        }

        mapOf(
            binding.quick5Button to "5.00",
            binding.quick9Button to "9.00",
            binding.quick12Button to "12.00",
            binding.quick15Button to "15.00",
            binding.quick20Button to "20.00",
            binding.quick28Button to "28.00",
        ).forEach { (button, value) ->
            button.setOnClickListener { binding.voltageInput.setText(value) }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun refreshUsbDevices(autoConnect: Boolean) {
        val devices = usbManager.deviceList.values.sortedWith(
            compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId },
        )
        val supported = devices.filter(::isSupportedDevice)
        val target = selectSupportedDevice(supported)
        val targetReady = target != null && usbManager.hasPermission(target) && when (usbMode(target)) {
            UsbMode.APP -> hasReadStatus
            UsbMode.BOOTLOADER -> bootloaderInfo != null
            null -> false
        }

        binding.statusText.text = when {
            targetReady -> getString(R.string.device_connected, profileName(target!!))
            supported.isNotEmpty() -> getString(R.string.supported_device_found, supported.size)
            devices.isNotEmpty() -> getString(R.string.usb_devices_found, devices.size)
            else -> getString(R.string.no_usb_device)
        }
        binding.deviceSummary.text = target?.let {
            getString(
                R.string.device_summary_format,
                it.vendorId,
                it.productId,
                if (usbManager.hasPermission(it)) getString(R.string.permission_yes) else getString(R.string.permission_no),
            )
        } ?: getString(R.string.connect_hint)

        binding.deviceDetails.text = if (devices.isEmpty()) {
            getString(R.string.connect_hint)
        } else {
            devices.joinToString(separator = "\n\n") { device ->
                val permission = if (usbManager.hasPermission(device)) {
                    getString(R.string.permission_yes)
                } else {
                    getString(R.string.permission_no)
                }
                getString(
                    R.string.device_line,
                    profileName(device),
                    device.vendorId,
                    device.productId,
                    device.interfaceCount,
                    permission,
                )
            }
        }

        binding.connectionDot.setTextColor(
            Color.parseColor(
                when {
                    targetReady -> "#12B76A"
                    target != null -> "#F79009"
                    else -> "#98A2B3"
                },
            ),
        )

        if (target == null) {
            hasReadStatus = false
            lastStatus = null
            bootloaderInfo = null
            binding.protocolStatus.text = getString(R.string.protocol_waiting)
            binding.systemStatus.text = getString(R.string.protocol_waiting)
            clearStatusSummary()
            closeSessionAsync()
        } else if (autoConnect && !isBusy) {
            val mode = usbMode(target)
            val waitingForDifferentMode = awaitingMode != null && awaitingMode != mode
            when {
                waitingForDifferentMode -> Unit
                !usbManager.hasPermission(target) && awaitingMode == mode -> requestPermissionForDevice(target)
                usbManager.hasPermission(target) && session?.device?.deviceName != target.deviceName -> connectDevice(target)
            }
        }

        renderFirmwareStatus()
        updateEnabledState()
    }

    private fun requestFirstSupportedDevicePermission() {
        val device = selectSupportedDevice(usbManager.deviceList.values.filter(::isSupportedDevice))
            ?.takeIf { !usbManager.hasPermission(it) }
            ?: run {
                refreshUsbDevices(autoConnect = true)
                return
            }
        requestPermissionForDevice(device)
    }

    private fun requestPermissionForDevice(device: UsbDevice) {
        if (pendingPermissionDeviceName == device.deviceName) return
        pendingPermissionDeviceName = device.deviceName
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
        binding.statusText.text = if (isBootloaderDevice(device)) {
            getString(R.string.bl_permission_required)
        } else {
            getString(R.string.waiting_for_permission)
        }
    }

    private fun connectDevice(device: UsbDevice) {
        setBusy(true, getString(R.string.connecting_hid))
        appendLog("连接 ${profileName(device)}")
        ioExecutor.execute {
            try {
                session?.close()
                val opened = UsbHidSession.open(usbManager, device)
                session = opened
                if (isBootloaderDevice(device)) {
                    val info = opened.queryBootloaderInfo()
                    runOnUiThread { showBootloader(info) }
                } else {
                    val status = opened.queryStatus()
                    runOnUiThread { showStatus(status, getString(R.string.status_synced)) }
                }
            } catch (error: Throwable) {
                session?.close()
                session = null
                runOnUiThread { showIoError(error) }
            }
        }
    }

    private fun showBootloader(info: FirmwareProtocol.BootloaderInfo) {
        bootloaderInfo = info
        hasReadStatus = false
        lastStatus = null
        if (awaitingMode == UsbMode.BOOTLOADER) awaitingMode = null
        binding.protocolStatus.text = getString(R.string.bootloader_connected, info.version, info.hardwareType ?: "?")
        binding.systemStatus.text = getString(R.string.bootloader_connected, info.version, info.hardwareType ?: "?")
        appendLog("Bootloader ${info.version} / ${info.hardwareType ?: "unknown"} 已连接")
        if (!info.isSupported) appendLog(getString(R.string.bootloader_too_old))
        setBusy(false)
        refreshUsbDevices(autoConnect = false)
    }

    private fun pullLatestFirmware() {
        val device = supportedDevice() ?: return
        val variant = firmwareVariant(device)
        val expectedHardware = hardwareType(device)
        isBusy = true
        binding.progressBar.visibility = View.VISIBLE
        binding.firmwareStatus.text = getString(R.string.firmware_fetching)
        updateEnabledState()
        appendLog(getString(R.string.firmware_preparing, variant.uppercase()))
        ioExecutor.execute {
            try {
                var cached = firmwareCache.load(variant)
                if (cached != null && cached.image.hardwareType != null &&
                    cached.image.hardwareType != expectedHardware
                ) {
                    firmwareCache.remove(variant)
                    cached = null
                }

                val release: FirmwareRepository.Release
                val image: FirmwareProtocol.Image
                val fromCache: Boolean
                if (cached != null) {
                    release = cached.release
                    image = cached.image
                    fromCache = true
                } else {
                    release = firmwareRepository.latest(variant)
                    val bytes = firmwareRepository.download(release)
                    image = FirmwareProtocol.parseImage(bytes)
                    require(image.hardwareType == null || image.hardwareType == expectedHardware) {
                        getString(
                            R.string.firmware_hardware_mismatch,
                            image.hardwareType ?: "?",
                            expectedHardware,
                        )
                    }
                    firmwareCache.save(release, bytes)
                    fromCache = false
                }

                firmwareRelease = release
                firmwareImage = image
                firmwareFromCache = fromCache
                runOnUiThread {
                    appendLog(
                        getString(
                            if (fromCache) R.string.firmware_cache_used else R.string.firmware_cached,
                            release.tag,
                        ),
                    )
                    setBusy(false)
                    renderFirmwareStatus()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    val message = error.message ?: error.javaClass.simpleName
                    binding.firmwareStatus.text = getString(R.string.firmware_download_failed, message)
                    appendLog(getString(R.string.firmware_fetch_failed_log, message))
                    setBusy(false)
                }
            }
        }
    }

    private fun confirmBootloaderTransition() {
        val device = supportedDevice() ?: return
        if (isBootloaderDevice(device)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_exit_bl_title)
                .setMessage(R.string.confirm_exit_bl_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> exitBootloader() }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_enter_bl_title)
                .setMessage(R.string.confirm_enter_bl_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> enterBootloader() }
                .show()
        }
    }

    private fun enterBootloader() {
        val device = supportedDevice()?.takeIf(::isSupportedAppDevice) ?: return
        setBusy(true, getString(R.string.switching_to_bl))
        appendLog("发送进入 Bootloader 命令")
        ioExecutor.execute {
            try {
                activeSession(device).enterBootloader()
                session?.close()
                session = null
                runOnUiThread {
                    awaitingMode = UsbMode.BOOTLOADER
                    hasReadStatus = false
                    lastStatus = null
                    bootloaderInfo = null
                    setBusy(false, getString(R.string.switching_to_bl))
                    binding.firmwareStatus.text = getString(R.string.switching_to_bl)
                    binding.root.postDelayed({ refreshUsbDevices(autoConnect = true) }, 1_500)
                }
            } catch (error: Throwable) {
                runOnUiThread { showIoError(error) }
            }
        }
    }

    private fun exitBootloader() {
        val device = supportedDevice()?.takeIf(::isBootloaderDevice) ?: return
        setBusy(true, getString(R.string.switching_to_app))
        appendLog("发送退出 Bootloader 命令")
        ioExecutor.execute {
            try {
                activeSession(device).exitBootloader()
                session?.close()
                session = null
                runOnUiThread {
                    awaitingMode = UsbMode.APP
                    bootloaderInfo = null
                    setBusy(false, getString(R.string.switching_to_app))
                    binding.firmwareStatus.text = getString(R.string.switching_to_app)
                    binding.root.postDelayed({ refreshUsbDevices(autoConnect = true) }, 1_500)
                }
            } catch (error: Throwable) {
                runOnUiThread { showIoError(error) }
            }
        }
    }

    private fun confirmFlashFirmware() {
        val device = supportedDevice()?.takeIf(::isBootloaderDevice) ?: return
        val release = firmwareRelease ?: return
        val image = firmwareImage ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_flash_title)
            .setMessage(
                getString(
                    R.string.confirm_flash_message,
                    release.name,
                    image.version ?: release.tag,
                    image.payload.size / 1024.0,
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> flashFirmware(device, image) }
            .show()
    }

    private fun flashFirmware(device: UsbDevice, image: FirmwareProtocol.Image) {
        check(isBootloaderDevice(device)) { "Firmware can only be flashed in Bootloader mode" }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.firmwareProgress.progress = 0
        binding.firmwareProgress.visibility = View.VISIBLE
        setBusy(true, getString(R.string.flashing_firmware, 0))
        appendLog("开始刷写 ${image.version ?: "firmware"}")
        ioExecutor.execute {
            try {
                activeSession(device).flashFirmware(image, hardwareType(device)) { progress ->
                    runOnUiThread {
                        binding.firmwareProgress.progress = progress
                        binding.firmwareStatus.text = if (progress >= 100) {
                            getString(R.string.firmware_verifying)
                        } else {
                            getString(R.string.flashing_firmware, progress)
                        }
                    }
                }
                session?.close()
                session = null
                runOnUiThread {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    awaitingMode = UsbMode.APP
                    bootloaderInfo = null
                    binding.firmwareProgress.progress = 100
                    binding.firmwareStatus.text = getString(R.string.firmware_flash_complete)
                    appendLog("固件刷写和 CRC32 校验成功")
                    setBusy(false, getString(R.string.firmware_flash_complete))
                    binding.root.postDelayed({ refreshUsbDevices(autoConnect = true) }, 1_500)
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val message = error.message ?: error.javaClass.simpleName
                    binding.firmwareStatus.text = getString(R.string.firmware_flash_failed, message)
                    appendLog("固件刷写失败：$message")
                    setBusy(false)
                }
            }
        }
    }

    private fun renderFirmwareStatus() {
        if (isBusy) return
        val device = supportedDevice()
        val release = firmwareRelease
        val image = firmwareImage
        binding.firmwareStatus.text = buildString {
            if (device != null) {
                val info = bootloaderInfo
                if (isBootloaderDevice(device) && info != null) {
                    append(getString(R.string.bootloader_connected, info.version, info.hardwareType ?: "?"))
                    if (!info.isSupported) append("\n").append(getString(R.string.bootloader_too_old))
                } else {
                    append(profileName(device))
                }
            }
            if (release != null && image != null) {
                if (isNotEmpty()) append("\n\n")
                append(
                    getString(
                        R.string.firmware_downloaded,
                        release.name,
                        image.version ?: release.tag,
                        image.hardwareType ?: "?",
                        image.payload.size / 1024.0,
                    ),
                )
                append("\n")
                append(
                    getString(
                        if (firmwareFromCache) R.string.firmware_source_cache
                        else R.string.firmware_source_network,
                    ),
                )
            }
            if (isEmpty()) append(getString(R.string.firmware_status_empty))
        }
    }

    private fun queryDeviceStatus() {
        val device = supportedDevice() ?: return
        setBusy(true, getString(R.string.querying_status))
        ioExecutor.execute {
            try {
                val active = activeSession(device)
                val status = active.queryStatus()
                runOnUiThread { showStatus(status, getString(R.string.status_synced)) }
            } catch (error: Throwable) {
                runOnUiThread { showIoError(error) }
            }
        }
    }

    private fun confirmAndApplyPreset() {
        val voltageMv = parseScaledInput(binding.voltageInput.text.toString(), 1_000)
        val currentMa = parseScaledInput(binding.currentInput.text.toString(), 1_000)

        when {
            voltageMv == null || voltageMv !in MIN_VOLTAGE_MV..MAX_VOLTAGE_MV || voltageMv % 20 != 0 -> {
                binding.voltageInput.error = getString(R.string.invalid_voltage)
                return
            }
            currentMa == null || currentMa !in MIN_CURRENT_MA..MAX_CURRENT_MA || currentMa % 100 != 0 -> {
                binding.currentInput.error = getString(R.string.invalid_current)
                return
            }
        }

        val voltageText = formatVoltage(voltageMv)
        val currentText = formatCurrent(currentMa)
        AlertDialog.Builder(this)
            .setTitle(
                if (voltageMv > HIGH_VOLTAGE_WARNING_MV) {
                    R.string.confirm_high_voltage_title
                } else {
                    R.string.confirm_apply_title
                },
            )
            .setMessage(getString(R.string.confirm_apply_message, voltageText, currentText))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ -> applyPreset(voltageMv, currentMa) }
            .show()
    }

    private fun applyPreset(voltageMv: Int, currentMa: Int) {
        val device = supportedDevice() ?: return
        setBusy(true, getString(R.string.applying_preset))
        appendLog("寫入預設 ${formatVoltage(voltageMv)}V / ${formatCurrent(currentMa)}A")
        ioExecutor.execute {
            try {
                val active = activeSession(device)
                active.applyPreset(voltageMv, currentMa)
                val status = active.queryStatus()
                check(status.presetVoltageMv == voltageMv && status.presetCurrentMa == currentMa) {
                    "回讀值與寫入值不一致"
                }
                runOnUiThread { showStatus(status, getString(R.string.preset_applied)) }
            } catch (error: Throwable) {
                runOnUiThread { showIoError(error) }
            }
        }
    }

    private fun confirmAndApplySystemSettings() {
        val status = lastStatus ?: return
        val commands = collectSystemChanges(status) ?: return
        if (commands.isEmpty()) {
            binding.systemStatus.text = getString(R.string.system_no_changes)
            return
        }

        val labels = commands.joinToString(separator = "\n") { "• ${it.label}" }
        val resetWarning = if (commands.any { it.causesPdReset }) {
            getString(R.string.pd_reset_warning)
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.system_confirm_title)
            .setMessage(getString(R.string.system_confirm_message, labels) + resetWarning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply_system) { _, _ -> applySystemCommands(commands) }
            .show()
    }

    private fun collectSystemChanges(
        status: PdUsbProtocol.DeviceStatus,
    ): List<PendingSystemCommand>? {
        val commands = mutableListOf<PendingSystemCommand>()
        val isNano = isNanoDevice(supportedDevice())

        if (!isNano && binding.vbusSwitch.isChecked != vbusEnabled(status.vbusStatus)) {
            commands += PendingSystemCommand(
                if (binding.vbusSwitch.isChecked) PdUsbProtocol.CMD_VBUS_ON else PdUsbProtocol.CMD_VBUS_OFF,
                if (binding.vbusSwitch.isChecked) "VBUS 開啟" else "VBUS 關閉",
            )
        }

        val priority = binding.prioritySpinner.selectedItemPosition
        val currentPriority = if (status.messagePriority == 0) 0 else 1
        if (priority != currentPriority) {
            commands += PendingSystemCommand(
                if (priority == 0) PdUsbProtocol.CMD_PRIORITY_FORWARD else PdUsbProtocol.CMD_PRIORITY_REPLY,
                if (priority == 0) "優先級：Forward" else "優先級：Reply",
            )
        }

        val report = binding.reportSpinner.selectedItemPosition
        val currentReport = if (status.reportType == 0) 0 else 1
        if (report != currentReport) {
            commands += PendingSystemCommand(
                if (report == 0) PdUsbProtocol.CMD_REPORT_STD else PdUsbProtocol.CMD_REPORT_MINI,
                if (report == 0) "報文格式：Std" else "報文格式：Mini",
            )
        }

        if (!isNano) {
            val vbusMode = binding.vbusModeSpinner.selectedItemPosition
            if (vbusMode != vbusModeIndex(status.vbusStatus)) {
                commands += PendingSystemCommand(
                    intArrayOf(
                        PdUsbProtocol.CMD_VBUS_MODE_OFF,
                        PdUsbProtocol.CMD_VBUS_MODE_ON,
                        PdUsbProtocol.CMD_VBUS_MODE_HOLD,
                    )[vbusMode],
                    "VBUS 模式：${binding.vbusModeSpinner.selectedItem}",
                )
            }
        }

        val triggerHold = binding.triggerHoldSpinner.selectedItemPosition
        if (triggerHold != status.triggerHoldStatus.coerceIn(0, 3)) {
            commands += PendingSystemCommand(
                intArrayOf(0x61, 0x62, 0x63, 0x64)[triggerHold],
                "誘騙保持：${binding.triggerHoldSpinner.selectedItem}",
            )
        }

        val timing = binding.triggerTimingSpinner.selectedItemPosition
        val currentTiming = if (status.triggerDelayMode == 0) 0 else 1
        if (timing != currentTiming) {
            commands += PendingSystemCommand(
                if (timing == 0) PdUsbProtocol.CMD_TRIGGER_IMMEDIATE else PdUsbProtocol.CMD_TRIGGER_DELAYED,
                "誘騙時機：${binding.triggerTimingSpinner.selectedItem}",
            )
        }

        if (!isNano) {
            if (binding.adcLogSwitch.isChecked != status.adcLogEnabled) {
                commands += PendingSystemCommand(
                    if (binding.adcLogSwitch.isChecked) PdUsbProtocol.CMD_ADC_LOG_ON else PdUsbProtocol.CMD_ADC_LOG_OFF,
                    if (binding.adcLogSwitch.isChecked) "ADC 日誌開啟" else "ADC 日誌關閉",
                )
            }

            val adcInterval = binding.adcIntervalInput.text.toString().toIntOrNull()
            if (adcInterval == null || adcInterval < 100) {
                binding.adcIntervalInput.error = getString(R.string.invalid_adc_interval)
                return null
            }
            if (adcInterval.toLong() != status.adcLogIntervalMs) {
                commands += PendingSystemCommand(
                    PdUsbProtocol.CMD_ADC_LOG_INTERVAL,
                    "ADC 間隔：${adcInterval}ms",
                    value = adcInterval,
                )
            }

            val flashFallback = binding.flashFallbackSwitch.isChecked
            if (flashFallback != (status.nvBackend == 2)) {
                commands += PendingSystemCommand(
                    if (flashFallback) PdUsbProtocol.CMD_FLASH_FALLBACK_ON else PdUsbProtocol.CMD_FLASH_FALLBACK_OFF,
                    if (flashFallback) "Flash 降級存儲開啟" else "Flash 降級存儲關閉",
                    waitForAck = false,
                )
            }
        }

        val pdMode = binding.pdModeSpinner.selectedItemPosition
        if (pdMode != status.sinkMode.coerceIn(0, 3)) {
            commands += PendingSystemCommand(
                intArrayOf(
                    PdUsbProtocol.CMD_PD_MODE_SPR,
                    PdUsbProtocol.CMD_PD_MODE_EPR,
                    PdUsbProtocol.CMD_PD_MODE_PROP,
                    PdUsbProtocol.CMD_PD_MODE_PD32,
                )[pdMode],
                "PD 模式：${binding.pdModeSpinner.selectedItem}",
                causesPdReset = true,
            )
        }
        return commands
    }

    private fun applySystemCommands(commands: List<PendingSystemCommand>) {
        val device = supportedDevice() ?: return
        setBusy(true, getString(R.string.system_applying, commands.size))
        appendLog("準備應用 ${commands.size} 項系統設置")
        ioExecutor.execute {
            try {
                val active = activeSession(device)
                commands.forEach { command ->
                    active.sendSystemCommand(
                        command = command.command,
                        value = command.value,
                        waitForAck = command.waitForAck,
                    )
                    appendLog("已發送 0x${command.command.toString(16).uppercase()} ${command.label}")
                }
                if (commands.any { it.causesPdReset }) Thread.sleep(700)
                val status = active.queryStatus(timeoutMs = 2_500)
                runOnUiThread { showStatus(status, getString(R.string.system_applied)) }
            } catch (error: Throwable) {
                runOnUiThread { showIoError(error) }
            }
        }
    }

    private fun confirmMcuReboot() {
        AlertDialog.Builder(this)
            .setTitle(R.string.mcu_reboot)
            .setMessage(R.string.confirm_mcu_reboot)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> rebootMcu() }
            .show()
    }

    private fun rebootMcu() {
        val device = supportedDevice() ?: return
        setBusy(true, getString(R.string.rebooting_mcu))
        appendLog("發送 MCU 重啟命令")
        ioExecutor.execute {
            try {
                val active = activeSession(device)
                active.sendSystemCommand(PdUsbProtocol.CMD_MCU_REBOOT, waitForAck = false)
                active.close()
                session = null
                runOnUiThread {
                    hasReadStatus = false
                    lastStatus = null
                    setBusy(false, getString(R.string.rebooting_mcu))
                    binding.root.postDelayed({ refreshUsbDevices(autoConnect = true) }, 2_000)
                }
            } catch (error: Throwable) {
                runOnUiThread { showIoError(error) }
            }
        }
    }

    private fun confirmUsbPdReboot() {
        AlertDialog.Builder(this)
            .setTitle(R.string.usb_pd_reboot)
            .setMessage(R.string.confirm_usb_pd_reboot)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> rebootUsbPd() }
            .show()
    }

    private fun rebootUsbPd() {
        val device = supportedDevice() ?: return
        setBusy(true, getString(R.string.rebooting_pd))
        appendLog("發送 USB PD 重新協商命令")
        ioExecutor.execute {
            try {
                activeSession(device).sendSystemCommand(PdUsbProtocol.CMD_USB_PD_REBOOT)
                runOnUiThread {
                    binding.root.postDelayed({ queryDeviceStatus() }, 1_200)
                }
            } catch (error: Throwable) {
                runOnUiThread { showIoError(error) }
            }
        }
    }

    private fun showStatus(status: PdUsbProtocol.DeviceStatus, headline: String) {
        lastStatus = status
        hasReadStatus = true
        bootloaderInfo = null
        if (awaitingMode == UsbMode.APP) awaitingMode = null
        if (!binding.voltageInput.hasFocus() && status.presetVoltageMv > 0) {
            binding.voltageInput.setText(formatVoltage(status.presetVoltageMv))
        }
        if (!binding.currentInput.hasFocus() && status.presetCurrentMa >= 0) {
            binding.currentInput.setText(formatCurrent(status.presetCurrentMa))
        }

        binding.presetVoltageText.text = "${formatVoltage(status.presetVoltageMv)} V"
        binding.presetCurrentText.text = "${formatCurrent(status.presetCurrentMa)} A"
        binding.modeText.text = "PD · ${sinkModeName(status.sinkMode)}"
        binding.vbusText.text = "VBUS · ${vbusName(status.vbusStatus)}"
        binding.protocolStatus.text = getString(R.string.status_readback, headline)
        binding.systemStatus.text = systemStatusSummary(status)
        syncSystemControls(status)
        appendLog("$headline：${formatVoltage(status.presetVoltageMv)}V / ${formatCurrent(status.presetCurrentMa)}A")
        setBusy(false)
        refreshUsbDevices(autoConnect = false)
    }

    private fun syncSystemControls(status: PdUsbProtocol.DeviceStatus) {
        binding.vbusSwitch.isChecked = vbusEnabled(status.vbusStatus)
        binding.prioritySpinner.setSelection(if (status.messagePriority == 0) 0 else 1)
        binding.pdModeSpinner.setSelection(status.sinkMode.coerceIn(0, 3))
        binding.reportSpinner.setSelection(if (status.reportType == 0) 0 else 1)
        binding.vbusModeSpinner.setSelection(vbusModeIndex(status.vbusStatus))
        binding.triggerHoldSpinner.setSelection(status.triggerHoldStatus.coerceIn(0, 3))
        binding.triggerTimingSpinner.setSelection(if (status.triggerDelayMode == 0) 0 else 1)
        binding.adcLogSwitch.isChecked = status.adcLogEnabled
        if (status.adcLogIntervalMs > 0) binding.adcIntervalInput.setText(status.adcLogIntervalMs.toString())
        binding.flashFallbackSwitch.isChecked = status.nvBackend == 2
    }

    private fun systemStatusSummary(status: PdUsbProtocol.DeviceStatus): String = buildString {
        append("優先級：${if (status.messagePriority == 0) "Forward" else "Reply"}")
        append(" · 報文：${if (status.reportType == 0) "Std" else "Mini"}\n")
        append("誘騙保持：${triggerHoldName(status.triggerHoldStatus)}")
        append(" · 時機：${if (status.triggerDelayMode == 0) "立即" else "延時"}\n")
        append("ADC：${if (status.adcLogEnabled) "開" else "關"} / ${status.adcLogIntervalMs}ms")
        append(" · NV：${nvBackendName(status.nvBackend)}")
    }

    private fun showIoError(error: Throwable) {
        hasReadStatus = false
        val message = error.message ?: error.javaClass.simpleName
        binding.protocolStatus.text = getString(R.string.protocol_error, message)
        binding.systemStatus.text = getString(R.string.protocol_error, message)
        appendLog("錯誤：$message")
        setBusy(false)
        refreshUsbDevices(autoConnect = false)
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        isBusy = busy
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (message != null) {
            binding.protocolStatus.text = message
            binding.systemStatus.text = message
        }
        updateEnabledState()
    }

    private fun updateEnabledState() {
        val device = supportedDevice()
        val hasPermission = device != null && usbManager.hasPermission(device)
        val appMode = device != null && isSupportedAppDevice(device)
        val bootloaderMode = device != null && isBootloaderDevice(device)
        val appReady = !isBusy && hasPermission && appMode && hasReadStatus
        val bootloaderReady = !isBusy && hasPermission && bootloaderMode && bootloaderInfo?.isSupported == true
        val nano = isNanoDevice(device)

        binding.refreshButton.isEnabled = !isBusy
        binding.permissionButton.isEnabled = !isBusy && device != null && !hasPermission
        binding.queryButton.isEnabled = !isBusy && hasPermission && appMode
        binding.applyButton.isEnabled = appReady
        binding.applySystemButton.isEnabled = appReady
        binding.mcuRebootButton.isEnabled = appReady
        binding.usbPdRebootButton.isEnabled = appReady
        binding.pullFirmwareButton.isEnabled = !isBusy && device != null
        binding.bootloaderButton.text = getString(if (bootloaderMode) R.string.exit_bootloader else R.string.enter_bootloader)
        binding.bootloaderButton.isEnabled = appReady || bootloaderReady
        binding.flashFirmwareButton.isEnabled = bootloaderReady && firmwareImage != null

        binding.prioritySpinner.isEnabled = appReady
        binding.pdModeSpinner.isEnabled = appReady
        binding.reportSpinner.isEnabled = appReady
        binding.triggerHoldSpinner.isEnabled = appReady
        binding.triggerTimingSpinner.isEnabled = appReady

        binding.vbusSwitch.isEnabled = appReady && !nano
        binding.vbusModeSpinner.isEnabled = appReady && !nano
        binding.adcLogSwitch.isEnabled = appReady && !nano
        binding.adcIntervalInput.isEnabled = appReady && !nano
        binding.flashFallbackSwitch.isEnabled = appReady && !nano
        binding.nanoNotice.visibility = if (device != null && appMode && nano) View.VISIBLE else View.GONE
    }

    private fun clearStatusSummary() {
        binding.presetVoltageText.text = getString(R.string.voltage_value_empty)
        binding.presetCurrentText.text = getString(R.string.current_value_empty)
        binding.modeText.text = getString(R.string.mode_value_empty)
        binding.vbusText.text = getString(R.string.vbus_value_empty)
    }

    private fun updatePowerPreview() {
        val voltage = binding.voltageInput.text.toString().toDoubleOrNull()
        val current = binding.currentInput.text.toString().toDoubleOrNull()
        val value = if (voltage != null && current != null) {
            "%.1f".format(voltage * current)
        } else {
            "--"
        }
        binding.powerPreview.text = getString(R.string.power_preview, value)
    }

    private fun appendLog(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { appendLog(message) }
            return
        }
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logLines.addLast("[$time] $message")
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        binding.eventLogText.text = logLines.joinToString("\n")
    }

    private fun activeSession(device: UsbDevice): UsbHidSession =
        session?.takeIf { it.device.deviceName == device.deviceName }
            ?: UsbHidSession.open(usbManager, device).also { session = it }

    private fun closeSessionAsync() {
        val active = session ?: return
        session = null
        ioExecutor.execute { active.close() }
    }

    private fun supportedDevice(): UsbDevice? =
        selectSupportedDevice(usbManager.deviceList.values.filter(::isSupportedDevice))

    private fun selectSupportedDevice(devices: Collection<UsbDevice>): UsbDevice? {
        awaitingMode?.let { expected ->
            devices.firstOrNull { usbMode(it) == expected }?.let { return it }
        }
        return devices.firstOrNull { session?.device?.deviceName == it.deviceName }
            ?: devices.firstOrNull(::isBootloaderDevice)
            ?: devices.firstOrNull(::isSupportedAppDevice)
    }

    private fun isSupportedDevice(device: UsbDevice): Boolean =
        device.vendorId == PD_VENDOR_ID && device.productId in SUPPORTED_PRODUCT_IDS

    private fun isSupportedAppDevice(device: UsbDevice): Boolean =
        device.vendorId == PD_VENDOR_ID && device.productId in SUPPORTED_APP_PRODUCT_IDS

    private fun isBootloaderDevice(device: UsbDevice): Boolean =
        device.vendorId == PD_VENDOR_ID && device.productId in SUPPORTED_BOOTLOADER_PRODUCT_IDS

    private fun usbMode(device: UsbDevice): UsbMode? = when {
        isSupportedAppDevice(device) -> UsbMode.APP
        isBootloaderDevice(device) -> UsbMode.BOOTLOADER
        else -> null
    }

    private fun isNanoDevice(device: UsbDevice?): Boolean =
        device?.vendorId == PD_VENDOR_ID && device.productId in setOf(NANO_APP_PRODUCT_ID, NANO_BOOTLOADER_PRODUCT_ID)

    private fun hardwareType(device: UsbDevice): String = if (isNanoDevice(device)) "NANO" else "STD"

    private fun firmwareVariant(device: UsbDevice): String = hardwareType(device).lowercase()

    private fun profileName(device: UsbDevice): String = when {
        device.vendorId == PD_VENDOR_ID && device.productId == STD_APP_PRODUCT_ID -> "PD Std · APP"
        device.vendorId == PD_VENDOR_ID && device.productId == STD_BOOTLOADER_PRODUCT_ID -> "PD Std · BL"
        device.vendorId == PD_VENDOR_ID && device.productId == NANO_APP_PRODUCT_ID -> "PD Nano · APP"
        device.vendorId == PD_VENDOR_ID && device.productId == NANO_BOOTLOADER_PRODUCT_ID -> "PD Nano · BL"
        else -> device.productName ?: getString(R.string.unknown_usb_device)
    }

    private fun parseScaledInput(text: String, scale: Int): Int? = try {
        BigDecimal(text.trim())
            .multiply(BigDecimal(scale))
            .setScale(0, RoundingMode.UNNECESSARY)
            .intValueExact()
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    private fun formatVoltage(millivolts: Int): String = "%.2f".format(millivolts / 1_000.0)
    private fun formatCurrent(milliamps: Int): String = "%.1f".format(milliamps / 1_000.0)

    private fun sinkModeName(mode: Int): String = when (mode) {
        0 -> "SPR"
        1 -> "EPR"
        2 -> "PROP"
        3 -> "PD3.2"
        else -> "#$mode"
    }

    private fun vbusModeIndex(status: Int): Int = when (status) {
        0 -> 0
        1 -> 1
        else -> 2
    }

    private fun vbusEnabled(status: Int): Boolean = status == 1 || status == 2 || status == 4

    private fun vbusName(status: Int): String = when (status) {
        0 -> "OFF"
        1 -> "ON"
        2 -> "HOLD"
        3 -> "HOLD / OFF"
        4 -> "HOLD / ON"
        else -> "#$status"
    }

    private fun triggerHoldName(status: Int): String = when (status) {
        0 -> "關閉"
        1 -> "開啟"
        2 -> "簡單"
        3 -> "預設"
        else -> "#$status"
    }

    private fun nvBackendName(backend: Int): String = when (backend) {
        0 -> "無"
        1 -> "主存儲"
        2 -> "Flash fallback"
        else -> "#$backend"
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.pdtool.voltage.USB_PERMISSION"

        private const val PD_VENDOR_ID = 0xA016
        private const val STD_APP_PRODUCT_ID = 0x0404
        private const val NANO_APP_PRODUCT_ID = 0x0104
        private const val STD_BOOTLOADER_PRODUCT_ID = 0x0405
        private const val NANO_BOOTLOADER_PRODUCT_ID = 0x0105
        private val SUPPORTED_APP_PRODUCT_IDS = setOf(STD_APP_PRODUCT_ID, NANO_APP_PRODUCT_ID)
        private val SUPPORTED_BOOTLOADER_PRODUCT_IDS = setOf(STD_BOOTLOADER_PRODUCT_ID, NANO_BOOTLOADER_PRODUCT_ID)
        private val SUPPORTED_PRODUCT_IDS = SUPPORTED_APP_PRODUCT_IDS + SUPPORTED_BOOTLOADER_PRODUCT_IDS

        private const val MIN_VOLTAGE_MV = 3_000
        private const val MAX_VOLTAGE_MV = 48_000
        private const val MIN_CURRENT_MA = 0
        private const val MAX_CURRENT_MA = 10_000
        private const val HIGH_VOLTAGE_WARNING_MV = 20_000
        private const val MAX_LOG_LINES = 80

        private val LANGUAGE_TAGS = listOf(
            "", "zh-CN", "zh-TW", "ja", "en", "ko", "th", "vi", "hi",
            "id", "fr", "de", "es", "it", "ru", "pt", "nl",
        )

        private const val PAGE_CONTROL = 0
        private const val PAGE_SYSTEM = 1
        private const val PAGE_TOOLS = 2
    }
}

