package testhelpers

import android.app.Application

/**
 * Minimal application for Robolectric tests.
 * Skips all heavy initialization to speed up test execution.
 */
class TestApplication : Application()
