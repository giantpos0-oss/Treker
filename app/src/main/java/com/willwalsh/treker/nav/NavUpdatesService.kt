package com.willwalsh.treker.nav

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.willwalsh.treker.TrekerApp

/**
 * The Navigation SDK sends each turn-by-turn update here. Treker shows the current step and keeps
 * the step list so it can be read on the guidance screen.
 */
class NavUpdatesService : Service() {
    private lateinit var tbt: TurnByTurnManager

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != TurnByTurnManager.MSG_NAV_INFO) return
            val info = runCatching { tbt.readNavInfoFromBundle(msg.data) }.getOrNull() ?: return
            val step = info.currentStep
            val upcoming = info.remainingSteps?.mapNotNull { it.fullInstructionText } ?: emptyList()
            (application as TrekerApp).guidance.onNavStep(step?.fullInstructionText, info.distanceToCurrentStepMeters, upcoming)
        }
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        tbt = TurnByTurnManager.createInstance()
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}
