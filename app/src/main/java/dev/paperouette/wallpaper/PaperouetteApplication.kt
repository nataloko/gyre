package dev.paperouette.wallpaper

import dev.paperouette.wallpaper.data.ArtworkImporter
import dev.paperouette.wallpaper.data.BundledCatalogRepository
import dev.paperouette.wallpaper.data.CatalogRepository
import dev.paperouette.wallpaper.data.DataStoreSettingsRepository
import dev.paperouette.wallpaper.data.PaperouetteCatalogRepository
import dev.paperouette.wallpaper.data.ImportStore
import dev.paperouette.wallpaper.data.SettingsRepository
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PaperouetteApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val importer: ArtworkImporter by lazy {
        ArtworkImporter(
            store = ImportStore(filesDir),
            scope = applicationScope,
            usableSpace = filesDir::getUsableSpace,
        )
    }

    val catalogue: CatalogRepository by lazy {
        PaperouetteCatalogRepository(
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
