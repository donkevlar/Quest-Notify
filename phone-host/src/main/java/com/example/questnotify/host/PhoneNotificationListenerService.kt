package com.example.questnotify.host

import android.app.Notification
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.example.questnotify.shared.NotificationPayload
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

class PhoneNotificationListenerService : NotificationListenerService() {

    private val appIconCache = ConcurrentHashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Avoid echoing our own foreground server status notification
        if (sbn.packageName == packageName && sbn.id == HostBridgeService.FOREGROUND_NOTIFICATION_ID) {
            return
        }

        val notification = sbn.notification ?: return
        // Skip empty group summary containers so Quest doesn't get duplicate alerts
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val regularText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        val bodyText = if (bigText.isNotEmpty()) bigText else regularText
        if (title.isEmpty() && bodyText.isEmpty()) return

        val appLabel = resolveAppLabel(sbn.packageName)
        val iconBase64 = extractIconBase64(sbn)

        val payload = NotificationPayload(
            key = sbn.key ?: "${sbn.packageName}:${sbn.id}",
            packageName = sbn.packageName,
            appName = appLabel,
            title = title.ifEmpty { appLabel },
            text = bodyText,
            subText = subText,
            category = notification.category.orEmpty(),
            timestamp = sbn.postTime,
            isOngoing = sbn.isOngoing,
            iconBase64 = iconBase64
        )

        HostBridgeRepository.onPhoneNotificationPosted(payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val key = sbn?.key ?: return
        HostBridgeRepository.onPhoneNotificationRemoved(key)
    }

    private fun resolveAppLabel(pkgName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkgName.substringAfterLast('.')
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun extractIconBase64(sbn: StatusBarNotification): String {
        val notification = sbn.notification
        // 1. Try to get custom notification large icon (e.g. sender avatar / profile picture)
        val largeIcon = try {
            notification?.getLargeIcon()?.loadDrawable(this)
        } catch (_: Exception) {
            null
        }

        if (largeIcon != null) {
            return drawableToBase64(largeIcon)
        }

        // 2. Fall back to cached app icon
        appIconCache[sbn.packageName]?.let { return it }

        // 3. Resolve app icon from package manager
        val appDrawable = try {
            packageManager.getApplicationIcon(sbn.packageName)
        } catch (_: Exception) {
            null
        }

        if (appDrawable != null) {
            val encoded = drawableToBase64(appDrawable)
            if (encoded.isNotEmpty()) {
                appIconCache[sbn.packageName] = encoded
            }
            return encoded
        }

        return ""
    }

    private fun drawableToBase64(drawable: Drawable, sizePx: Int = 96): String {
        return try {
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
            } else {
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
                bmp
            }
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }
}

