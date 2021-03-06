package com.douglaspac.wifireminder

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.support.v4.app.NotificationCompat
import android.text.format.Formatter
import com.douglaspac.wifireminder.activity.DonationActivity
import com.douglaspac.wifireminder.broadcast.MuteReceiver
import com.douglaspac.wifireminder.broadcast.RateReceiver
import com.douglaspac.wifireminder.broadcast.TurnOnWifiReceiver
import com.douglaspac.wifireminder.persister.MySharedPref
import com.douglaspac.wifireminder.utils.EXTRA_NOTIFICATION_ID

class WifiReminder(private val ctx: Context) : Runnable {
    private val trafficMobileTotal by lazy { TrafficStats.getMobileTxBytes() + TrafficStats.getMobileRxBytes() }

    override fun run() {
        if (!canRun()) return

        val lastTotalMobileUsage = MySharedPref.getTotalMobileUsage(ctx)
        val diff = trafficMobileTotal - lastTotalMobileUsage

        val notifyAfter = MySharedPref.getNotifyAfter(ctx).toLong() * 1000000L
        if (diff > notifyAfter) {
            showWiFiReminderNotification(diff)

            val notifyCounter = MySharedPref.getNotifyCounter(ctx)
            MySharedPref.setNotifyCounter(ctx, notifyCounter + 1)
            if (notifyCounter < 31 && notifyCounter % 10 == 0) {
                showDonationNotification(notifyCounter)
            }
        }

        resetMobileDataValues()
    }

    private fun canRun(): Boolean {
        val myKM = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isPhoneLocked = myKM.isKeyguardLocked
        if (isPhoneLocked) {
            return false
        }

        if (!isOnlyMobileNetworkConnected()) {
            return false
        }

        if (trafficMobileTotal == 0L) {
            return false
        }

        val now = System.currentTimeMillis()
        val lastVerified = MySharedPref.getLastVerifiedTime(ctx)
        val tenAgo = now - (10 * 60 * 1000)
        if (lastVerified < tenAgo) {
            resetMobileDataValues()
            return false
        }

        val muteUntil = MySharedPref.getMuteUntil(ctx)
        if (muteUntil > now) {
            resetMobileDataValues()
            return false
        }

        return true
    }

    private fun resetMobileDataValues() {
        MySharedPref.setTotalMobileUsage(ctx, trafficMobileTotal)
        MySharedPref.setLastVerifiedTime(ctx, System.currentTimeMillis())
    }

    private fun isOnlyMobileNetworkConnected(): Boolean {
        var isWifiConn = false
        var isMobileConn = false

        try {
            val connMgr = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connMgr.allNetworks.forEach { network ->
                val caps = connMgr.getNetworkCapabilities(network)
                val connected = connMgr.getNetworkInfo(network).isConnected

                isWifiConn = isWifiConn or (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && connected)
                isMobileConn = isMobileConn or (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && connected)
            }
        } catch (ex: Exception) {
            isMobileConn = false
        }

        return !isWifiConn && isMobileConn
    }

    private fun showWiFiReminderNotification(diffInBytes: Long) {
        val diffInMegaBytesFormatted = Formatter.formatShortFileSize(ctx, diffInBytes)

        val notificationId = ctx.packageName.length + Math.random().toInt()
        val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(NotificationChannel(
                "channel-01", this.javaClass.simpleName, NotificationManager.IMPORTANCE_HIGH
            ))
        }

        val intentTurnOnWiFi = Intent(ctx, TurnOnWifiReceiver::class.java).apply {
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingIntentTurnOnWiFi = PendingIntent.getBroadcast(ctx, 1, intentTurnOnWiFi, PendingIntent.FLAG_UPDATE_CURRENT)

        val intentMute = Intent(ctx, MuteReceiver::class.java).apply {
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingIntentMute = PendingIntent.getBroadcast(ctx, 1, intentMute, PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(ctx, "channel-01").apply {
            this.setSmallIcon(R.drawable.ic_launcher_foreground)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                this.color = ctx.resources.getColor(R.color.colorPrimary, ctx.theme)
            } else {
                this.color = ctx.resources.getColor(R.color.colorPrimary)
            }
            this.setContentTitle(ctx.getString(R.string.notification_title))
            this.setContentText(ctx.getString(R.string.notification_body, diffInMegaBytesFormatted))
            this.setAutoCancel(true)
            setContentIntent(pendingIntentTurnOnWiFi)
            this.addAction(0, ctx.getString(R.string.turn_on_wifi), pendingIntentTurnOnWiFi)
            this.addAction(0, ctx.getString(R.string.button_mute_for_one_hour), pendingIntentMute)
        }

        notificationManager.notify(notificationId, builder.build())
    }

    private fun showDonationNotification(counter :Int) {
        val notificationId = ctx.packageName.length + Math.random().toInt()
        val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(NotificationChannel(
                "channel-01", this.javaClass.simpleName, NotificationManager.IMPORTANCE_HIGH
            ))
        }

        val intentRate = Intent(ctx, RateReceiver::class.java).apply {
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pendingIntentRate = PendingIntent.getBroadcast(ctx, 1, intentRate, PendingIntent.FLAG_UPDATE_CURRENT)

        val intentDonation = Intent(ctx, DonationActivity::class.java)
        val pendingIntentDonation = TaskStackBuilder.create(ctx).run {
            addNextIntentWithParentStack(intentDonation)
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val builder = NotificationCompat.Builder(ctx, "channel-01").apply {
            this.setSmallIcon(R.drawable.ic_launcher_foreground)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                this.color = ctx.resources.getColor(R.color.colorPrimary, ctx.theme)
            } else {
                this.color = ctx.resources.getColor(R.color.colorPrimary)
            }
            this.setContentTitle(ctx.getString(R.string.notification_donation_title))
            this.setContentText(ctx.getString(R.string.notification_donation_body, counter.toString()))
            this.setAutoCancel(true)
            setContentIntent(pendingIntentDonation)
            this.addAction(0, ctx.getString(R.string.rate_this_app), pendingIntentRate)
            this.addAction(0, ctx.getString(R.string.make_donation), pendingIntentDonation)
        }

        notificationManager.notify(notificationId, builder.build())
    }
}