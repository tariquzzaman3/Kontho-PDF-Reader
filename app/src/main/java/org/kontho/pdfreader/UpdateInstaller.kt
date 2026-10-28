package org.kontho.pdfreader

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat

private const val UPDATE_MIME_TYPE = "application/vnd.android.package-archive"
private const val UPDATE_PREFS = "kontho_library"
private const val PREF_UPDATE_DOWNLOAD_ID = "pending_update_download_id"
private const val PREF_UPDATE_VERSION = "pending_update_version"
private const val PREF_INSTALL_PROMPTED_ID = "update_install_prompted_id"
private const val PREF_UNKNOWN_SOURCES_REQUESTED_ID = "update_unknown_sources_requested_id"
private const val PREF_UNKNOWN_SOURCES_NOTICE_ID = "update_unknown_sources_notice_id"

class UpdateDownloadReceiver(private val activity: Activity) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        UpdateInstaller.onDownloadComplete(activity, completedId)
    }
}

object UpdateInstaller {
    fun enqueue(activity: Activity, url: String, version: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" ||
            uri.host != "github.com" ||
            !uri.path.orEmpty().contains("/releases/download/")
        ) {
            toast(activity, "The APK download link is not a trusted GitHub release.", "APK ডাউনলোড লিংকটি নির্ভরযোগ্য GitHub রিলিজের নয়।")
            return
        }

        try {
            val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val request = DownloadManager.Request(uri).apply {
                setTitle("Kontho PDF Reader $version")
                setDescription("Downloading Kontho update…")
                setMimeType(UPDATE_MIME_TYPE)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(
                    activity,
                    Environment.DIRECTORY_DOWNLOADS,
                    "Kontho-PDF-Reader-update-$safeVersion.apk"
                )
                setAllowedOverRoaming(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) setAllowedOverMetered(true)
            }
            val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(PREF_UPDATE_DOWNLOAD_ID, downloadId)
                .putString(PREF_UPDATE_VERSION, version)
                .remove(PREF_INSTALL_PROMPTED_ID)
                .remove(PREF_UNKNOWN_SOURCES_REQUESTED_ID)
                .remove(PREF_UNKNOWN_SOURCES_NOTICE_ID)
                .apply()
            toast(
                activity,
                "Downloading version $version. You can follow progress in notifications.",
                "সংস্করণ $version ডাউনলোড হচ্ছে। নোটিফিকেশন থেকে অগ্রগতি দেখতে পারবেন।"
            )
        } catch (error: Exception) {
            toast(
                activity,
                "Could not start the update download: ${error.localizedMessage ?: "unknown error"}",
                "আপডেট ডাউনলোড শুরু করা যায়নি: ${error.localizedMessage ?: "অজানা ত্রুটি"}"
            )
        }
    }

    fun registerReceiver(activity: Activity, receiver: UpdateDownloadReceiver) {
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun onDownloadComplete(activity: Activity, completedId: Long) {
        if (completedId < 0L) return
        val expected = activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .getLong(PREF_UPDATE_DOWNLOAD_ID, -1L)
        if (completedId == expected) checkPendingDownload(activity)
    }

    fun checkPendingDownload(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(PREF_UPDATE_DOWNLOAD_ID, -1L)
        if (id < 0L) return

        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var status = -1
        var reason = 0
        try {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            } ?: return
        } catch (_: Exception) {
            return
        }

        when (status) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> return
            DownloadManager.STATUS_FAILED -> {
                prefs.edit().remove(PREF_UPDATE_DOWNLOAD_ID).remove(PREF_UPDATE_VERSION).apply()
                toast(
                    activity,
                    "Update download failed (code $reason). Check your connection and try again.",
                    "আপডেট ডাউনলোড ব্যর্থ হয়েছে (কোড $reason)। ইন্টারনেট সংযোগ দেখে আবার চেষ্টা করুন।"
                )
            }
            DownloadManager.STATUS_SUCCESSFUL -> openInstaller(activity, manager, id, prefs)
        }
    }

    private fun openInstaller(
        activity: Activity,
        manager: DownloadManager,
        id: Long,
        prefs: android.content.SharedPreferences
    ) {
        if (prefs.getLong(PREF_INSTALL_PROMPTED_ID, -1L) == id) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            if (prefs.getLong(PREF_UNKNOWN_SOURCES_REQUESTED_ID, -1L) != id) {
                prefs.edit().putLong(PREF_UNKNOWN_SOURCES_REQUESTED_ID, id).apply()
                toast(
                    activity,
                    "Allow “Install unknown apps” for Kontho to continue. Return here after enabling it.",
                    "চালিয়ে যেতে Kontho-এর জন্য “Install unknown apps” অনুমতি দিন। চালু করে এখানে ফিরে আসুন।"
                )
                try {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                } catch (_: Exception) {
                    toast(activity, "Open Android Settings and allow Kontho to install apps.", "Android সেটিংসে গিয়ে Kontho-কে অ্যাপ ইনস্টল করার অনুমতি দিন।")
                }
            }
            return
        }

        val apkUri = manager.getUriForDownloadedFile(id)
        if (apkUri == null) {
            toast(activity, "The downloaded APK could not be opened.", "ডাউনলোড করা APK খোলা যায়নি।")
            return
        }

        try {
            prefs.edit()
                .putLong(PREF_INSTALL_PROMPTED_ID, id)
                .remove(PREF_UNKNOWN_SOURCES_REQUESTED_ID)
                .apply()
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, UPDATE_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(installIntent)
        } catch (error: Exception) {
            prefs.edit().remove(PREF_INSTALL_PROMPTED_ID).apply()
            toast(
                activity,
                "Could not open Android's installer: ${error.localizedMessage ?: "unknown error"}",
                "Android-এর ইনস্টলার খোলা যায়নি: ${error.localizedMessage ?: "অজানা ত্রুটি"}"
            )
        }
    }

    private fun toast(activity: Activity, en: String, bn: String) {
        val isBangla = activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .getString("app_language", "en") == "bn"
        Toast.makeText(activity, if (isBangla) bn else en, Toast.LENGTH_LONG).show()
    }
}
