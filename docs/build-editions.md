# Build editions

The `visuals` flavor selects the feature at compile time. Both editions keep the same application ID, version and signing configuration so they can be installed over one another while preserving receiver and player settings.

| Edition | Generated constant | Release command | APK output directory |
| --- | --- | --- | --- |
| Aurora | `BuildConfig.AURORA_ENABLED = true` | `./gradlew :app:assembleAuroraRelease` | `app/build/outputs/apk/aurora/release/` |
| Standard | `BuildConfig.AURORA_ENABLED = false` | `./gradlew :app:assembleStandardRelease` | `app/build/outputs/apk/standard/release/` |

Build both with `./gradlew :app:assembleAuroraRelease :app:assembleStandardRelease`. Debug and sanitizer builds use the corresponding `AuroraDebug`, `StandardDebug`, `AuroraSanitize` and `StandardSanitize` task suffixes. Release bundles use `:app:bundleAuroraRelease` and `:app:bundleStandardRelease`.

Aurora rendering, preview generation, settings, GLSL and material assets live in `app/src/aurora/`. Standard compiles only a small no-op feature bridge from `app/src/standard/`; it uses the original cover-gradient theme and exposes no Aurora or simulated-playback controls. Previously saved Aurora preferences cannot enable that feature in Standard. Record rotation, spectrum, lyrics, controls, audio and networking remain shared. The Aurora edition keeps the existing theme toggle, live/pre-rendered modes, scale and other preferences.

This is stronger than a runtime switch: the Standard source set never includes the renderer or the six pre-rendered material files, including debug builds. The material payload alone occupies 202,816,977 bytes (about 193.42 MiB), so actual APK sizes are reported by CI rather than assumed from the asset size.

The unsigned-release CI builds both editions, verifies all six Aurora assets and their uncompressed/seekable storage, checks Standard for leaked assets/shaders/renderer classes, and reports both package sizes in the job summary. Download `airplay-aurora-release` and `airplay-standard-release` artifacts. The regular signed-release workflow publishes both APKs and bundles; the debug workflow also uploads two separately named artifacts.

Media3 supplies shared GLSL shaders for video processing. These are expected in both editions. The package auditor identifies Aurora shaders by filename and source-content hashes, including renamed copies, instead of rejecting every `.glsl` resource.
