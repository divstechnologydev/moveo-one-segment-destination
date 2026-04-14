package com.moveo.segment.sample

import android.app.Application
import com.segment.analytics.kotlin.android.Analytics

class SampleApp : Application() {

    companion object {
        lateinit var analytics: Analytics
            private set
    }

    override fun onCreate() {
        super.onCreate()

        analytics = Analytics(
            writeKey = "YOUR_SEGMENT_WRITE_KEY",
            context = applicationContext
        ) {
            trackApplicationLifecycleEvents = true
            // To stop sending events to Segment and only send to Moveo One:
            // autoAddSegmentDestination = false
        }

        analytics.add(plugin = MoveoOneDestination(
            apiKey = "YOUR_MOVEO_API_KEY",
            debug = true
        ))
    }
}
