package dev.paperouette.wallpaper.render

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import dev.paperouette.wallpaper.data.BundledCatalogRepository
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class CatalogRenderStressTest {
    @Test
    fun everyRemixCanReplaceItsTexturesRepeatedly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalogue = BundledCatalogRepository(context).current.value
        OffscreenRenderer(context, width = 72, height = 128).use { renderer ->
            repeat(2) {
                catalogue.remixes.forEach(renderer::render)
            }
        }
    }
}

