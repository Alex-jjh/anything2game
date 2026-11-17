package com.anything2game.app

import android.app.Application

class A2gApplication : Application() {
    val dependencies: AppDependencies by lazy { AndroidAppContainer(this) }
}
