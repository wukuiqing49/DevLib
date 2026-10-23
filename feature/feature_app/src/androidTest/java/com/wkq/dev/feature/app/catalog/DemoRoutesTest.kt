package com.wkq.dev.feature.app.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wkq.router.api.Router
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only resolves destinations; never opens camera, purchases, networking or crash pages. */
@RunWith(AndroidJUnit4::class)
class DemoRoutesTest {
    @Test fun everyCatalogDestinationResolvesToADifferentActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Router.init(context)
        val destinations = DemoCatalog.entries.map { entry ->
            val route = Router.findRoute(entry.route)
            assertNotNull("Missing destination: ${entry.module}", route)
            assertTrue(android.app.Activity::class.java.isAssignableFrom(route!!.clazz))
            route.clazz
        }
        assertEquals(DemoCatalog.entries.size, destinations.toSet().size)
    }
}
