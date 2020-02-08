
import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Insets
import javafx.scene.control.OverrunStyle
import javafx.scene.image.Image
import javafx.scene.image.WritableImage
import javafx.scene.input.KeyCode
import javafx.scene.input.TransferMode
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.CornerRadii
import javafx.scene.layout.HBox
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.stage.Stage
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

class MainView: UIComponent("WImageViewer") {
    private val currentFolder = SimpleStringProperty("")
    val currentFiles = mutableListOf<File>()

    private val currentImage = SimpleObjectProperty<Image?>()
    val currentFile = SimpleObjectProperty<File>() // this controls what is shown

    private val iv = imageview(currentImage) {
        isPreserveRatio = true
    }

    private val imageExtensions = listOf(".jpg", ".jpeg", ".png")


    fun showNext() {
        logger.debug("show next")
        val oldidx = currentFiles.indexOf(currentFile.get())
        if (oldidx < currentFiles.size - 1) currentFile.set(currentFiles[oldidx + 1])
        else WImageViewer.showNotification("No next image!")
    }

    fun showPrev() {
        logger.debug("show prev")
        val oldidx = currentFiles.indexOf(currentFile.get())
        if (oldidx > 0 ) currentFile.set(currentFiles[oldidx - 1])
        else WImageViewer.showNotification("No previous image!")
    }

    private var pInfos: Form = form {}

    private fun genInfos() = form {
        background = Background(BackgroundFill(Color.YELLOW, CornerRadii.EMPTY, Insets.EMPTY))
        fieldset("File info") {
            field("Name") {
                textarea(currentFile.get().absolutePath) {
                    maxWidth = 400.0
                    prefHeight = 75.0
                    isWrapText = true
                    isEditable = false
                }
            }
            field("Size") {
                label(currentFile.get().length().toString())
            }
        }
        maxWidth = 500.0
    }

    fun showInfos() {
        if (root.children.contains(pInfos))
            root.children.remove(pInfos)
        else {
            pInfos = genInfos()
            root.add(pInfos)
        }
    }

    enum class QuickOperation {
        COPY, MOVE, LINK
    }

