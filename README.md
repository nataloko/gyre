# Gyre

[![CI](https://github.com/nataloko/gyre/actions/workflows/ci.yml/badge.svg)](https://github.com/nataloko/gyre/actions/workflows/ci.yml)

Gyre is a fully offline Android 17 live wallpaper. Its layered, touch-responsive
vortexes react to your hand, the phone's tilt, and the launcher.

## Procedural artwork, all the way through

**Every piece of Gyre's artwork is procedurally generated.** The app does not
download images, use an online service, or depend on a pre-made art library.
The complete collection can be recreated locally, byte for byte, from the
definitions in `tools/catalog/` and the renderers in `tools/artwork/`.

The catalogue contains 26 pieces and 352 variants:

- Gyre Stack, the launcher icon's own geometry: nineteen square plates on one
  centred axis, their bands turning in three independent groups.
- Five layered spinner scenes.
- Six generative systems: reaction-diffusion, strange attractors,
  quasicrystal interference, recursive Truchet tiling, a Kleinian limit set,
  and hyperbolic circle limits folded from Poincaré reflections.
- Fourteen procedural spinner pieces and their effect variants.

The generated layers are bundled with the app so it remains completely offline
when installed. Each layer is a fully rendered colour image: the generators
can shade, light, and composite effects that a simple colour ramp cannot
describe. Colour variants therefore have their own generated pixels, while
effect variants reuse unchanged layers by content address. The current artwork
bundle is 950 files and about 124 MiB.

That bundle is not in this repository. It is a build product, so what a clone
carries is the definitions and renderers that make it, and you run the
generator once — see [Generate the catalogue](#generate-the-catalogue). The
same command runs in CI, which is where the downloadable builds get their
artwork.

Every choice made by a generator comes from a seeded random number generator
keyed by its catalogue identifiers. That is what makes a rebuild repeatable,
and why those identifiers must not be renamed.

Gyre was inspired in part by SwirlWalls' playful, touch-responsive spinner
wallpapers.

## Bringing your own artwork

The bundled collection is everything the app ships with, but not everything it
can show. **Import** in the collection sheet takes artwork from the phone's own
storage — a folder or a zip — and adds it to the collection alongside the
generated pieces.

Two things can be imported, and the app works out which from the file itself:

- **A pack**: a catalogue and its artwork, built by `tools/export_pack.py` from
  any Gyre asset tree and side-loaded onto the phone. Its checksums are verified
  as it copies.
- **Pictures**: any folder or zip of photographs. Each becomes a variant of one
  new piece, centre-cropped square so it turns, flicks and nudges like the rest
  of the collection.

Imported artwork is copied into the app's own storage, so it survives the
original being moved or deleted, and it is removed again from the header above
the pieces it brought. Nothing is uploaded and no network permission exists;
the system file picker is the only place Gyre asks for anything outside itself.

## License

Gyre is licensed under the [Apache License 2.0](LICENSE). This covers the app,
the procedural generators and catalogue definitions, and the artwork generated
from them by `tools/generate_catalog.py`. See [NOTICE](NOTICE) for attribution.

## Develop

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

The first command is what a fresh clone cannot skip: the images it writes are
not committed. It takes several minutes, and needs running again only when
something under `tools/` changes. JVM tests and lint do not need it at all —
the two manifests they read are tracked. Instrumentation tests need one more
command, because `SceneRendererTest` compares against reference frames that are
generated too:

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

The catalogue is a build product of the procedural definitions and renderers,
which is why the images are not committed. Build the complete bundle with:

```sh
uv run tools/generate_catalog.py
```

This writes the catalogue and all generated artwork to `app/src/main/assets/`,
using SHA-256 content-addressed paths. A run from unchanged inputs must produce
the same bytes, and the check that it did is:

```sh
git diff --exit-code -- app/src/main/assets/catalog
```

Those two manifests are the part of the catalogue that is tracked, and
`checksums.json` holds the SHA-256 of every generated image. So a clean diff
there is a complete statement that the rebuild reproduced all 948 of them, byte
for byte.

That holds on one machine, not across machines. NumPy dispatches different SIMD
kernels depending on the CPU, and the last-bit differences that follow
occasionally change a quantised pixel, which changes that file's content
address. Disabling AVX-512 on a single machine is enough to move about a tenth
of the hashes. The pictures are the same — under 0.02% of pixels differ by more
than a rounding step, and those sit on anti-aliasing boundaries — so the
identifiers move while the artwork does not. CI therefore checks that the
catalogue it rendered is well formed and legible, rather than that it matches
the manifests committed from somewhere else.

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

Tagging a commit `v1.5.0` builds and signs the release on CI and attaches the
APK and its checksum to a GitHub release, which is where installable builds
come from. The rest of this section is the same thing by hand.

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
