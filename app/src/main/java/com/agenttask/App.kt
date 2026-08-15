package com.agenttask

import android.app.Application
import android.os.Build
import android.os.Environment
import com.agenttask.agent.Agent
import com.agenttask.agent.Tools
import com.agenttask.data.Db
import com.agenttask.data.SettingsStore
import com.agenttask.net.OpenAiClient
import java.io.File

class App : Application() {

    lateinit var db: Db
    lateinit var settings: SettingsStore
    lateinit var client: OpenAiClient
    lateinit var tools: Tools
    lateinit var agent: Agent

    @Volatile var workspace: String = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Db.open(this)
        settings = SettingsStore(this)
        client = OpenAiClient()
        workspace = defaultWorkspace()
        tools = Tools(this) { workspace }
        agent = Agent(client, tools)
    }

    fun defaultWorkspace(): String {
        val ext = getExternalFilesDir(null) ?: filesDir
        val ws = File(ext, "workspace")
        if (!ws.exists()) ws.mkdirs()
        return ws.absolutePath
    }

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    companion object {
        lateinit var instance: App
            private set
    }
}

