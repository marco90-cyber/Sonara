// SPDX-License-Identifier: GPL-3.0-or-later
package com.marco.mlplayer.audio.direct

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.decent.usbaudio.UsbAudioDevice
import com.marco.mlplayer.data.Settings
import com.marco.mlplayer.util.AppLog

/** Dove finisce il suono. */
enum class Route(val label: String) {
    Android("Android AudioTrack (mixer di sistema)"),
    UsbExclusive("USB esclusiva (driver diretto al DAC)"),
    DirectTrack("Uscita diretta (AudioTrack DIRECT)"),
    AAudio("AAudio esclusiva (MMAP)"),
}

/**
 * Uscite che saltano il mixer di Android (idee e codice da LastWave, GPL-3.0; driver USB di
 * decent-player, MIT): USB esclusiva al DAC, AudioTrack DIRECT e AAudio esclusiva (MMAP).
 * Qui stanno le istanze condivise e le preferenze.
 */
class DirectOutputs(context: Context, private val settings: Settings) {
    private val app = context.applicationContext
    val audioManager: AudioManager? = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val usbManager: UsbManager? = app.getSystemService(Context.USB_SERVICE) as? UsbManager

    val usb = ExclusiveUsbOutput(app)
    val usbBitPerfect = UsbBitPerfectOutput(audioManager)

    init {
        usb.setWanted(settings.usbExclusive)
        usbBitPerfect.setEnabled(settings.bitPerfect)
    }

    val usbWanted: Boolean get() = settings.usbExclusive
    val directWanted: Boolean get() = settings.directOutput
    val bitPerfect: Boolean get() = settings.bitPerfect

    /** Primo DAC USB Audio Class collegato (anche senza permesso). */
    fun usbAudioDevice(): UsbDevice? = runCatching {
        usbManager?.deviceList?.values?.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        }
    }.getOrNull()

    fun usbDacReady(): Boolean {
        val d = usbAudioDevice() ?: return false
        return runCatching { usbManager?.hasPermission(d) == true }.getOrDefault(false)
    }

    /** Chiede il permesso USB per il DAC collegato (serve per l'uscita esclusiva). */
    fun ensureUsbPermission() {
        val d = usbAudioDevice() ?: return
        if (runCatching { usbManager?.hasPermission(d) == true }.getOrDefault(false)) return
        runCatching {
            UsbAudioDevice.getInstance(app).requestPermission(d) { granted ->
                AppLog.i("Uscita", "Permesso USB per ${d.productName}: ${if (granted) "concesso" else "negato"}")
            }
        }.onFailure { AppLog.w("Uscita", "Richiesta permesso USB non riuscita", it) }
    }

    /** Dispositivo di uscita USB visto da Android (per il bit-perfect del mixer, Android 14+). */
    fun androidUsbOutput(): AudioDeviceInfo? = runCatching {
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }.getOrNull()

    fun release() {
        runCatching { usb.release() }
    }
}
