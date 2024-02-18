import com.drew.imaging.ImageMetadataReader
import com.drew.lang.GeoLocation
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.GpsDirectory

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryWatcher

import javafx.application.Platform
import javafx.event.Event
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.OverrunStyle
import javafx.scene.control.TextInputDialog
import javafx.scene.image.Image
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.TransferMode
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.util.Duration

import mu.KLogger
import mu.KotlinLogging

import org.controlsfx.control.Notifications

import tornadofx.*

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import javax.imageio.ImageIO

import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis


private lateinit var logger: KLogger

enum class QuickOperation { COPY, MOVE, GO }

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
                mv.il.currentImage.readInfos()
                field("Name") {
                    textarea(mv.il.currentImage.path) {
                        maxWidth = 400.0
                        prefHeight = 75.0
                        isWrapText = true
                        isEditable = false
                    }
                }
                field("Size") {
                    label(mv.il.currentImage.size.toString())
                }
                field("Tags") {
                    textarea {
                        prefHeight = 150.0
                        text = mv.il.currentImage.tags
                        isEditable = false
                    }
                }
                mv.il.currentImage.geo?.also { geo ->
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
                tooltip("Reveal folder")
                onLeftClick {
                    Helpers.openFile(File(file))
                }
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
                onLeftClick {
                    WImageViewer.showNotification("This does nothing, type the number!")
                }
            }
        }
    }
}

// this holds the dir and also updates UI.
class MyImageList(private val mv: MainView) {
    private val currentImages: ConcurrentSkipListSet<MyImage> = ConcurrentSkipListSet<MyImage>()
    var currentImage: MyImage = MyImage(null)
    private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp")
    private var dw: DirectoryWatcher? = null

    private fun removeCurrent() {
        logger.debug("remove current")
        val oldidx = currentImages.indexOf(currentImage)
        currentImages.remove(currentImage)
        when {
            oldidx > 0 && oldidx == currentImages.size -> { // was last
                currentImage = currentImages.elementAt(oldidx - 1) // is prev
                showCurrentImage()
            }
            oldidx > -1 && oldidx <= currentImages.size - 1 -> {
                currentImage = currentImages.elementAt(oldidx) // is next
                showCurrentImage()
            }
            else -> showFirst()
        }
    }

    fun showFirst(showLast: Boolean = false) {
        logger.debug("show first")
        if (currentImages.size > 0) {
            currentImage = currentImages.elementAt(if (showLast) currentImages.size - 1 else 0)
            showCurrentImage()
            WImageViewer.showNotification("Showing ${if (showLast) "last" else "first"} image!")
        } else {
            WImageViewer.showNotification("No images!")
        }
    }

    fun showNext() { // if at last file, show the one before the current file
        logger.debug("show next")
        val oldidx = currentImages.indexOf(currentImage)
        when {
            oldidx == -1 -> showFirst()
            oldidx < currentImages.size - 1 -> {
                currentImage = currentImages.elementAt(oldidx + 1)
                showCurrentImage()
            }
            else -> WImageViewer.showNotification("No next image!")
        }
    }

    fun showPrev() {
        logger.debug("show prev")
        val oldidx = currentImages.indexOf(currentImage)
        when {
            oldidx == -1 -> showFirst()
            oldidx > 0 -> {
                currentImage = currentImages.elementAt(oldidx - 1)
                showCurrentImage()
            }
            else -> WImageViewer.showNotification("No previous image!")
        }
    }

    // this updates also all gui properties.
    private fun showCurrentImage() {
        logger.info("showCurrentImage: $currentImage")
        mv.guiCurrentPath.set(currentImage.toString())
        mv.guiCurrentIdx.set(currentImages.indexOf(currentImage))
        mv.iv.image = null
        mv.iv.image = currentImage.getImage()
        if (mv.iv.image.progress != 1.0) { // image not yet fully loaded, have to wait before updateImageSize()
            logger.info("showCurrentImage not yet loaded (${mv.iv.image.progress}), waiting...")
            mv.iv.image.progressProperty().addListener { _, _, newValue ->
                logger.info("showCurrentImage progress $newValue")
                if (newValue == 1.0) mv.updateImage()
            }
        } else mv.updateImage()
    }

