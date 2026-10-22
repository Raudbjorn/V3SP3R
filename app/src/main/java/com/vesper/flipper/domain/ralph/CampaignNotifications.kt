package com.vesper.flipper.domain.ralph
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object CampaignNotifications {
    fun pending(context: Context, id: String, title: String = "Campaign needs approval") {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("campaign-approvals","Campaign approvals",NotificationManager.IMPORTANCE_DEFAULT))
        if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = PendingIntent.getActivity(context,id.hashCode(),Intent(context,CampaignActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { manager.notify(id.hashCode(),NotificationCompat.Builder(context,"campaign-approvals")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText("Open the campaign inbox to review its exact command and scope.")
            .setContentIntent(intent).setAutoCancel(true).build()) } catch (_: SecurityException) { }
    }
}
