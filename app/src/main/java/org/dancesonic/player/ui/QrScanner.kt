package org.dancesonic.player.ui

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Scans a code and connects with it. A cancelled scan does nothing. Play services reports scanner failures
 * without a common exception type, so any other failure reads as "not available".
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
suspend fun ConnectViewModel.scanAndConnect(context: Context) {
    try {
        scanQrCode(context.applicationContext)?.let { connectFromScan(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportError("The code scanner is not available on this device.")
    }
}

/** Scans a DanceBrainz pairing code and signs in with it. A cancelled scan does nothing; a failure is reported. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
suspend fun DanceBrainzViewModel.scanAndSignIn(context: Context) {
    try {
        scanQrCode(context.applicationContext)?.let { signInWithCode(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportError("The code scanner is not available on this device.")
    }
}

/**
 * Opens the Google code scanner (runs in Play services, so the app needs no camera permission).
 * Returns the QR payload, or null if the user closed the scanner without a result.
 */
suspend fun scanQrCode(context: Context): String? =
    suspendCancellableCoroutine { cont ->
        val options =
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode -> cont.resume(barcode.rawValue) }
            .addOnCanceledListener { cont.resume(null) }
            .addOnFailureListener { error -> cont.resumeWithException(error) }
    }
