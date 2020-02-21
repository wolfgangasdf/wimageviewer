
# Introduction

WIMageViewer is an image/photo viewer for myself, it does this:

* Quick folders: Move/copy current image to one of the quick folders, easily changable.
* Show geolocation in infos
* The help:

                    |f - toggle fullscreen
                    |i - show image information and geolocation
                    |down/up - next/prev
                    |home/end - first/last
                    |r - rotate
                    |n - rename
                    |l - reveal file in file browser
                    |backspace - trash/delete current image (meta: don't confirm)
                    |[alt,cmd]+[1-6] - Quickfolder operations copy/move
                    |left/right - navigate folders
                    |? - show this help
                    |
                    |Drop a folder or file onto the main window to open it!

### How to run ###

* [Download the zip](https://github.com/wolfgangasdf/wimageviewer/releases), extract it somewhere and double-click the app (Mac) or
  `bin/wimageviewer.bat` (Windows) or `bin/wimageviewer` (Linux).

### How to develop, compile & package ###

* Get Java 13 from https://jdk.java.net
* Clone the repository
* I use the free community version of [IntelliJ IDEA](https://www.jetbrains.com/idea/download/)

Packaging:

* Download JDKs for the other platforms (and/or adapt `cPlatforms` in `build.gradle.kts`), extract them and set the environment variables to it:
  * `export JDK_MAC_HOME=...`, `export JDK_WIN_HOME=...`, `export JDK_LINUX_HOME=...`
* Package for all platforms: `./gradlew clean dist`. The resulting files are in `build/crosspackage`

### Used frameworks ###

* [Kotlin](https://kotlinlang.org/) and [Gradle](https://gradle.org/)
* [TornadoFX](https://github.com/edvin/tornadofx)
* [Metadata extractor](https://github.com/drewnoakes/metadata-extractor) to extract image tags and geolocation
* [controlsfx](https://github.com/controlsfx/controlsfx) for notifications
* [leafletjs](https://leafletjs.com/) for maps

### License ###
[MIT](http://opensource.org/licenses/MIT)
