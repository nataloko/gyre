package dev.gyre.wallpaper

import dev.gyre.wallpaper.data.ArtworkImporter
import dev.gyre.wallpaper.data.BundledCatalogRepository
import dev.gyre.wallpaper.data.CatalogRepository
import dev.gyre.wallpaper.data.DataStoreSettingsRepository
import dev.gyre.wallpaper.data.GyreCatalogRepository
import dev.gyre.wallpaper.data.ImportStore
import dev.gyre.wallpaper.data.SettingsRepository
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GyreApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val importer: ArtworkImporter by lazy {
        ArtworkImporter(
            store = ImportStore(filesDir),
            scope = applicationScope,
            usableSpace = filesDir::getUsableSpace,
        )
    }

    val catalogue: CatalogRepository by lazy {
        GyreCatalogRepository(
            bundled = BundledCatalogRepository(this),
            imported = importer.imported,
            scope = applicationScope,
        )
    }

    val settings: SettingsRepository by lazy {
        DataStoreSettingsRepository(this, catalogue, applicationScope)
    }

    override fun onCreate() {
        super.onCreate()
        // Parses the bundled catalogue and reads the import store off the main thread, before the
        // UI or the engine first needs either.
        applicationScope.launch {
            settings
        }
    }
}
