package com.lostf1sh.pixelplayeross.presentation.telegram

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Encodes only TDLib's short-lived Telegram login links.
 * The link is an authentication secret: callers must not log or persist it.
 */
internal object TelegramQrCodeEncoder {
    private const val LOGIN_LINK_PREFIX = "tg://login?token="
    private const val MAX_LINK_LENGTH = 2_048

    fun encode(link: String, size: Int = 512): BitMatrix {
        require(link.length in LOGIN_LINK_PREFIX.length + 1..MAX_LINK_LENGTH) {
            "Telegram returned an invalid QR login link."
        }
        require(link.startsWith(LOGIN_LINK_PREFIX)) {
            "Telegram returned an unsupported QR login link."
        }
        require(size in 128..1_024) {
            "QR code dimensions must be between 128 and 1024 pixels."
        }

        return QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size)
    }
}
