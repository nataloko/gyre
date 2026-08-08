# Gyre

[![CI](https://github.com/nataloko/gyre/actions/workflows/ci.yml/badge.svg)](https://github.com/nataloko/gyre/actions/workflows/ci.yml)

Gyre is a fully offline live wallpaper for Android 17, inspired in part by
SwirlWalls — the wonderfully playful spinner wallpaper that now appears to be
retired and is no longer available on Google Play. Gyre carries that idea in
its own direction: layered, touch-responsive artwork that reacts to your hand,
the phone's tilt, and the launcher.

## Artwork made from scratch

Every piece bundled with Gyre is procedurally generated. There are no downloads,
online services, or stock-art libraries hiding behind it. The whole collection
can be rebuilt locally from the definitions in `tools/catalog/` and the
renderers in `tools/artwork/`.

The catalogue contains 26 pieces and 352 variants:

- Gyre Stack, the launcher icon's own geometry: nineteen square plates on one
  centred axis, their bands turning in three independent groups.
- Five layered spinner scenes.
- Six generative systems: reaction-diffusion, strange attractors,
  quasicrystal interference, recursive Truchet tiling, a Kleinian limit set,
  and hyperbolic circle limits folded from Poincaré reflections.
- Fourteen procedural spinner pieces and their effect variants.

The finished layers are bundled with the app, so it stays completely offline
after installation. Each layer is a fully rendered colour image because the
generators do more than a simple colour ramp can describe: they shade, light,
and composite the artwork too. Colour variants therefore get their own pixels,
while effect variants reuse any layers that have not changed. The current
artwork bundle is 950 files and about 124 MiB.

The bundle itself is not checked into this repository. A clone contains the
recipes and the tools that make it; run the generator once to produce the
images (see [Generate the catalogue](#generate-the-catalogue)). CI does the
same when it makes a downloadable build.

Even the apparently random choices are repeatable. They come from seeded random
number generators tied to catalogue identifiers, which is why those identifiers
must not be renamed.

## Add your own artwork

The bundled collection is only the starting point. Choose **Import** in the
collection sheet to add a folder or zip from your phone alongside Gyre's own
generated pieces.

Gyre works out what you gave it:

- **A pack**: a catalogue and its artwork, built by `tools/export_pack.py` from
  any Gyre asset tree and side-loaded onto the phone. Its checksums are verified
  as it copies.
- **Pictures**: any folder or zip of photographs. Each becomes a variant of one
  new piece, centre-cropped square so it turns, flicks and nudges like the rest
  of the collection.

Imported artwork is copied into the app's own storage, so moving or deleting
the original will not break your wallpaper. You can remove an import again from
the header above the pieces it added. Nothing is uploaded, and Gyre has no
network permission; the system file picker is the only place it asks for
anything outside itself.

## License

Gyre is licensed under the [Apache License 2.0](LICENSE). This covers the app,
the procedural generators and catalogue definitions, and the artwork generated
from them by `tools/generate_catalog.py`. See [NOTICE](NOTICE) for attribution.

## Work on Gyre

You need JDK 17, `uv`, and an Android SDK containing:

- `platforms;android-37.0`
- `build-tools;37.0.0`
- `platform-tools`

Install the SDK through Google's current `cmdline-tools`. API 37 is available
there as `platforms;android-37.0`, not `platforms;android-37`. Point Gradle at
the SDK with `ANDROID_HOME` or a git-ignored `local.properties` file containing
`sdk.dir=...`.

Generate the artwork, then build a debug APK:

```sh
uv run tools/generate_catalog.py
./gradlew assembleDebug
```

Do not skip the first command on a fresh clone: the images it creates are not
committed. It takes several minutes, but you only need to run it again after
something under `tools/` changes. JVM tests and lint read the two tracked
manifests, so they do not need the generated images. Instrumentation tests do
need one extra step because `SceneRendererTest` compares against generated
reference frames:

```sh
uv run tools/generate_catalog.py --fixtures app/src/androidTest/assets/previews
```

Enable the repository hooks once per clone:

```sh
git config core.hooksPath tools/hooks
```

Before a push, this runs `testDebugUnitTest lintDebug`, the same checks CI runs
on the other side. Set `GYRE_SKIP_VERIFY=1` to skip them for one push.

`flake.nix` remains only for a possible return to NixOS. Do not set
`android.aapt2FromMavenOverride`; it was a NixOS-only workaround.

Gyre targets Android API 37 only. It deliberately declares no network
permission and includes no runtime HTTP client.

## Generate the catalogue

The catalogue is built from the procedural definitions and renderers, rather
than stored in Git. Generate the complete bundle with:

```sh
uv run tools/generate_catalog.py
```

This writes the catalogue and all generated artwork to `app/src/main/assets/`,
using SHA-256 content-addressed paths. A run from unchanged inputs must produce
the same bytes, and the check that it did is:

```sh
git diff --exit-code -- app/src/main/assets/catalog
```

Only the two manifests are tracked. Because `checksums.json` contains the
SHA-256 of every generated image, a clean diff confirms that all 948 images
were reproduced byte for byte.

That byte-for-byte promise applies on the same machine, not between different
CPUs. NumPy chooses different SIMD kernels for different hardware, and tiny
rounding differences can change a pixel and therefore its content-addressed
filename. Disabling AVX-512 alone moves about a tenth of the hashes. Visually,
the results are the same: fewer than 0.02% of pixels differ by more than one
rounding step, all around anti-aliased edges. CI therefore checks that its newly
rendered catalogue is valid and legible instead of comparing hashes produced on
another machine.

The source definitions for pieces, layers, rotations, and variants are in:

- `tools/catalog/designs.toml`
- `tools/catalog/spinner_designs.json`

The procedural renderers, palettes, and colour recipes are in
`tools/artwork/`.

Run the generator in this order when changing the collection:

```sh
uv run tools/generate_catalog.py --metadata-only --out app/src/main/assets/catalog
./gradlew --rerun-tasks testDebugUnitTest --tests '*ArtworkColorSchemeTest'
uv run tools/generate_catalog.py
uv run tools/generate_catalog.py --fixtures app/src/androidTest/assets/previews
```

`--metadata-only` writes `catalog.json` without rendering any artwork, so
`ArtworkColorSchemeTest` can check that every variant has legible app chrome
before you spend a full 352-variant render finding out it does not. It has to
be written where the test reads it, which is the tracked
`app/src/main/assets/catalog/catalog.json`, and `--rerun-tasks` is what stops
Gradle serving a cached pass from before the swap.

That first command leaves `catalog.json` holding placeholder image paths. The
third replaces it with the real one, so run them together; `git diff` on
`app/src/main/assets/catalog` afterwards is what confirms you did. The fourth
updates the GL reference frames `SceneRendererTest` compares against, and is
needed only when a piece that test covers changes.

## Build a signed release

Tagging a commit `v1.5.0` asks CI to build and sign the release, then attach the
APK and its checksum to a GitHub release. That is where installable builds come
from. To do the same thing locally:

Create a persistent signing key once, then back up both generated signing files
somewhere secure:

```sh
uv run tools/create_release_key.py
```

Build and package the release:

```sh
./gradlew assembleRelease
uv run tools/package_release.py
```

The build never falls back to the debug key when release credentials are
missing. For installation, upgrades, offline testing, and key backup, see
[`docs/INSTALL.md`](docs/INSTALL.md).