    // watches one directory, stops watching the first one before.
    private fun watchdir(folder: File) {
        dw?.also {
            logger.info("dirwatcher: stopping old watcher $it")
            it.close()
        }
        logger.info("dirwatcher: watching $folder")
        try {
            dw = DirectoryWatcher.builder().path(folder.toPath()).listener { dce ->
                logger.info("dirwatcher($folder) event: $dce ${dce.path()}")
                // WImageViewer.showNotification("dirwatcher: ${dce.eventType()} ${dce.path()}")
                WImageViewer.showNotification("Folder changed!")
                when (dce.eventType()) {
                    DirectoryChangeEvent.EventType.CREATE -> runLater {
                        updateFiles(folder, currentImage.file)
                    }
                    DirectoryChangeEvent.EventType.MODIFY -> runLater {
                        updateFiles(folder, currentImage.file)
                    }
                    DirectoryChangeEvent.EventType.DELETE -> runLater {
                        if (dce.path().toFile().path == currentImage.file?.path)
                            removeCurrent()
                        else
                            updateFiles(folder, currentImage.file)
                    }
                    DirectoryChangeEvent.EventType.OVERFLOW -> {
                        logger.error("dirwatcher overflow!")
                        updateFiles(folder, currentImage.file)
                    }
                    else -> {
                    }
                }
            }.fileHashing(false).build()
        } catch (e: Exception) {
            logger.error("Exception while starting directory watcher: ")
            e.printStackTrace()
            error("Error watching folder!", "Try to drag'n'drop folder\n$folder\n(this might be due to sandboxing)")
        }
        dw?.watchAsync()
    }

    private fun updateFiles(folder: File, setCurrent: File? = null) {
        logger.info("updateFiles $folder current=$setCurrent")
        currentImage = MyImage(null)
        folder.listFiles()?.filter { f -> f.isDirectory || imageExtensions.any { f.name.lowercase(Locale.getDefault()).endsWith(it) }
        }?.sorted()?.also {
            logger.debug("clearing cache...")
            currentImages.forEach { mi -> mi.privateImage = null } // cleanup cache
            currentImages.clear()
            logger.debug("adding ${it.size} files...")
            it.forEach { f ->
                val img = MyImage(f)
                if (f.absolutePath == setCurrent?.absolutePath) currentImage = img
                currentImages.add(img)
            }
        }
        mv.guiImageCount.set(currentImages.size)
        WImageViewer.showNotification("Loaded files in $folder")
        watchdir(folder)
    }

    // loads folder with files, if folder==file, show it, else possibly revealFile
    fun setFolder(folder: File, revealFile: File? = null) {
        logger.info("setfolder: $folder , $revealFile")
        if (folder.isDirectory) {
            updateFiles(folder, revealFile)
        } else {
            updateFiles(folder.parentFile, folder)
        }
        if (currentImage.file == null) showFirst() else showCurrentImage()
    }

    // caching. works totally independent of rest, except clear on folder change!
    private val cacheBusy = AtomicBoolean(false)
    private val cacheidx = AtomicInteger(-1) // points to next image to be cached
    private val cacheN = 3 // cache that many images forth and back
    private fun doCache(incdec: Boolean) {
        cacheBusy.set(true)
        val i = currentImages.elementAt(cacheidx.get())
        if (i.privateImage == null) {
            logger.debug("doCache cacheidx=$cacheidx")
            i.loadImage(true) // loadinbg keeps UI responsive.
            while (i.privateImage?.progress != 1.0) Thread.sleep(10) // wait until picture is loaded before caching next or so.
            cacheidx.getAndAdd(if (incdec) +1 else -1)
            cacheBusy.set(false)
        } else {
            cacheidx.getAndAdd(if (incdec) +1 else -1)
            cacheBusy.set(false)
        }
    }
    init {
        logger.info("initialize cachehelper...")
        thread(name = "cachehelper") {
            var lastcurrentidx = -1
            var needcaching = false
            while (true) {
                if (!cacheBusy.get()) {
                    val curridx = currentImages.indexOf(currentImage)
                    if (curridx != lastcurrentidx) {
                        logger.debug("cacheing: lastidx=$lastcurrentidx curridx=$curridx")
                        lastcurrentidx = curridx
                        cacheidx.set(curridx + 1)
                        needcaching = true
                    }
                    if (needcaching) { // do stuff: first forward, then backward.
                        if (cacheidx.get() < curridx) { // was going back
                            if (cacheidx.get() < 0 || cacheidx.get() < curridx - cacheN) { // at end backwards
                                logger.debug("caching: finished, cleanup...")
                                currentImages.forEachIndexed { index, myImage -> if (myImage!!.privateImage != null && abs(curridx-index) > cacheN) {
                                    logger.debug("caching: curridx=$curridx remove from cache idx: $index")
                                    myImage.privateImage = null
                                } }
                                needcaching = false
                            } else { // cache and go back
                                doCache(false)
                            }
                        } else { // was going forward
                            if (cacheidx.get() >= currentImages.size || cacheidx.get() > curridx + cacheN) { // at end forwards
                                cacheidx.set(curridx - 1)
                            } else { // cache and go forward
                                doCache(true)
                            }
                        }
                    }
                }
                Thread.sleep(10)
            }
        }
    }
}

