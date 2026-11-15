package com.lifi.messenger

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Process

/**
 * Blinks the rear flashlight following the exact bit sequence from LiFiProtocol.
 * Bit k is scheduled at (start + k * bitMs) using System.nanoTime(), so timing errors
 * never accumulate (a late torch call does not delay the following bits).
 */
class TorchTransmitter(private val cm: CameraManager) {

    /** Camera id that owns the flashlight, or null if the phone has none. */
    val cameraId: String? = findFlashCamera()

    @Volatile private var cancelled = false
    @Volatile var running = false
        private set

    private fun hasFlash(id: String): Boolean =
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    private fun findFlashCamera(): String? = try {
        cm.cameraIdList.firstOrNull {
            hasFlash(it) && cm.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { hasFlash(it) }
    } catch (e: Exception) {
        null
    }

    /** Request an immediate stop. The torch is switched OFF by the worker within ~1 ms. */
    fun cancel() {
        cancelled = true
    }

    /**
     * @param onBit    called from the worker thread for every bit: (index, torchOn)
     * @param onFinish called from the worker thread once: (completed, errorMessage)
     *                 completed=false and error=null means "stopped by the user"
     */
    fun start(
        bits: BooleanArray,
        bitMs: Int,
        onBit: (Int, Boolean) -> Unit,
        onFinish: (Boolean, String?) -> Unit
    ) {
        val id = cameraId
        if (id == null) {
            onFinish(false, "This phone has no flashlight")
            return
        }
        if (running) return
        cancelled = false
        running = true
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var error: String? = null
            var on = false
            val stepNs = bitMs * 1_000_000L
            try {
                val t0 = System.nanoTime() + 50_000_000L
                for (i in bits.indices) {
                    if (!sleepUntil(t0 + i * stepNs)) break
                    if (bits[i] != on) {
                        cm.setTorchMode(id, bits[i])
                        on = bits[i]
                    }
                    onBit(i, bits[i])
                }
                if (!cancelled) sleepUntil(t0 + bits.size * stepNs)   // let the last STOP bit finish
            } catch (e: SecurityException) {
                error = "CAMERA_PERMISSION"
            } catch (e: CameraAccessException) {
                error = "Torch unavailable - is another app using the camera?"
            } catch (e: Exception) {
                error = e.message ?: "Torch error"
            } finally {
                try { cm.setTorchMode(id, false) } catch (_: Exception) { }
                running = false
                onFinish(!cancelled && error == null, error)
            }
        }, "lifi-tx").start()
    }

    /** Sleeps until [target] (System.nanoTime), spinning for the last 4 ms. False if cancelled. */
    private fun sleepUntil(target: Long): Boolean {
        while (true) {
            if (cancelled) return false
            val remaining = target - System.nanoTime()
            if (remaining <= 0) return true
            if (remaining > 4_000_000L) Thread.sleep(1) else Thread.yield()
        }
    }
}
