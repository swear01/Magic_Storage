# Magic Storage

[![CI](https://github.com/swear01/Magic_Storage/actions/workflows/ci.yml/badge.svg)](https://github.com/swear01/Magic_Storage/actions/workflows/ci.yml)

`magic_storage` is a NeoForge 1.21.1 storage + crafting mod. It provides a server-authoritative storage network, storage/crafting terminals, crafting energy, import/export buses, and an in-game Patchouli guide.

Public repository: https://github.com/swear01/Magic_Storage

## Requirements

- JDK 21
- Gradle wrapper from this repository

On the local Mac dev machine, set:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

## Build and test

```bash
./gradlew build
./gradlew runGameTestServer
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover scripts
```

Expected automated coverage is currently SelfTest 104 + GameTest 74, plus Python script tests.

## CI/CD

GitHub Actions runs on pushes to `main`, pull requests, and manual dispatch:

- `.github/workflows/ci.yml` builds the mod, runs `./gradlew runGameTestServer`, runs Python script tests, and uploads `build/libs/magic_storage-*.jar` as an artifact.
- `.github/workflows/release.yml` runs when a tag `v<mod_version>` is pushed, verifies the tag matches `gradle.properties`, repeats the build/tests, then creates a GitHub Release with the jar.

Release example:

```bash
git tag v0.1.3
git push origin main v0.1.3
```

## Manual GUI verification

Automated tests do not verify Minecraft GUI rendering. For terminal/Patchouli/visual changes, follow the fixed Prism dev + Computer Use workflow in `docs/notes.md` under “Prism dev / Computer Use”.

## License

All Rights Reserved.
