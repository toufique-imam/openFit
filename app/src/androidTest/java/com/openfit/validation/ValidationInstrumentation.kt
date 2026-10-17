package com.openfit.validation

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

internal object ValidationContext {
    lateinit var app: Context
    lateinit var instrumentation: Instrumentation
}

/** Never runs deletion/import fixtures inside the user's regular debug or release app. */
class ValidationInstrumentation : Instrumentation() {
    private var suite = "all"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); suite = arguments?.getString("suite") ?: "all"; start() }
    override fun onStart() {
        check(targetContext.packageName == "com.openfit.validation.debug") {
            "Use -PopenfitValidation=true; refusing fixtures against ${targetContext.packageName}"
        }
        ValidationContext.app = targetContext.applicationContext
        ValidationContext.instrumentation = this
        val core = JUnitCore()
        core.addListener(object : RunListener() {
            override fun testFailure(failure: Failure) {
                sendStatus(0, Bundle().apply { putString("stream", "FAIL: $failure\n${failure.trace}\n") })
            }
            override fun testFinished(description: org.junit.runner.Description) {
                sendStatus(0, Bundle().apply { putString("stream", "Finished: ${description.methodName}\n") })
            }
        })
        val data = arrayOf(HealthStoreDeviceTest::class.java, MigrationDeviceTest::class.java, HealthSyncDeviceTest::class.java)
        val classes = when (suite) {
            "ui" -> arrayOf(CustomizeDeviceTest::class.java)
            "data" -> data
            else -> data + arrayOf(CustomizeDeviceTest::class.java, DeletionDeviceTest::class.java)
        }
        val result = if (suite == "breathe") core.run(org.junit.runner.Request.method(CustomizeDeviceTest::class.java, "breathingSessionStartsPausesAndResets")) else core.run(*classes)
        finish(if (result.wasSuccessful()) -1 else 0, Bundle().apply {
            putString("stream", "${result.runCount} tests; ${result.failureCount} failures\n")
            putInt("tests", result.runCount)
            putInt("failures", result.failureCount)
        })
    }
}
