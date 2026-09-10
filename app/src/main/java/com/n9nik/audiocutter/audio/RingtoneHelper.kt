package com.n9nik.audiocutter.audio

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

/**
 * Saves trimmed audio into MediaStore and sets it as the default
 * ringtone / alarm / notification sound.
 *
 * Setting a system default needs WRITE_SETTINGS, a special-access permission
 * the user must grant manually in system Settings — we guide them there.
 * Per-contact ringtones are deliberately NOT implemented (no contacts permission).
 */
object RingtoneHelper {

    const val TYPE_RINGTONE = RingtoneManager.TYPE_RINGTONE
    const val TYPE_ALARM = RingtoneManager.TYPE_ALARM
    const val TYPE_NOTIFICATION = RingtoneManager.TYPE_NOTIFICATION

    fun canWriteSettings(context: Context): Boolean =
        Settings.System.canWrite(context)

    fun openWriteSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            // Fallback: generic settings page.
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun typeLabel(type: Int): String = when (type) {
        TYPE_ALARM -> "alarm"
        TYPE_NOTIFICATION -> "notification"
        else -> "ringtone"
    }

    /**
     * Copies [file] into MediaStore (Music/TinyTone) flagged for [type].
     * Returns the content Uri, or null on failure.
     */
    fun saveToMediaStore(
        context: Context,
        file: File,
        mimeType: String,
        title: String,
        type: Int
    ): Uri? {
        return try {
            val fileName = file.name.ifBlank { "$title.${file.extension}" }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.IS_RINGTONE, type == TYPE_RINGTONE)
                put(MediaStore.Audio.Media.IS_ALARM, type == TYPE_ALARM)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, type == TYPE_NOTIFICATION)
                put(MediaStore.Audio.Media.IS_MUSIC, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MUSIC + "/TinyTone"
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    put(
                        MediaStore.Audio.Media.DATA,
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MUSIC
                        ).absolutePath + "/TinyTone/$fileName"
                    )
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { `in` -> `in`.copyTo(out) }
            } ?: run {
                context.contentResolver.delete(uri, null, null)
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            uri
        } catch (_: Exception) {
            null
        }
    }

    /** Sets [uri] as the system default for [type]. Needs WRITE_SETTINGS. */
    fun setAsDefault(context: Context, uri: Uri, type: Int): Boolean {
        return try {
            RingtoneManager.setActualDefaultRingtoneUri(context, type, uri)
            true
        } catch (_: Exception) {
            false
        }
    }
}
