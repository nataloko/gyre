package dev.paperouette.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.paperouette.wallpaper.data.DocumentTreeImportSource
import dev.paperouette.wallpaper.data.ImportSource
import dev.paperouette.wallpaper.data.ZipImportSource
import dev.paperouette.wallpaper.ui.PaperouetteApp
import dev.paperouette.wallpaper.wallpaper.PaperouetteWallpaperService

class MainActivity : ComponentActivity() {
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importFile)
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::importFolder)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both bars are pinned to light icons rather than following the system theme: the artwork
        // runs the full height of the window behind them, and only the app's own scrims decide
        // what is behind an icon.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val application = application as PaperouetteApplication
        setContent {
            PaperouetteApp(
                catalogueRepository = application.catalogue,
                repository = application.settings,
                importer = application.importer,
                onApplyWallpaper = ::openWallpaperPreview,
                onChooseFile = ::chooseFile,
                onChooseFolder = ::chooseFolder,
            )
        }
    }

    /** Returns whether a picker opened, so the app can report failure in its own surface. */
    private fun openWallpaperPreview(): Boolean {
        val component = ComponentName(this, PaperouetteWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            component,
        )
        return runCatching { startActivity(intent) }
            .recoverCatching { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            .isSuccess
    }

    /**
     * The system's own file picker, which is the only place Paperouette asks for anything outside itself.
     *
     * The wildcard type sits alongside the zip types on purpose: a file pushed over adb or handed
     * on by a browser often arrives typed `application/octet-stream`, or not at all, and a picker
     * that greys out the file the user came to choose is worse than one that lets them choose the
     * wrong thing — the import says so plainly enough.
     */
    private fun chooseFile() {
        filePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun chooseFolder() {
        folderPicker.launch(null)
    }

    private fun importFile(uri: Uri) = import(uri) { release ->
        ZipImportSource(
            label = displayName(uri),
            onClose = release,
            open = { requireNotNull(contentResolver.openInputStream(uri)) { "Cannot read $uri" } },
        )
    }

    private fun importFolder(uri: Uri) = import(uri) { release ->
        DocumentTreeImportSource(
            resolver = contentResolver,
            tree = uri,
            label = displayName(uri),
            onClose = release,
        )
    }

    /**
     * Hands [uri] to the importer with a read grant that lasts as long as the copy does.
     *
     * The copy runs on the application scope and outlives this activity, so the grant the picker
     * gave has to be persisted to survive it — and released the moment the source closes, rather
     * than left standing over the user's storage.
     */
    private fun import(uri: Uri, source: (release: () -> Unit) -> ImportSource) {
        val resolver = contentResolver
        val persisted = runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        (application as PaperouetteApplication).importer.start {
            source {
                if (persisted) {
                    runCatching {
                        resolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    /** What the picker calls the file, for when a pack does not name itself. */
    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "Imported artwork"
}
