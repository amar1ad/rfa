package com.example

import android.app.Application

/**
 * Custom application class for SketchGit.
 * 
 * Note: The line `android:name=".SketchApplication"` has been removed from AndroidManifest.xml
 * to keep the configuration clean by default.
 * 
 * If you have local libraries (.jar) or SDKs that require custom application-wide initialization, 
 * you can add your initialization logic inside [onCreate] and restore the `android:name=".SketchApplication"` 
 * attribute in your AndroidManifest.xml under the `<application>` tag.
 */
class SketchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Custom application-wide initialization can be added here
        // E.g., initializing local JAR libraries or custom analytics engines.
    }
}
