package com.huimao.map.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

class NavCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(sessionInfo: SessionInfo): Session = NavCarSession()
}

class NavCarSession : Session() {
    override fun onCreateScreen(intent: Intent) = NavCarScreen(carContext)
    override fun onNewIntent(intent: Intent) { getScreenManager().push(NavCarScreen(carContext)) }
}
