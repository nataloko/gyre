# Build, install, and preserve Gyre

Gyre is fully offline. Its complete artwork collection is procedurally
generated in this repository and bundled into the APK, so installing it never
needs a network connection.

The quickest way to a signed APK is not to build one: tagging a commit `v*`
makes CI generate the artwork, build, sign, and attach the package and its
checksum to a GitHub release. Everything below is how to do the same by hand.

## Build a signed APK

First install the toolchain described in [`README.md`](../README.md): JDK 17,
`uv`, and an Android SDK with `platforms;android-37.0` and
`build-tools;37.0.0`. Gradle finds the SDK through `ANDROID_HOME` or
`local.properties`.

Generate the artwork if this clone has not yet. It is not committed, and a
release without it will not get past `package_release.py`:

```sh
uv run tools/generate_catalog.py
```

If this checkout does not have signing credentials yet, create them once:

```sh
uv run tools/create_release_key.py
```

Back up both `gyre-release.jks` and `keystore.properties` in a secure location.
They are intentionally ignored by Git. Every future upgrade must use this same
key; if it is lost, Android will refuse to install an update over an existing
copy of Gyre.

Build, verify, and package the release:

```sh
./gradlew assembleRelease
uv run tools/package_release.py
```

The package is written to `dist/Gyre-<versionName>-release.apk`. Its version is
read from the APK itself, so it always matches `app/build.gradle.kts`.
`dist/SHA256SUMS` lists the checksum for only that package. Before releasing a
changed build, increment both `versionCode` and `versionName`; Android will not
install a lower `versionCode` over a newer one.

## Install or upgrade

Enable USB debugging on an Android 17 phone, connect it, and check that ADB can
see it:

```sh
adb devices
```

Wireless debugging works too. In Developer options, open **Wireless debugging**
→ **Pair device with code**, then use mDNS to find the pairing endpoint:

```sh
adb mdns services                       # _adb-tls-pairing while the dialog is open
adb pair <host>:<port> <6-digit code>   # once per machine; pairing persists
adb connect <host>:<port>               # the _adb-tls-connect endpoint
```

A phone can appear twice: once under its mDNS name and once under `ip:port`.
That makes Gradle run connected tests twice. `adb disconnect <ip>:<port>`
removes the direct connection while leaving the mDNS transport available across
port changes and reboots.

Install the APK:

```sh
adb install -r dist/Gyre-<version>-release.apk
```

`-r` upgrades an existing same-key installation without clearing settings. If
more than one phone is attached, add `-s <serial>` to select one.

Open Gyre, pull up the sheet to choose a piece and variant, and select **Set
wallpaper**. Android then shows its standard live-wallpaper preview before
applying it.

Because `-r` preserves app data, use an upgrade—not a clean install—when
checking a fix for saved settings. A clean install can hide a problem that
remains on existing phones.

## Build and side-load an artwork pack

A pack carries a catalogue and its artwork into the app without being compiled
into it — which is how a collection Gyre cannot bundle reaches a phone.

```sh
uv run tools/export_pack.py \
    --assets /path/to/another/asset/tree \
    --name "A Collection" \
    --out dist/a-collection.zip
adb push dist/a-collection.zip /sdcard/Download/
```

The exporter verifies every file against its own content address before writing
anything, so it fails loudly if the source tree's catalogue has not been
generated. It then re-reads what it wrote; `--verify <pack>` does that alone,
offline.

On the phone: pull the collection up, **Import**, **A file**, and choose the
zip. The app checks the manifest and free space before it copies a byte, and
verifies each file's checksum as it arrives. A pack of 516 files and 178 MiB
imports in about three seconds.

Pictures need no tooling at all: **Import**, **A folder**, and choose any folder
of photographs.

## Check offline operation

Enable airplane mode. Clear only system or download caches, not Gyre's app
data. Then verify that the live stage, collection, variant strip, Look and
Behaviour panels, and installed wallpaper still work.

They should: Gyre has no network permission or runtime HTTP library, and every
procedurally generated artwork layer needed at runtime ships in the APK.

## What must never be published

Artwork this repository does not license — anything imported into the app, and
any pack exported from a tree containing it — stays off the public remote, out
of release assets, and out of attachments. The app's own bundled artwork is
generated from `tools/` and carries no such restriction, which is exactly why
imported material ships as a file to import rather than as part of the APK.

Commits must be authored with the ID-prefixed GitHub noreply address from
GitHub Settings → Emails, never a personal one.
