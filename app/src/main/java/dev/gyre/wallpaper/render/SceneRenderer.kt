package dev.gyre.wallpaper.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import dev.gyre.wallpaper.data.ArtworkPaths
import dev.gyre.wallpaper.data.ImageSource
import dev.gyre.wallpaper.data.openStream
import dev.gyre.wallpaper.model.RampStop
import dev.gyre.wallpaper.model.Remix
import dev.gyre.wallpaper.model.SubsetLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Shared GLES 3 scene renderer used by both the app preview and wallpaper.
 *
 * It takes no catalogue: a scene arrives already resolved, and where a layer's bytes live is
 * decided by its catalogue path alone — see [ArtworkPaths].
 */
class SceneRenderer(private val context: Context) {
    private data class TextureLayer(
        val texture: Int,
        val width: Int,
        val height: Int,
    )

    private data class Framebuffer(
        var framebuffer: Int = 0,
        var texture: Int = 0,
        var width: Int = 0,
        var height: Int = 0,
    )

    private data class LayerUniforms(
        val texture: Int,
        val ramp: Int,
        val ramped: Int,
        val cyclic: Int,
        val rampMean: Int,
        val visibleScale: Int,
        val parallax: Int,
        val pivot: Int,
        val mirror: Int,
        val subset: Int,
        val rotation: Int,
        val dim: Int,
        val grayscale: Int,
    )

    private data class FilterUniforms(
        val texture: Int,
        val direction: Int,
        val dim: Int,
        val grayscale: Int,
    )

    internal data class DebugSnapshot(
        val sceneId: String?,
        val textureIdsByKey: Map<String, Int>,
        val framebufferCount: Int,
    )

    internal class SceneLoadException(
        val assetPath: String,
        cause: Throwable,
    ) : IllegalStateException("Cannot load scene asset $assetPath", cause)

    private var layerProgram = 0
    private var filterProgram = 0
    private var layerUniforms: LayerUniforms? = null
    private var filterUniforms: FilterUniforms? = null
    private var maxTextureSize = 0
    private var vertexArray = 0
    private var vertexBuffer = 0
    private var sceneId: String? = null
    private var textureBudget = 0
    /** Keyed by [ImageSource.cacheKey], so a bundled and an imported file never share an entry. */
    private var texturesByKey = emptyMap<String, TextureLayer>()
    private var sceneTextures = emptyList<TextureLayer>()

    /** One per scene layer, parallel to [sceneTextures]; 0 where the layer ships its own colour. */
    private var sceneRamps = emptyList<Int>()

    /** Each ramp's average colour, for a cyclic layer filtered past the point of resolving. */
    private var sceneRampMeans = emptyList<FloatArray>()
    private var sceneAspect = 1f
    private val sceneFramebuffer = Framebuffer()
    private val blurFramebuffer = Framebuffer()

