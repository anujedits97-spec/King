package dev.kairo.myra

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Starting Myraa's brain in Termux from the app (Termux's RUN_COMMAND, which you allow once). */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val Myraa = "/data/data/com.termux/files/usr/bin/myra"

    fun installed(c: Context) = runCatching { c.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun permitted(c: Context) = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Runs `myra start` in Termux, in the background. False if Termux refused. */
    fun startBrain(c: Context): Boolean {
        if (!installed(c) || !permitted(c)) return false
        val i = Intent()
            .setClassName(PACKAGE, "com.termux.app.RunCommandService")
            .setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", Myraa)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("start"))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }.isSuccess
    }
}
