package org.dancesonic.player.ui

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