    fun initialize() {
        check(layerProgram == 0) { "Renderer is already initialized" }
        layerProgram = createProgram(VERTEX_SHADER, LAYER_FRAGMENT_SHADER)
        filterProgram = createProgram(VERTEX_SHADER, FILTER_FRAGMENT_SHADER)
        layerUniforms = LayerUniforms(
            texture = location(layerProgram, "uTexture"),
            ramp = location(layerProgram, "uRamp"),
            ramped = location(layerProgram, "uRamped"),
            cyclic = location(layerProgram, "uCyclic"),
            rampMean = location(layerProgram, "uRampMean"),
            visibleScale = location(layerProgram, "uVisibleScale"),
            parallax = location(layerProgram, "uParallax"),
            pivot = location(layerProgram, "uPivot"),
            mirror = location(layerProgram, "uMirror"),
            subset = location(layerProgram, "uSubset"),
            rotation = location(layerProgram, "uRotation"),
            dim = location(layerProgram, "uDim"),
            grayscale = location(layerProgram, "uGrayscale"),
        )
        filterUniforms = FilterUniforms(
            texture = location(filterProgram, "uTexture"),
            direction = location(filterProgram, "uDirection"),
            dim = location(filterProgram, "uDim"),
            grayscale = location(filterProgram, "uGrayscale"),
        )
        val maximum = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maximum, 0)
        maxTextureSize = maximum[0]
        check(maxTextureSize > 0) { "OpenGL reported an invalid maximum texture size" }

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buffer = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .apply { position(0) }
        val arrays = IntArray(1)
        val buffers = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        GLES30.glGenBuffers(1, buffers, 0)
        vertexArray = arrays[0]
        vertexBuffer = buffers[0]
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            buffer,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        checkGl("initialize")
    }

    fun render(
        scene: Remix,
        viewport: Viewport,
        monotonicNanos: Long,
        motion: MotionState,
        filters: FilterState,
        rotationCenter: RotationCenter = RotationCenter.Center,
        mirrored: Boolean = false,
        rotationReversed: Boolean = false,
    ) {
        check(layerProgram != 0) { "Renderer is not initialized" }
        val requiredBudget = max(viewport.width, viewport.height)
        if (scene.id != sceneId || requiredBudget > textureBudget) loadScene(scene, requiredBudget)

        val normalizedFilters = filters.normalized()
        // The launcher's zoom darkens the artwork along with closing it in, so whatever is drawn
        // over it reads. It is deliberately kept out of FilterState: the filters are the user's
        // settled choice and are what the wallpaper reports its colours through, while this is a
        // transient the launcher is holding open.
        val effectiveDim = (normalizedFilters.dim + zoomOf(motion) * ZOOM_DIM_DEPTH)
            .coerceIn(0f, 1f)
        val blurred = normalizedFilters.blur > BLUR_EPSILON
        if (blurred) {
            val targetWidth = max(1, ceil(viewport.width / 4f).toInt())
            val targetHeight = max(1, ceil(viewport.height / 4f).toInt())
            ensureFramebuffer(sceneFramebuffer, targetWidth, targetHeight)
            ensureFramebuffer(blurFramebuffer, targetWidth, targetHeight)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFramebuffer.framebuffer)
            GLES30.glViewport(0, 0, targetWidth, targetHeight)
            clearTarget()
            drawLayers(
                scene,
                viewport.aspectRatio,
                monotonicNanos,
                motion,
                rotationCenter,
                mirrored,
                rotationReversed,
                0f,
                0f,
            )

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFramebuffer.framebuffer)
            GLES30.glViewport(0, 0, targetWidth, targetHeight)
            clearTarget()
            drawFilter(
                texture = sceneFramebuffer.texture,
                directionX = 1f / targetWidth,
                directionY = 0f,
                blur = normalizedFilters.blur,
                dim = 0f,
                grayscale = 0f,
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewport.width, viewport.height)
            drawFilter(
                texture = blurFramebuffer.texture,
                directionX = 0f,
                directionY = 1f / targetHeight,
                blur = normalizedFilters.blur,
                dim = effectiveDim,
                grayscale = normalizedFilters.grayscale,
            )
        } else {
            releaseFilterBuffers()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewport.width, viewport.height)
            clearTarget()
            drawLayers(
                scene = scene,
                viewportAspect = viewport.aspectRatio,
                monotonicNanos = monotonicNanos,
                motion = motion,
                rotationCenter = rotationCenter,
                mirrored = mirrored,
                rotationReversed = rotationReversed,
                dim = effectiveDim,
                grayscale = normalizedFilters.grayscale,
            )
        }
        checkGl("render ${scene.id}")
    }

    /** Guards the shader against a launcher reporting something outside 0..1, or nothing at all. */
    private fun zoomOf(motion: MotionState): Float =
        motion.launcherZoom.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f

    private fun clearTarget() {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun loadScene(scene: Remix, requiredBudget: Int) {
        require(scene.layers.isNotEmpty()) { "Scene ${scene.id} has no layers" }
        if (requiredBudget > textureBudget) {
            // Cached textures were decoded for a smaller viewport; reload them sharper.
            releaseTextures()
            textureBudget = requiredBudget
        }
        val sources = scene.layers.map { ArtworkPaths.resolve(it.imageUrl, context) }
        val newlyLoaded = linkedMapOf<String, TextureLayer>()
        val newRamps = mutableListOf<Int>()
        val newRampMeans = mutableListOf<FloatArray>()
        var loadingPath = sources.first().path
        try {
            sources.distinctBy(ImageSource::cacheKey).forEach { source ->
                loadingPath = source.path
                if (source.cacheKey !in texturesByKey) {
                    newlyLoaded[source.cacheKey] = loadTexture(source)
                }
            }
            val nextTextures = sources.map { source ->
                texturesByKey[source.cacheKey] ?: requireNotNull(newlyLoaded[source.cacheKey])
            }
            val first = nextTextures.first()
            val firstSubset = scene.layers.first().imageSubsetLayoutParams ?: SubsetLayout.Full
            val inferredWidth = first.width / firstSubset.sceneWidthRatio.coerceAtLeast(0.001f)
            val inferredHeight = first.height / firstSubset.sceneHeightRatio.coerceAtLeast(0.001f)
            scene.layers.forEach { layer ->
                newRamps += layer.ramp?.let(::createRampTexture) ?: 0
                newRampMeans += layer.ramp?.let(RampTexture::mean) ?: NO_RAMP_MEAN
            }
            val retainedKeys = sources.mapTo(mutableSetOf(), ImageSource::cacheKey)
            val obsolete = texturesByKey.filterKeys { it !in retainedKeys }.values
            val obsoleteRamps = sceneRamps
            texturesByKey = retainedKeys.associateWith { key ->
                texturesByKey[key] ?: requireNotNull(newlyLoaded[key])
            }
            sceneTextures = nextTextures
            sceneRamps = newRamps
            sceneRampMeans = newRampMeans
            sceneAspect = inferredWidth / inferredHeight
            sceneId = scene.id
            deleteTextures(obsolete)
            deleteRamps(obsoleteRamps)
            checkGl("load scene ${scene.id}")
        } catch (error: Throwable) {
            deleteTextures(newlyLoaded.values)
            deleteRamps(newRamps)
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                // Clear errors from the failed upload so the previous scene remains renderable.
            }
            throw if (error is SceneLoadException) error else SceneLoadException(loadingPath, error)
        }
    }

    private fun loadTexture(source: ImageSource): TextureLayer {
        val assetPath = source.path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.openStream(context).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot decode $assetPath" }
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSizeFor(min(bounds.outWidth, bounds.outHeight))
        }
        val bitmap = source.openStream(context).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input, null, options)) {
                "Cannot decode $assetPath"
            }
        }
        var generatedTexture = 0
        try {
            require(bitmap.width <= maxTextureSize && bitmap.height <= maxTextureSize) {
                "$assetPath is ${bitmap.width}x${bitmap.height}; GL maximum is $maxTextureSize"
            }
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            check(textures[0] != 0) { "OpenGL did not allocate a texture for $assetPath" }
            generatedTexture = textures[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR_MIPMAP_LINEAR,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE,
            )
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            checkGl("upload $assetPath")
            return TextureLayer(textures[0], bitmap.width, bitmap.height)
        } catch (error: Throwable) {
            if (generatedTexture != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(generatedTexture), 0)
            }
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawLayers(
        scene: Remix,
        viewportAspect: Float,
        monotonicNanos: Long,
        motion: MotionState,
        rotationCenter: RotationCenter,
        mirrored: Boolean,
        rotationReversed: Boolean,
        dim: Float,
        grayscale: Float,
    ) {
        val uniforms = requireNotNull(layerUniforms)
        check(scene.layers.size == sceneTextures.size) { "Scene textures are incomplete" }
        GLES30.glUseProgram(layerProgram)
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUniform1i(uniforms.texture, 0)
        GLES30.glUniform1i(uniforms.ramp, 1)
        // The base layer is the one that has to cover the screen; the layers above it are drawn on
        // transparent surrounds, so their corners leaving the image is invisible. Framing the whole
        // scene by the base layer's requirement keeps every layer registered with the others.
        val pivot = rotationCenter.sanitized()
        val window = SceneCoverage.zoomed(
            SceneCoverage.window(scene, sceneAspect, viewportAspect, pivot),
            zoomOf(motion),
        )
        GLES30.glUniform2f(uniforms.visibleScale, window.x, window.y)
        // The setting measures down from the top of the screen, the way a touch does; the shader's
        // vUv measures up from the bottom.
        GLES30.glUniform2f(uniforms.pivot, pivot.x, 1f - pivot.y)
        GLES30.glUniform2f(uniforms.mirror, if (mirrored) -1f else 1f, 1f)
        GLES30.glUniform1f(uniforms.dim, dim)
        GLES30.glUniform1f(uniforms.grayscale, grayscale)

        scene.layers.forEachIndexed { index, layer ->
            val textureLayer = sceneTextures[index]
            val subset = layer.imageSubsetLayoutParams ?: SubsetLayout.Full
            // Clamped to what the framing actually granted: near an edge the safe circle is
            // small, and panning the nominal amount would push a corner off the artwork.
            val (rawParallaxX, rawParallaxY) = LayerTransform.parallax(layer, motion)
            val parallaxX = rawParallaxX.coerceIn(-window.panAllowance, window.panAllowance)
            val parallaxY = rawParallaxY.coerceIn(-window.panAllowance, window.panAllowance)
            val rotation = LayerTransform.totalRotationRadians(
                layer = layer,
                monotonicNanos = monotonicNanos,
                spinRadians = motion.spinRadians,
                inputRotationScaler = scene.inputRotationScaler,
                reversed = rotationReversed,
            )
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureLayer.texture)
            val rampTexture = sceneRamps.getOrElse(index) { 0 }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rampTexture)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glUniform1f(uniforms.ramped, if (rampTexture != 0) 1f else 0f)
            GLES30.glUniform1f(uniforms.cyclic, if (layer.cyclic) 1f else 0f)
            val mean = sceneRampMeans.getOrElse(index) { NO_RAMP_MEAN }
            GLES30.glUniform4f(uniforms.rampMean, mean[0], mean[1], mean[2], mean[3])
            GLES30.glUniform1f(uniforms.rotation, rotation)
            GLES30.glUniform2f(uniforms.parallax, parallaxX, parallaxY)
            GLES30.glUniform4f(
                uniforms.subset,
                subset.xRatio,
                subset.yRatio,
                subset.sceneWidthRatio,
                subset.sceneHeightRatio,
            )
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
    }

    private fun drawFilter(
        texture: Int,
        directionX: Float,
        directionY: Float,
        blur: Float,
        dim: Float,
        grayscale: Float,
    ) {
        val uniforms = requireNotNull(filterUniforms)
        GLES30.glUseProgram(filterProgram)
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uniforms.texture, 0)
        GLES30.glUniform2f(
            uniforms.direction,
            directionX * (1f + blur * 7f),
            directionY * (1f + blur * 7f),
        )
        GLES30.glUniform1f(uniforms.dim, dim)
        GLES30.glUniform1f(uniforms.grayscale, grayscale)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    private fun ensureFramebuffer(target: Framebuffer, width: Int, height: Int) {
        if (target.framebuffer != 0 && target.width == width && target.height == height) return
        releaseFramebuffer(target)
        val textures = IntArray(1)
        val framebuffers = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textures[0],
            0,
        )
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, framebuffers, 0)
            GLES30.glDeleteTextures(1, textures, 0)
            error("Cannot create ${width}x$height framebuffer")
        }
        target.framebuffer = framebuffers[0]
        target.texture = textures[0]
        target.width = width
        target.height = height
    }

    fun trimMemory() {
        releaseTextures()
        releaseFilterBuffers()
    }

    fun release() {
        trimMemory()
        if (vertexBuffer != 0) GLES30.glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
        if (vertexArray != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
        if (layerProgram != 0) GLES30.glDeleteProgram(layerProgram)
        if (filterProgram != 0) GLES30.glDeleteProgram(filterProgram)
        vertexBuffer = 0
        vertexArray = 0
        layerProgram = 0
        filterProgram = 0
        layerUniforms = null
        filterUniforms = null
        maxTextureSize = 0
        textureBudget = 0
    }

    internal fun debugSnapshot() = DebugSnapshot(
        sceneId = sceneId,
        textureIdsByKey = texturesByKey.mapValues { it.value.texture },
        framebufferCount = listOf(sceneFramebuffer, blurFramebuffer).count { it.framebuffer != 0 },
    )

    private fun releaseTextures() {
        deleteTextures(texturesByKey.values)
        deleteRamps(sceneRamps)
        texturesByKey = emptyMap()
        sceneTextures = emptyList()
        sceneRamps = emptyList()
        sceneRampMeans = emptyList()
        sceneId = null
    }

    private fun deleteRamps(ramps: Collection<Int>) {
        val allocated = ramps.filter { it != 0 }
        if (allocated.isNotEmpty()) {
            GLES30.glDeleteTextures(allocated.size, allocated.toIntArray(), 0)
        }
    }

    /**
     * A layer's ramp as a [RampTexture.WIDTH]×1 lookup, or 0 when the layer is already coloured.
     *
     * Clamped and unmipmapped: it is sampled by the mask's value, not by position on screen, so
     * there is no minification to filter for and an edge stop must stay the colour it was given.
     */
    private fun createRampTexture(stops: List<RampStop>): Int {
        val pixels = ByteBuffer.allocateDirect(RampTexture.WIDTH * 4)
            .order(ByteOrder.nativeOrder())
            .put(RampTexture.build(stops))
            .apply { position(0) }
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        check(textures[0] != 0) { "OpenGL did not allocate a ramp texture" }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            RampTexture.WIDTH,
            1,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            pixels,
        )
        return textures[0]
    }

    private fun deleteTextures(textures: Collection<TextureLayer>) {
        if (textures.isNotEmpty()) {
            GLES30.glDeleteTextures(textures.size, textures.map(TextureLayer::texture).toIntArray(), 0)
        }
    }

    private fun releaseFilterBuffers() {
        releaseFramebuffer(sceneFramebuffer)
        releaseFramebuffer(blurFramebuffer)
    }

    private fun releaseFramebuffer(target: Framebuffer) {
        if (target.framebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
        }
        if (target.texture != 0) GLES30.glDeleteTextures(1, intArrayOf(target.texture), 0)
        target.framebuffer = 0
        target.texture = 0
        target.width = 0
        target.height = 0
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES30.glCreateProgram().also { program ->
            GLES30.glAttachShader(program, vertex)
            GLES30.glAttachShader(program, fragment)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            check(status[0] == GLES30.GL_TRUE) { "Program link failed: $log" }
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader ->
        GLES30.glShaderSource(shader, source.trimStart())
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) {
            "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}"
        }
    }

    private fun location(program: Int, name: String): Int =
        GLES30.glGetUniformLocation(program, name).also { uniformLocation ->
            check(uniformLocation >= 0) { "Missing shader uniform $name" }
        }

    /** Halves decode resolution while the texture stays comfortably above the viewport's needs. */
    private fun sampleSizeFor(minDimension: Int): Int {
        val target = ceil(textureBudget * SAMPLE_MARGIN).toInt()
        var sample = 1
        while (target > 0 && minDimension / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun checkGl(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "OpenGL error 0x${error.toString(16)} after $operation" }
    }

    private companion object {
        const val BLUR_EPSILON = 0.001f

        /** Stands in for a layer that has no ramp, where the uniform is never read. */
        val NO_RAMP_MEAN = floatArrayOf(0f, 0f, 0f, 0f)

        // Cover-fit can put a max(viewport) span of texture on screen; the margin absorbs
        // parallax shifts and subset layouts that sample a little beyond that.
        const val SAMPLE_MARGIN = 1.5f

        // How far the artwork darkens when the launcher is fully zoomed away, so that whatever the
        // launcher draws over it reads. How far it closes in is SceneCoverage.ZOOM_DEPTH, which
        // lives there because it is framing and has to be proved safe alongside the rest of it.
        const val ZOOM_DIM_DEPTH = 0.25f

        const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPosition;
            out vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        const val LAYER_FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform sampler2D uTexture;
            uniform sampler2D uRamp;
            uniform float uRamped;
            uniform float uCyclic;
            uniform vec4 uRampMean;
            uniform vec2 uVisibleScale;
            uniform vec2 uParallax;
            uniform vec2 uPivot;
            uniform vec2 uMirror;
            uniform vec4 uSubset;
            uniform float uRotation;
            uniform float uDim;
            uniform float uGrayscale;
            in vec2 vUv;
            out vec4 outColor;
            void main() {
                // Screen position measured from the point the scene turns about, so the artwork's
                // middle lands there and the rotation happens around it rather than around the
                // middle of the glass.
                // uMirror reflects the mapping about the pivot rather than about the middle
                // of the glass, so flipping the artwork does not also move the point it turns
                // about. Reflection preserves |offset|, and SceneCoverage frames by that distance
                // alone, so the no-edges guarantee is untouched by it.
                vec2 offset = (vUv - uPivot) * uVisibleScale * uMirror + uParallax;
                float c = cos(-uRotation);
                float s = sin(-uRotation);
                vec2 scene = mat2(c, -s, s, c) * offset + 0.5;
                vec2 uv = (scene - uSubset.xy) / uSubset.zw;
                if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
                    discard;
                }
                uv.y = 1.0 - uv.y;
                vec4 color = texture(uTexture, uv);
                // A ramped layer ships as a mask and takes its colour from here, so every colour
                // variant of a piece can share the one mask. Both sides are premultiplied, which
                // is what keeps the lookup a straight substitution.
                if (uRamped > 0.5) {
                    if (uCyclic > 0.5) {
                        // The mask carries (cos, sin) of a wrapping value, because a wrapping
                        // value cannot be interpolated as a scalar: either side of the wrap the
                        // texels hold 1 and 0 and every sample between them lands on the far side
                        // of the ramp. As a vector it interpolates to the right angle, and its
                        // length reports how much of the cycle survived — at zero the filtering
                        // has averaged a whole turn away and the ramp's mean is the honest answer.
                        vec2 turn = color.rg * 2.0 - 1.0;
                        float position = atan(turn.y, turn.x) * 0.15915494 + 0.5;
                        float resolved = clamp(length(turn) * 1.6, 0.0, 1.0);
                        color = mix(uRampMean, texture(uRamp, vec2(position, 0.5)), resolved);
                    } else {
                        color = texture(uRamp, vec2(color.r, 0.5));
                    }
                }
                float luminance = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
                color.rgb = mix(color.rgb, vec3(luminance), uGrayscale);
                color.rgb *= 1.0 - uDim;
                outColor = color;
            }
        """

        const val FILTER_FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform sampler2D uTexture;
            uniform vec2 uDirection;
            uniform float uDim;
            uniform float uGrayscale;
            in vec2 vUv;
            out vec4 outColor;
            void main() {
                vec4 color = texture(uTexture, vUv) * 0.227027;
                color += texture(uTexture, vUv + uDirection * 1.384615) * 0.316216;
                color += texture(uTexture, vUv - uDirection * 1.384615) * 0.316216;
                color += texture(uTexture, vUv + uDirection * 3.230769) * 0.070270;
                color += texture(uTexture, vUv - uDirection * 3.230769) * 0.070270;
                float luminance = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
                color.rgb = mix(color.rgb, vec3(luminance), uGrayscale);
                color.rgb *= 1.0 - uDim;
                outColor = color;
            }
        """
    }
}