    private var pQuickFolders: HBox = hbox {}
    private fun genQuickFolders(operation: QuickOperation) = hbox {
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
                        }
                        hideQuickFolders()
                    }
                }
                button("x").apply {
                    prefWidth = 25.0
                    onLeftClick {
                        Settings.settings.quickFolders[index] = ""
                        hideQuickFolders()
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

    fun showQuickFolders(quickOperation: QuickOperation) {
        if (root.children.contains(pQuickFolders))
            root.children.remove(pQuickFolders)
        pQuickFolders = genQuickFolders(quickOperation)
        root.add(pQuickFolders)
    }
    fun hideQuickFolders() {
        root.children.remove(pQuickFolders)
    }

    private fun updateFiles(folder: File) {
        folder.listFiles()?.filter { f -> f.isDirectory || imageExtensions.any { f.name.endsWith(it) }}?.sorted()?.also {
            logger.debug("adding ${it.joinToString(", ")}")
            currentFiles.clear()
            currentFiles.addAll(it)
        }
        WImageViewer.showNotification("Loaded files in $folder")
    }
    fun setFolderFile(f: File) {
        if (f.isDirectory) {
            currentFolder.set(f.absolutePath)
            updateFiles(f)
            currentFiles.firstOrNull()?.also{ currentFile.set(it) }
        } else {
            currentFolder.set(f.parent)
            updateFiles(f.parentFile)
            currentFile.set(f)
        }
    }

    override val root = stackpane {
        prefWidth = 800.0
        prefHeight = 600.0
        background = Background(BackgroundFill(Color.BLACK, CornerRadii.EMPTY, Insets.EMPTY))
        menubar {
            isUseSystemMenuBar = true
            menu("File") {
                item("Open").setOnAction {
                    chooseDirectory("Open folder")?.also {
                        setFolderFile(it)
                    }
                }
            }
        }
        children += iv
    }

    private fun textToImage(text: String): WritableImage? {
        val t = Text(text)
        return t.snapshot(null, null)
    }

    init {
        currentFile.onChange {
            if (!currentFile.get().exists()) {
                logger.error("currentFile $currentFile doesn't exist.")
                return@onChange
            }
            if (it?.isDirectory == true) {
                currentImage.set(textToImage(it.absolutePath))
            } else {
                logger.debug("loading ${currentFile.get().toURI().toURL().toExternalForm()}")
                currentImage.set(Image(currentFile.get().toURI().toURL().toExternalForm()))
            }
        }
        iv.fitWidthProperty().bind(root.widthProperty())
        iv.fitHeightProperty().bind(root.heightProperty())
    }
}

class WImageViewer : App() {

    override fun start(stage: Stage) {
        mainstage = stage
        if (Helpers.isMac()) {
            java.awt.Taskbar.getTaskbar().iconImage = ImageIO.read(this::class.java.getResource("/icons/icon_256x256.png"))
        }

        val mv = MainView()

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
            println("DE: $it")
            if (it.dragboard.hasFiles()) {
                it.dragboard.files.firstOrNull()?.also { f ->
                    mv.setFolderFile(f)
                }
            }
        }

        stage.scene?.setOnKeyPressed {
            if (it.text == "?") {
                information("Help", """f - toggle fullscreen
                    |down/up - next/prev
                    |[alt,cmd]+[1-6] - Quickfolder operations copy/move
                    |backspace - delete current image
                    |
                    |Drop a folder or file onto this to open it!
                """.trimMargin())
            }
            when(it.code) {
                KeyCode.F -> stage.isFullScreen = !stage.isFullScreen
                KeyCode.DOWN, KeyCode.SPACE -> mv.showNext()
                KeyCode.UP -> mv.showPrev()
                KeyCode.I -> mv.showInfos()
                KeyCode.CONTROL -> mv.showQuickFolders(MainView.QuickOperation.LINK)
                KeyCode.ALT -> mv.showQuickFolders(MainView.QuickOperation.COPY)
                KeyCode.COMMAND -> mv.showQuickFolders(MainView.QuickOperation.MOVE)
                KeyCode.BACK_SPACE -> {
                    val source = mv.currentFile.get()
                    confirm("Confirm delete current file", source.absolutePath) {
                        Files.delete(source.toPath())
                        mv.showNext()
                        mv.currentFiles.remove(source)
                        showNotification("Deleted\n$source")
                    }
                }
                in KeyCode.DIGIT1..KeyCode.DIGIT9 -> {
                    val keynumber = it.code.ordinal - KeyCode.DIGIT1.ordinal + 1
                    val source = mv.currentFile.get()
                    if (!source.isFile) {
                        showNotification("Current item is not a file")
                        return@setOnKeyPressed
                    }
                    val target = File(Settings.settings.quickFolders[keynumber]!!)
                    if (!target.isDirectory || !target.canWrite()) {
                        showNotification("Target is not a folder or not writable:\n$target")
                        return@setOnKeyPressed
                    }
                    val targetp = Paths.get(target.absolutePath, source.name)
                    if (targetp.toFile().exists()) {
                        showNotification("Target exists already:\n$targetp")
                        return@setOnKeyPressed
                    }
                    if (it.isControlDown && !it.isAltDown && !it.isMetaDown) {
                        logger.info("link $source to $target")
                        throw UnsupportedOperationException("can't link for now")
                    } else if (!it.isControlDown && it.isAltDown && !it.isMetaDown) {
                        logger.info("copy ${source.toPath()} to $targetp")
                        Files.copy(source.toPath(), targetp, StandardCopyOption.COPY_ATTRIBUTES)
                        showNotification("Copied\n$source\nto\n$targetp")
                    } else if (!it.isControlDown && !it.isAltDown && it.isMetaDown) {
                        logger.info("move ${source.toPath()} to $targetp")
                        Files.move(source.toPath(), targetp)
                        mv.showNext()
                        mv.currentFiles.remove(source)
                        showNotification("Moved\n$source\nto\n$targetp")
                    }
                }
                else -> {}
            }
        }

    }

    companion object {
        lateinit var mainstage: Stage
        fun showNotification(text: String, title: String = "") {
            runLater { Notifications.create().owner(mainstage).title(title).text(text).show() }
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

