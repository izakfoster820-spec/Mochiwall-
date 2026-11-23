package com.mochiwall.app

import android.app.Application
import com.mochiwall.app.data.ImageFrameProcessor
import com.mochiwall.app.data.ProjectRepository
import com.mochiwall.app.data.ProjectStorage

class MochiApplication : Application() {

    lateinit var projectRepository: ProjectRepository
        private set

    val imageFrameProcessor: ImageFrameProcessor by lazy {
        ImageFrameProcessor(this)
    }

    override fun onCreate() {
        super.onCreate()
        val storage = ProjectStorage(this)
        projectRepository = ProjectRepository(storage)
        projectRepository.initialize()
    }
}
