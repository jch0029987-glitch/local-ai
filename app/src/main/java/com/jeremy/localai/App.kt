package com.jeremy.localai

import android.app.Application
import com.topjohnwu.superuser.Shell

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Configure and initialize libsu globally
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }
}