class MyImage(val file: File?) : Comparable<MyImage> {
    var tags: String = ""
    var exifOrientation: Int? = null
    var geo: GeoLocation? = null
    val path: String get() = file?.absolutePath?:"no file"
    val size: Long get() = file?.length()?:0
    val exists: Boolean get() = file?.exists() == true
    var privateImage: Image? = null
    fun getImage(): Image {
        assert(Platform.isFxApplicationThread())
        if (privateImage == null) {
            logger.debug("myimage.getImage not cached, loadImage(false)! $this")
            loadImage(false)
        }
        return privateImage!!
    }

    fun loadImage(loadinbg: Boolean) {
        assert(Platform.isFxApplicationThread() || loadinbg)
        privateImage = if (file == null) {
            Helpers.textToImage("no image")
        } else if (!file.exists()) {
            Helpers.textToImage("file doesn't exist")
        } else if (file.isDirectory) {
            Helpers.textToImage(file.absolutePath)
        } else {
            logger.debug("myimage: loading ${file.toURI().toURL().toExternalForm()}")
            Image(file.toURI().toURL().toExternalForm(), loadinbg)
        }
        readInfos() // to get rotation angle, could do better to avoid doing it twice (infos)
    }

    fun readInfos() {
        val timeInMillis = measureTimeMillis {
            if (file != null) {
                val metadata = ImageMetadataReader.readMetadata(file)
                tags = ""
                tags += "$metadata\nTags:\n"
                metadata.directories.forEach { directory ->
                    directory.tags.forEach { tags += it.toString() + "\n" }
                }
                val gpsDirectory: GpsDirectory? = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
                geo = gpsDirectory?.geoLocation
                geo?.also { tags += "\ngeo: ${it.toDMSString()}" }
                val exifDirectoryasdf: ExifIFD0Directory? =
                    metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                if (exifDirectoryasdf?.containsTag(ExifIFD0Directory.TAG_ORIENTATION) == true) {
                    exifOrientation = exifDirectoryasdf.getInt(ExifIFD0Directory.TAG_ORIENTATION)
                }
                // flips are ignored... https://stackoverflow.com/questions/5905868/how-to-rotate-jpeg-images-based-on-the-orientation-metadata
            } else {
                geo = null
                tags = ""
                exifOrientation = null
            }
        }
        logger.debug("readinfos took $timeInMillis ms, file:${file?.path}")
    }

    override fun toString(): String = path
    override fun compareTo(other: MyImage) = if (file == null || other.file == null) -1 else this.file.compareTo(other.file)
}

// https://stackoverflow.com/a/53308535
class RotatablePane(private val child: Pane) : Region() {
    override fun layoutChildren() {
        if (child.rotate % 180.0 == 0.0) {
            child.resize(width, height)
            child.relocate(0.0, 0.0)
        } else {
            child.resize(height, width)
            val delta = (width - height) / 2
            child.relocate(delta, -delta)
        }
    }

    init {
        id = "RotatablePaneLayouter"
        children.add(child)
    }
}

