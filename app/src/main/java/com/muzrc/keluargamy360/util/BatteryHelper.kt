package com.muzrc.keluargamy360.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

object BatteryHelper {

    data class BatteryStatus(
        val level: Int,
        val isCharging: Boolean
    )

    fun getBatteryStatus(context: Context): BatteryStatus {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 100
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return BatteryStatus(level = pct, isCharging = charging)
    }
}
