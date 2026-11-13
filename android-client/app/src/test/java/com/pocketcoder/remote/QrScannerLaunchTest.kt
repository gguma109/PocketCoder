package com.pocketcoder.remote

import com.journeyapps.barcodescanner.CaptureActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QrScannerLaunchTest {
    // Regression: the scanner crashed in onResume with NoClassDefFoundError (androidx ContextCompat missing).
    @Test fun scannerActivityResumesWithoutMissingClasses() {
        Robolectric.buildActivity(CaptureActivity::class.java).setup().pause().stop().destroy()
    }
}