class MainView : UIComponent("WImageViewer") {
    val il = MyImageList(this)
    private val helperWindows = HelperWindows(this)
    private var currentZoom: SDP = SDP(1.0)
    val guiCurrentPath: SSP = SSP("")
    val guiCurrentIdx: SIP = SIP(0)
    val guiImageCount: SIP = SIP(0)
    private var pQuickFolders: HBox = hbox {}
    private var angle = 0

    val iv = imageview {
        isPreserveRatio = true
    }

    private val stackPaneImage: StackPane = stackpane { // stackpane in order to center content if smaller than window
        id = "stackPaneImage"
        children += iv
        background = Background(BackgroundFill(Color.BLACK, CornerRadii.EMPTY, Insets.EMPTY))
    }

    private val rotablePane = RotatablePane(stackPaneImage)

    private val scrollPane = scrollpane {
        id = "scrollPane"
        content = rotablePane
        style = "-fx-background: #000000;" // only way
        styleClass += "edge-to-edge" // remove thin white border
        addEventFilter(KeyEvent.ANY) { // disable cursor keys etc.
            Event.fireEvent(FX.primaryStage.scene, it.copyFor(it.source, FX.primaryStage.scene))
            it.consume()
        }
        isFitToWidth = true
        isFitToHeight = true
    }

