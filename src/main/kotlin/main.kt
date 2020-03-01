import com.drew.imaging.ImageMetadataReader
import com.drew.lang.GeoLocation
import com.drew.metadata.exif.GpsDirectory
import javafx.application.Platform
import javafx.event.Event
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.OverrunStyle
import javafx.scene.control.TextInputDialog
import javafx.scene.image.Image
import javafx.scene.image.WritableImage
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.TransferMode
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.util.Duration
import mu.KLogger
import mu.KotlinLogging
import org.controlsfx.control.Notifications
import tornadofx.*
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.system.exitProcess


private lateinit var logger: KLogger

enum class QuickOperation { COPY, MOVE }

enum class Zoom { IN, OUT, FIT}

class InfoView(private val mv: MainView): View("Information") {
    private fun leafletHTML(geo: GeoLocation) = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                            	<meta charset="utf-8" />
                            	<meta name="viewport" content="width=device-width, initial-scale=1.0">
                                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.6.0/dist/leaflet.css" integrity="sha512-xwE/Az9zrjBIphAcBb3F6JVqxf46+CDLwfLMHloNu6KEQCAWi6HcDUbeOfBIptF7tcCzusKFjFw2yuvEpDL9wQ==" crossorigin=""/>
                                <script src="https://unpkg.com/leaflet@1.6.0/dist/leaflet.js" integrity="sha512-gZwIG9x3wUXg2hdXF6+rVkLF/0Vi9U8D2Ntg4Ga5I5BZpVkVxlJWbSQtXPSiUTtC0TjtGOmxa1AJPuV0CPthew==" crossorigin=""></script>
                            </head>
                            <body>
                            <div id="mapid" style="width: 350px; height: 250px;"></div>
                            <script>
                            	var mymap = L.map('mapid').setView([${geo.latitude}, ${geo.longitude}], 13);
                            	L.tileLayer('https://api.mapbox.com/styles/v1/{id}/tiles/{z}/{x}/{y}?access_token=pk.eyJ1IjoibWFwYm94IiwiYSI6ImNpejY4NXVycTA2emYycXBndHRqcmZ3N3gifQ.rJcFIG214AriISLbB6B5aw', {
                            		maxZoom: 18,
                            		attribution: '<a href="https://www.openstreetmap.org/">OpenStreetMap</a> , ' +
                            			'<a href="https://www.mapbox.com/">Mapbox</a>',
                            		id: 'mapbox/streets-v11',
                            		tileSize: 512,
                            		zoomOffset: -1
                            	}).addTo(mymap);
                            	L.marker([${geo.latitude}, ${geo.longitude}]).addTo(mymap);
                            </script>
                            </body>
                            </html>
                            """.trimIndent()

    override val root = scrollpane {
        form {
            background = Background(BackgroundFill(Color.YELLOW, CornerRadii.EMPTY, Insets.EMPTY))
            fieldset("File info") {
                mv.currentFile!!.readInfos()
                field("Name") {
                    textarea(mv.currentFile!!.file.absolutePath) {
                        maxWidth = 400.0
                        prefHeight = 75.0
                        isWrapText = true
                        isEditable = false
                    }
                }
                field("Size") {
                    label(mv.currentFile!!.file.length().toString())
                }
                field("Tags") {
                    textarea {
                        prefHeight = 150.0
                        text = mv.currentFile!!.tags
                        isEditable = false
                    }
                }
                mv.currentFile!!.geo?.also { geo ->
                    field("Location") {
                        webview {
                            prefWidth = 350.0
                            prefHeight = 270.0
                            runLater {
                                engine.loadContent(leafletHTML(geo))
                            }
                        }
                    }
                    field("Links") {
                        button("Google maps").setOnAction {
                            Helpers.openURL("http://maps.google.com/maps?q=${geo.latitude},${geo.longitude}&z=17")
                        }
                    }
                }
                maxWidth = 500.0
            }
        }
    }
}

class HelperWindows(private val mv: MainView) {

    fun genQuickFolders(operation: QuickOperation) = HBox().apply {
        button(operation.toString()).apply {
            prefWidth = 100.0
            minWidth = prefWidth
            prefHeight = 50.0
        }
        Settings.settings.quickFolders.forEach { (index, file) ->
            spacer {
                minWidth = 3.0
                prefWidth = 10.0
            }
            button(index.toString()).apply {
                prefWidth = 25.0
                prefHeight = 50.0
            }
            vbox {
                maxHeight = 50.0
                button("e").apply {
                    prefWidth = 25.0
                    onLeftClick {
                        chooseDirectory("Choose quick folder")?.also {
                            Settings.settings.quickFolders[index] = it.absolutePath
                            Settings.saveSettings()
                        }
                        mv.hideQuickFolders()
                    }
                }
                button("x").apply {
                    prefWidth = 25.0
                    onLeftClick {
                        Settings.settings.quickFolders[index] = ""
                        mv.hideQuickFolders()
                        Settings.saveSettings()
                    }
                }
            }
            button(file).apply {
                minWidth = 50.0
                maxWidth = 250.0
                prefHeight = 50.0
                isWrapText = true
                textOverrun = OverrunStyle.LEADING_WORD_ELLIPSIS
                tooltip(file)
            }
        }
    }

}

class MyImage(val file: File) : Comparable<MyImage> {
    var tags: String = ""
    var geo: GeoLocation? = null
    val image: Image
        get() {
            return if (!file.exists()) {
                textToImage("file doesn't exist")!!
            } else if (file.isDirectory) {
                textToImage(file.absolutePath)!!
            } else {
                logger.debug("myimage: loading ${file.toURI().toURL().toExternalForm()}")
                Image(file.toURI().toURL().toExternalForm())
            }
        }

    private fun textToImage(text: String): WritableImage? {
        val t = Text(text)
        return t.snapshot(null, null)
    }

    fun readInfos() {
        val metadata = ImageMetadataReader.readMetadata(file)
        tags = ""
        tags += "$metadata\nTags:\n"
        metadata.directories.forEach { directory ->
            directory.tags.forEach { tags += it.toString() + "\n" }
        }
        val gpsDirectory: GpsDirectory? = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
        geo = gpsDirectory?.geoLocation
        geo?.also { tags += "\ngeo: ${it.toDMSString()}" }
    }

    override fun toString(): String = file.absolutePath
    override fun compareTo(other: MyImage) = this.file.compareTo(other.file)
}

class MainView : UIComponent("WImageViewer") {
    private val helperWindows = HelperWindows(this)
    private val imageExtensions = listOf(".jpg", ".jpeg", ".png")
    val currentFiles = java.util.concurrent.ConcurrentSkipListSet<MyImage>()
    var currentFile: MyImage? = null
    private var currentZoom: SDP = SDP(1.0)
    private val guiCurrentPath: SSP = SSP("")
    private var pQuickFolders: HBox = hbox {}

    val iv = imageview {
        isPreserveRatio = true

    }

    private val siv = scrollpane {
        content = iv
        content = stackpane { // to center content if smaller than window
            children += iv
            prefWidthProperty().bind(doubleBinding(this@scrollpane.viewportBoundsProperty()) { value.width })
            prefHeightProperty().bind(doubleBinding(this@scrollpane.viewportBoundsProperty()) { value.height })
            background = Background(BackgroundFill(Color.BLACK, CornerRadii.EMPTY, Insets.EMPTY))
        }
        style = "-fx-background: #000000;" // only way
        styleClass += "edge-to-edge" // remove thin white border
        addEventFilter(KeyEvent.ANY) { // disable cursor keys etc.
            Event.fireEvent(FX.primaryStage.scene, it.copyFor(it.source, FX.primaryStage.scene))
            it.consume()
        }
    }

    private val statusBar = borderpane {
        bottom = hbox {
            background = Background(BackgroundFill(Color.GRAY, CornerRadii.EMPTY, Insets.EMPTY))
            prefHeight = 25.0
            alignment = Pos.CENTER
            label(guiCurrentPath).style { fontSize = 18.px }
            spacer {
                minWidth = 3.0
                prefWidth = 10.0
            }
            label(currentZoom.asString("Zoom: %.1f")).style { fontSize = 18.px }
        }
        isMouseTransparent = true
    }

    fun showFirst(showLast: Boolean = false) {
        logger.debug("show first")
        if (currentFiles.size > 0) {
            showImage(currentFiles.elementAt(if (showLast) currentFiles.size - 1 else 0))
            WImageViewer.showNotification("Showing ${if (showLast) "last" else "first"} image!")
        } else {
            WImageViewer.showNotification("No images!")
        }
    }

    fun showNext() { // if at last file, show the one before the current file
        logger.debug("show next")
        val oldidx = currentFiles.indexOf(currentFile)
        when {
            oldidx == -1 -> showFirst()
            oldidx < currentFiles.size - 1 -> showImage(currentFiles.elementAt(oldidx + 1))
            else -> WImageViewer.showNotification("No next image!")
        }
    }

    fun showPrev() {
        logger.debug("show prev")
        val oldidx = currentFiles.indexOf(currentFile)
        when {
            oldidx == -1 -> showFirst()
            oldidx > 0 -> showImage(currentFiles.elementAt(oldidx - 1))
            else -> WImageViewer.showNotification("No previous image!")
        }
    }

    fun showInfo() {
        InfoView(this).openWindow()
    }

    fun showQuickFolders(quickOperation: QuickOperation) {
        if (root.children.contains(pQuickFolders))
            root.children.remove(pQuickFolders)
        pQuickFolders = helperWindows.genQuickFolders(quickOperation)
        root.add(pQuickFolders)
    }

    fun hideQuickFolders() {
        root.children.remove(pQuickFolders)
    }

    fun toggleStatusBar() {
        if (root.children.contains(statusBar))
            root.children.remove(statusBar)
        else {
            root.add(statusBar)
        }
    }

    // this updates also all gui properties.
    private fun showImage(img: MyImage?) {
        currentFile = img
        if (img == null) {
            currentFile = null
            guiCurrentPath.set("<no file>")
            return
        }
        guiCurrentPath.set(img.toString())
        iv.image = null
        iv.image = img.image
    }

    private fun updateFiles(folder: File) {
        folder.listFiles()?.filter { f -> f.isDirectory || imageExtensions.any { f.name.endsWith(it) } }?.sorted()?.also {
            logger.debug("adding ${it.joinToString(", ")}")
            currentFiles.clear()
            it.forEach { f -> currentFiles.add(MyImage(f)) }
        }
        WImageViewer.showNotification("Loaded files in $folder")
    }

    fun setFolderFile(f: File) {
        if (f.isDirectory) {
            updateFiles(f)
            showImage(currentFiles.firstOrNull())
        } else {
            updateFiles(f.parentFile)
            showImage(currentFiles.find { it.file == f } ?: currentFiles.firstOrNull())
        }
    }

    override val root = stackpane {
        children += siv
    }

    fun zoom(z: Zoom) {
        iv.fitWidthProperty().unbind()
        iv.fitHeightProperty().unbind()
        when(z) {
            Zoom.IN -> currentZoom.value += 0.5
            Zoom.OUT -> currentZoom.value -= 0.5
            Zoom.FIT -> currentZoom.value = 1.0
        }
        if (currentZoom.value <= 1.0) currentZoom.set(1.0)
        iv.fitWidthProperty().bind(root.widthProperty() * currentZoom)
        iv.fitHeightProperty().bind(root.heightProperty() * currentZoom)
    }

    init {
        zoom(Zoom.FIT)
        siv.prefWidthProperty().bind(root.widthProperty())
        siv.prefHeightProperty().bind(root.heightProperty())
    }
}

class WImageViewer : App() {

    override fun start(stage: Stage) {
        FX.setPrimaryStage(stage = stage)
        mv = MainView()

        if (Helpers.isMac()) {
            java.awt.Taskbar.getTaskbar().iconImage = ImageIO.read(this::class.java.getResource("/icons/icon_256x256.png"))
        }

        stage.scene = createPrimaryScene(mv)
        stage.setOnShown {
            stage.title = "WImageViewer"
        }
        stage.fullScreenExitHint = ""
        stage.fullScreenProperty().onChange {
            logger.debug("fs change: $it")
        }
        stage.setOnCloseRequest {
            Settings.saveSettings()
            exitProcess(0)
        }
        stage.width = Settings.settings.width
        stage.height = Settings.settings.height
        stage.show()
        Platform.setImplicitExit(true)

        stage.scene?.setOnKeyReleased {
            mv.hideQuickFolders()
        }

        stage.scene?.setOnDragOver {
            if (it.dragboard.hasFiles()) {
                it.acceptTransferModes(TransferMode.COPY, TransferMode.MOVE, TransferMode.LINK)
            }
        }
        stage.scene?.setOnDragDropped {
            if (it.dragboard.hasFiles()) {
                it.dragboard.files.firstOrNull()?.also { f ->
                    mv.setFolderFile(f)
                }
            }
        }

        stage.scene?.setOnKeyPressed {
            when (it.text) {
                "?" -> showHelp()
                "+" -> mv.zoom(Zoom.IN)
                "-" -> mv.zoom(Zoom.OUT)
                "=" -> mv.zoom(Zoom.FIT)
                "d" -> LayoutDebugger.debug(FX.primaryStage.scene)
                else -> when (it.code) {
                    KeyCode.F -> stage.isFullScreen = !stage.isFullScreen
                    KeyCode.DOWN, KeyCode.SPACE -> mv.showNext()
                    KeyCode.UP -> mv.showPrev()
                    KeyCode.HOME -> mv.showFirst()
                    KeyCode.END -> mv.showFirst(true)
                    KeyCode.LEFT -> mv.currentFile?.file?.parentFile?.parentFile?.also { pf -> mv.setFolderFile(pf) }
                    KeyCode.RIGHT -> if (mv.currentFile?.file?.isDirectory == true) { mv.setFolderFile(mv.currentFile!!.file) }
                    KeyCode.I -> mv.showInfo()
                    KeyCode.B -> mv.toggleStatusBar()
                    KeyCode.R -> mv.iv.rotate = mv.iv.rotate + 90
                    KeyCode.O -> {
                        chooseDirectory("Open folder", mv.currentFile?.file?.parentFile)?.also { f ->
                            mv.setFolderFile(f)
                        }
                    }
                    KeyCode.ALT -> mv.showQuickFolders(QuickOperation.COPY)
                    KeyCode.COMMAND -> mv.showQuickFolders(QuickOperation.MOVE)
                    KeyCode.CONTROL -> mv.showQuickFolders(QuickOperation.MOVE)
                    KeyCode.L -> if (mv.currentFile?.file?.exists() == true) Helpers.revealFile(mv.currentFile!!.file)
                    KeyCode.N -> {
                        if (mv.currentFile?.file?.exists() == true) {
                            TextInputDialog(mv.currentFile!!.file.name).showAndWait().ifPresent { s ->
                                val p = mv.currentFile!!.file.toPath()
                                val t = p.resolveSibling(s)
                                logger.info("rename $p to $t")
                                Files.move(p, t)
                                mv.setFolderFile(t.toFile())
                                showNotification("Moved\n$p\nto\n$t")
                            }
                        }
                    }
                    KeyCode.BACK_SPACE -> {
                        mv.currentFile?.also { source ->
                            var doit = it.isMetaDown
                            if (!doit) confirm("Confirm delete current file", source.file.absolutePath) { doit = true }
                            if (doit) {
                                val res = Helpers.trashOrDelete(source.file)
                                mv.showNext()
                                mv.currentFiles.remove(source)
                                showNotification("$res \n$source")
                            }
                        }
                    }
                    in KeyCode.DIGIT1..KeyCode.DIGIT6 -> {
                        val keynumber = it.code.ordinal - KeyCode.DIGIT1.ordinal + 1
                        if (mv.currentFile == null) return@setOnKeyPressed
                        val source = mv.currentFile!!
                        if (!source.file.isFile) {
                            showNotification("Current item is not a file")
                            return@setOnKeyPressed
                        }
                        val target = File(Settings.settings.quickFolders[keynumber]!!)
                        if (!target.isDirectory || !target.canWrite()) {
                            showNotification("Target is not a folder or not writable:\n$target")
                            return@setOnKeyPressed
                        }
                        val targetp = Paths.get(target.absolutePath, source.file.name)
                        if (targetp.toFile().exists()) {
                            showNotification("Target exists already:\n$targetp")
                            return@setOnKeyPressed
                        }
                        if (!it.isControlDown && it.isAltDown && !it.isMetaDown) {
                            logger.info("copy ${source.file.toPath()} to $targetp")
                            Files.copy(source.file.toPath(), targetp, StandardCopyOption.COPY_ATTRIBUTES)
                            showNotification("Copied\n$source\nto\n$targetp")
                        } else if (!it.isAltDown && (it.isControlDown || it.isMetaDown)) {
                            logger.info("move ${source.file.toPath()} to $targetp")
                            Files.move(source.file.toPath(), targetp)
                            mv.showNext()
                            mv.currentFiles.remove(source)
                            showNotification("Moved\n$source\nto\n$targetp")
                        }
                    }
                    else -> {
                    }
                }
            }
         }

        if (Settings.settings.lastImage != "") mv.setFolderFile(File(Settings.settings.lastImage))

    } // start

    companion object {
        lateinit var mv: MainView
        private var notificationTimeoutMs: Long = 0
        private var notificationLastMsg: String = ""
        fun showNotification(text: String, title: String = "") {
            runLater {
                val msg = text + title
                if (System.currentTimeMillis() > notificationTimeoutMs || notificationLastMsg != msg) { // avoid spam
                    notificationLastMsg = msg
                    notificationTimeoutMs = System.currentTimeMillis() + 2000
                    Notifications.create().owner(FX.primaryStage).hideAfter(Duration(2000.0)).title(title).text(text).show()
                }
            }
        }

        fun showHelp() {
            information("Help", """
                    |f - toggle fullscreen
                    |i - show image information and geolocation
                    |down/up - next/prev
                    |home/end - first/last
                    |r - rotate
                    |[+,-,=] - zoom in,out,fit
                    |n - rename
                    |l - reveal file in file browser
                    |backspace - trash/delete current image (meta: don't confirm)
                    |[alt,cmd]+[1-6] - Quickfolder operations copy/move
                    |left/right - navigate folders
                    |o - open Folder
                    |? - show this help
                    |
                    |Drop a folder or file onto the main window to open it!
                """.trimMargin(), owner = FX.primaryStage)
        }
    }
}

fun main(args: Array<String>) {
    System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    System.setProperty(org.slf4j.simple.SimpleLogger.SHOW_DATE_TIME_KEY, "true")
    System.setProperty(org.slf4j.simple.SimpleLogger.DATE_TIME_FORMAT_KEY, "yyyy-MM-dd HH:mm:ss:SSS")
    System.setProperty(org.slf4j.simple.SimpleLogger.LOG_FILE_KEY, "System.out") // and use intellij "grep console" plugin
//    System.setProperty("prism.verbose", "true")
    logger = KotlinLogging.logger {} // after set properties!

    logger.error("error")
    logger.warn("warn")
    logger.info("info jvm ${System.getProperty("java.version")}")
    logger.debug("debug")
    logger.trace("trace")

    ManagementFactory.getRuntimeMXBean().inputArguments.forEach { logger.debug("jvm arg: $it") }

    logger.info("starting wimageviewer!")

    Settings

    launch<WImageViewer>(args)
}

