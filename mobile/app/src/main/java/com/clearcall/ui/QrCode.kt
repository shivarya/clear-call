package com.clearcall.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Renders a user code as a QR bitmap for the "add me" share card. */
fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}.getOrNull()
