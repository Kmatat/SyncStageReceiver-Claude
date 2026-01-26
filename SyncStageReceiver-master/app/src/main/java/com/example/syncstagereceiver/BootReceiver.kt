package com.example.syncstagereceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.syncstagereceiver.ui.MainActivity
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Timber.i("Boot completed, starting MainActivity.")

            // --- FIX: We ONLY start MainActivity. It will handle the service. ---
            val i = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }
    }
}