    private val statusBar = borderpane {
        bottom = hbox {
            background = Background(BackgroundFill(Color.GRAY, CornerRadii.EMPTY, Insets.EMPTY))
            prefHeight = 25.0
            alignment = Pos.CENTER
            label(guiCurrentPath).style { fontSize = 18.px }
            spacer {
                minWidth = 5.0
            }
            label(currentZoom.asString("Zoom: %.1f")).style {
                fontSize = 18.px
                setMinWidth(Region.USE_PREF_SIZE)
            }
            spacer {
                minWidth = 5.0
                maxWidth = 5.0
            }
            label(guiCurrentIdx.asString().concat("/").concat(guiImageCount.asString())).style {
                fontSize = 18.px
                setMinWidth(Region.USE_PREF_SIZE)
            }
        }
        isMouseTransparent = true
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

    override val root: StackPane = stackpane {
        id = "MainView.root"
        children += scrollPane
        children += statusBar
    }

    fun updateImage(z: Zoom? = null, relangle: Int = 0) {
        logger.debug("updateImageSize zoom=$z relangle=$relangle")
        when(z) {
            Zoom.IN -> currentZoom.value += 0.5
            Zoom.OUT -> currentZoom.value -= 0.5
            Zoom.FIT -> currentZoom.value = 1.0
            null -> {}
        }
        if (currentZoom.value <= 1.0) currentZoom.set(1.0)

        if (relangle == 0) { // set initial image rotation from exif tag.
            angle = when(il.currentImage.exifOrientation) {
                3,4 -> 180 // ignores flips currently https://stackoverflow.com/questions/5905868/how-to-rotate-jpeg-images-based-on-the-orientation-metadata
                5,6 -> 90
                7,8 -> -90
                else -> 0
            }
        }
        angle += relangle % 360
        stackPaneImage.rotate = angle.toDouble()

        if (iv.image != null) { // this is most simple way due to bugs in rotated ImageView
            val availw = root.width * currentZoom.value
            val availh = root.height * currentZoom.value

            val imgw = if (angle % 180 == 0) iv.image.width else iv.image.height
            val imgh = if (angle % 180 != 0) iv.image.width else iv.image.height

            val factw = availw / imgw
            val facth = availh / imgh
            val factres = min(factw, facth)
            val resw = imgw * factres
            val resh = imgh * factres

            iv.fitWidth = if (angle % 180 == 0) resw else resh
            iv.fitHeight = if (angle % 180 != 0) resw else resh

            rotablePane.minWidth = resw // essential to make scrollbars appear
            rotablePane.minHeight = resh
        }
    }

    init {
        root.heightProperty().onChange { updateImage() }
        root.widthProperty().onChange { updateImage() }
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

        stage.scene?.setOnDragOver {
            if (it.dragboard.hasFiles()) {
                it.acceptTransferModes(TransferMode.COPY, TransferMode.MOVE, TransferMode.LINK)
            }
        }
        stage.scene?.setOnDragDropped {
            if (it.dragboard.hasFiles()) {
                it.dragboard.files.firstOrNull()?.also { f ->
                    mv.il.setFolder(f)
                }
            }
        }

        // just change files here, directorywatcher catches changes!
        stage.scene?.setOnKeyTyped { // only this is independent of platform and keyboard layout!
            logger.debug("keytyped: char=${it.character} text=${it.text} code=${it.code}")
            when (it.character) {
                "h" -> showHelp()
                "+" -> mv.updateImage(Zoom.IN)
                "-" -> mv.updateImage(Zoom.OUT)
                "=" -> mv.updateImage(Zoom.FIT)
                "d" -> LayoutDebugger().apply {
                    debuggingScene = FX.primaryStage.scene
                    openModal(modality = Modality.NONE)
                    nodeTree.root.expandAll()
                }
                "f" -> stage.isFullScreen = !stage.isFullScreen
                "i" -> mv.showInfo()
                "b" -> mv.toggleStatusBar()
                "r" -> mv.updateImage(relangle = 90)
                "o" -> {
                    chooseDirectory("Open folder", mv.il.currentImage.file?.parentFile)?.also { f ->
                        mv.il.setFolder(f)
                    }
                }
                "l" -> if (mv.il.currentImage.file?.exists() == true) Helpers.revealFile(mv.il.currentImage.file!!)
                "n" -> {
                    if (mv.il.currentImage.file?.exists() == true) {
                        TextInputDialog(mv.il.currentImage.file!!.name).apply {
                            title = "Rename file"
                            headerText = "Enter new filename:"
                        }.showAndWait().ifPresent { s ->
                            val p = mv.il.currentImage.file!!.toPath()
                            val t = p.resolveSibling(s)
                            logger.info("rename $p to $t")
                            Files.move(p, t)
                            showNotification("Moved\n$p\nto\n$t")
                        }
                    }
                }
            }
        }
        stage.scene?.setOnKeyPressed {
            logger.debug("keypressed: char=${it.character} text=${it.text} code=${it.code}")
            when {
                it.code == KeyCode.UP && (it.isControlDown || it.isMetaDown) -> mv.il.currentImage.file?.parentFile?.parentFile?.also { pf -> mv.il.setFolder(pf, mv.il.currentImage.file?.parentFile) }
                it.code == KeyCode.DOWN && (it.isControlDown || it.isMetaDown) -> if (mv.il.currentImage.file?.isDirectory == true) { mv.il.setFolder(mv.il.currentImage.file!!) }
                it.code == KeyCode.DOWN -> mv.il.showNext()
                it.code == KeyCode.UP -> mv.il.showPrev()
                it.code == KeyCode.SPACE -> if (it.isShiftDown) mv.il.showPrev() else mv.il.showNext()
                it.code == KeyCode.HOME -> mv.il.showFirst()
                it.code == KeyCode.END -> mv.il.showFirst(true)
                it.code == KeyCode.ALT -> mv.showQuickFolders(QuickOperation.COPY)
                it.code == KeyCode.COMMAND -> mv.showQuickFolders(QuickOperation.MOVE)
                it.code == KeyCode.CONTROL -> mv.showQuickFolders(QuickOperation.MOVE)
                it.code == KeyCode.SHIFT -> mv.showQuickFolders(QuickOperation.GO)
                it.code == KeyCode.BACK_SPACE -> {
                    if (mv.il.currentImage.exists) {
                        var doit = it.isMetaDown
                        if (!doit) confirm("Confirm delete current file", mv.il.currentImage.path, owner = FX.primaryStage) { doit = true }
                        if (doit) {
                            val res = Helpers.trashOrDelete(mv.il.currentImage.file!!) + " ${mv.il.currentImage}"
                            showNotification(res)
                        }
                    }
                }
                it.code in KeyCode.DIGIT1..KeyCode.DIGIT6 -> {
                    val keynumber = it.code.ordinal - KeyCode.DIGIT1.ordinal + 1
                    val source = mv.il.currentImage
                    val target = File(Settings.settings.quickFolders[keynumber]!!)
                    if (!target.isDirectory || !target.canWrite()) {
                        showNotification("Target is not a folder or not writable:\n$target")
                        return@setOnKeyPressed
                    }
                    if (!it.isControlDown && !it.isAltDown && !it.isMetaDown && it.isShiftDown) {
                        logger.info("go to $target")
                        mv.il.setFolder(target)
                    }
                    if (source.file?.isFile != true) {
                        showNotification("Current item is not a file")
                        return@setOnKeyPressed
                    }
                    val targetp = Paths.get(target.absolutePath, source.file.name)
                    if (targetp.toFile().exists()) {
                        showNotification("Target exists already:\n$targetp")
                        return@setOnKeyPressed
                    }
                    if (!it.isControlDown && it.isAltDown && !it.isMetaDown && !it.isShiftDown) {
                        logger.info("copy ${source.file.toPath()} to $targetp")
                        Files.copy(source.file.toPath(), targetp, StandardCopyOption.COPY_ATTRIBUTES)
                        showNotification("Copied\n$source\nto\n$targetp")
                    } else if (!it.isAltDown && (it.isControlDown || it.isMetaDown) && !it.isShiftDown) {
                        logger.info("move ${source.file.toPath()} to $targetp")
                        Files.move(source.file.toPath(), targetp)
                        showNotification("Moved\n$source\nto\n$targetp")
                    }
                }
                else -> {
                }
            }
         }
        stage.scene?.setOnKeyReleased {
            mv.hideQuickFolders()
        }

        if (Settings.settings.lastImage != "") mv.il.setFolder(File(Settings.settings.lastImage))
        mv.updateImage(Zoom.FIT)

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
                    |down|space / up|shift+space - next / prev image
                    |home / end - first / last image
                    |r - rotate
                    |[+,-,=] - zoom in,out,fit
                    |n - rename
                    |l - reveal current file in file browser
                    |backspace - trash/delete current image (meta: don't confirm)
                    |o - open Folder...
                    |ctr|meta + up / down - navigate folders
                    |[alt,ctrl|cmd] - Keep pressed to show quickfolders
                    |[alt,ctrl|cmd,shift]+[1-6] - Quickfolder operations copy,move,go
                    |h - show this help
                    |
                    |Drop a folder or file onto the main window to open it!
                """.trimMargin(), owner = FX.primaryStage)
        }
    }
}

fun main(args: Array<String>) {
    val oldOut: PrintStream = System.out
    val oldErr: PrintStream = System.err
    var logps: FileOutputStream? = null
    class MyConsole(val errchan: Boolean): OutputStream() {
        override fun write(b: Int) {
            logps?.write(b)
            (if (errchan) oldErr else oldOut).print(b.toChar().toString())
        }
    }
    System.setOut(PrintStream(MyConsole(false), true))
    System.setErr(PrintStream(MyConsole(true), true))

    System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    System.setProperty(org.slf4j.simple.SimpleLogger.SHOW_DATE_TIME_KEY, "true")
    System.setProperty(org.slf4j.simple.SimpleLogger.DATE_TIME_FORMAT_KEY, "yyyy-MM-dd HH:mm:ss:SSS")
    System.setProperty(org.slf4j.simple.SimpleLogger.LOG_FILE_KEY, "System.out") // and use intellij "grep console" plugin
//    System.setProperty("prism.verbose", "true")

    var dir = System.getProperty("java.io.tmpdir")
    if (Helpers.isLinux() || Helpers.isMac()) if (File("/tmp").isDirectory) dir = "/tmp"
    val logfile = File("$dir/wimageviewerlog.txt")
    logps = FileOutputStream(logfile)

    logger = KotlinLogging.logger {} // after set properties!

    logger.error("error")
    logger.warn("warn")
    logger.info("info jvm ${System.getProperty("java.version")}")
    logger.debug("debug")
    logger.trace("trace")

    logger.info("Log file: $logfile")
    ManagementFactory.getRuntimeMXBean().inputArguments.forEach { logger.debug("jvm arg: $it") }

    logger.info("starting wimageviewer!")

    Settings

    launch<WImageViewer>(args)
}

