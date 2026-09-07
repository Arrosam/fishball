# FishBall

## Releasing

A release is not finished when the GitHub release exists. The app and the site both read the
same manifest, and a release that stops at GitHub is invisible to every install in the world.

Every release, in order:

1. Bump `versionCode` **and** `versionName` in [app/build.gradle.kts](app/build.gradle.kts).
   `versionCode` is the only thing the update check compares — the name is for the person
   reading the modal.
2. Build the signed release APK (`:app:assembleRelease`). It is signed from
   `keystore.properties` + `fishball-release.jks`, neither of which is in version control.
3. **Show the user the release notes and wait for their explicit yes.** They go out publicly,
   in Chinese, under the user's name. "Ship it as release X" authorises the version and the
   build, not the wording.
4. Create the GitHub release on tag `v<versionName>`, attaching the APK under **two** names:
   `fishball-<versionName>.apk` so a downloaded file says which build it is, and
   `fishball.apk` so `releases/latest/download/fishball.apk` keeps resolving for anyone
   holding that link.
5. **Update the download link on areel.org** — the repo is `Arrosam/areel.org`, cloned at
   `../areel.org`, and the file is `fishball/latest.json`: `versionCode`, `versionName`,
   `size`, `url` (pointing at the version-named asset) and `notes`. The site's download
   buttons and the app's 检查更新 both follow this file. Update the hardcoded fallback version
   in `fishball/en/index.html` and `fishball/zh/index.html` at the same time; it is only shown
   when the fetch fails, which is exactly when being right matters.
6. Verify the manifest is actually live — GitHub Pages takes a few minutes, and until it has
   built, the old version is still what everybody is served.

Step 5 is the one that gets forgotten, and forgetting it is silent: the release looks complete
from GitHub while every phone still sees the previous version.

## The two builds

The user's phone runs the **debug-signed** APK; releases are **release-signed**. The two
signatures do not match, so a release APK cannot install over the phone's build — including
through the app's own updater, which will reach the installer and be refused. Syncing to the
phone means `:app:assembleDebug`. Never `adb uninstall` to get around it without saying so
first: it deletes `files/memory.json`, `files/turns.jsonl` and `files/pictures/`, which is the
user's real conversation history and the only copy of it.

## Building

`JAVA_HOME` must point at a JDK 21 — `C:\Users\Samuel\toolchain\jdk-21.0.12.1+1` on this
machine. The Bash tool inherits a JDK 8 and will fail; run Gradle through PowerShell.

## Commits

One coherent change per commit, committed as it lands, so any one of them can be reverted
alone.
