# Toolchain

Installed 2026-08-31. Before this, nothing in `app/` had ever been compiled — six defects had
accumulated by inspection alone, and one design round had to be reverted because of them. The
first real build found three more in under three minutes.

## What is installed, and where

| | Version | Location |
|---|---|---|
| JDK | Temurin 21.0.12.1 | `C:\Users\Samuel\toolchain\jdk-21.0.12.1+1` |
| Gradle | 8.11.1 | `C:\Users\Samuel\toolchain\gradle-8.11.1` |
| Android SDK | platform 35, build-tools 35.0.0, platform-tools | `%LOCALAPPDATA%\Android\Sdk` |

Nothing was installed system-wide and no existing Java was touched. The machine already had
JDK 8, 11 and 25, none usable: AGP 8.7 needs 17–21, and Android Studio's bundled runtime is
JDK 11. The JDK 21 above is an extracted zip, not an installer.

`local.properties` points AGP at the SDK and is gitignored, so it stays machine-local.

## Building

```bash
./gradlew :core:test          # the spec suites
./gradlew :app:assembleDebug  # APK -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease
```

If `JAVA_HOME` is not already the JDK 21 above, set it for the shell first — the system
default is JDK 25, which Gradle 8.11 will refuse.

## Three build-config bugs this immediately exposed

Worth recording, because all three were invisible to review and none were in the app's own
logic.

**1. Plugin classpath collision.** `:core` applied `org.jetbrains.kotlin.jvm` with a version
while the root had already put `kotlin-android` on the classpath. Both ship from the same
artifact, so Gradle refused: *"already on the classpath with an unknown version"*. Fixed by
declaring every plugin version once in the root with `apply false`, and applying without a
version in subprojects.

**2. `jvmToolchain(17)` on a machine with no JDK 17.** A toolchain request is a hard
requirement, not a preference — the build fails rather than falling back. `:core` now targets
Java 17 *bytecode* from whichever JDK runs, matching how `:app` was already configured.

**3. `?attr/colorControlNormal` in the four VectorDrawables.** That attribute comes from
AppCompat/Material *Views* themes; this app uses a bare framework theme and Compose, so
resource linking failed. The drawables now carry a literal stroke colour and no
drawable-level `android:tint` at all — colour belongs to the `Icon(tint = …)` call site, and a
tint baked into the asset would have fought it.

## What is verified now, and what still isn't

**Verified:** `:core` main and test sources compile and all specs pass under JUnit; `:app`
compiles; a debug APK links, packages and dexes; the release build survives R8 with the
ProGuard rules in `app/proguard-rules.pro`.

**Not verified:** anything at runtime. Compiling is not running — no screen has been rendered
on a device or emulator, no glass surface has been seen, and `SearxngGateway` has never issued
a request from inside the app. An emulator image (`system-images;android-35;...`) is the next
thing to install if that matters.
