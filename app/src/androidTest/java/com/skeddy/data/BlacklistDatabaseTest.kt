package com.skeddy.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlacklistDatabaseTest {

    @Test
    fun getInstance_returnsSingleton() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val instance1 = BlacklistDatabase.getInstance(context)
        val instance2 = BlacklistDatabase.getInstance(context)

        assertSame(instance1, instance2)
    }

    @Test
    fun blacklistDao_returnsNonNull() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = BlacklistDatabase.getInstance(context)

        assertNotNull(database.blacklistDao())
    }
}
