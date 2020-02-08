
import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.image.WritableImage
import javafx.scene.input.KeyCode
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.CornerRadii
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.stage.Stage
import mu.KLogger
import mu.KotlinLogging
import org.controlsfx.control.Notifications
import tornadofx.*
import java.io.File
import java.lang.management.ManagementFactory
import javax.imageio.ImageIO
import kotlin.system.exitProcess


private lateinit var logger: KLogger

class MainView: View("WImageViewer") {
    val currentFolder = SimpleStringProperty("")
    val currentFiles = mutableListOf<File>()

    val currentImage = SimpleObjectProperty<Image?>()
    val currentFile = SimpleObjectProperty<File>() // this controls what is shown

    val iv = imageview(currentImage) {
        isPreserveRatio = true
    }

    val imageExtensions = listOf(".jpg", ".jpeg", ".png")


    fun showNext() {
        logger.debug("show next")
        val oldidx = currentFiles.indexOf(currentFile.get())
        if (oldidx < currentFiles.size - 1) currentFile.set(currentFiles[oldidx + 1])
        else Notifications.create().text("No next image!").show()
        logger.debug("   $oldidx")
    }

    fun showPrev() {
        logger.debug("show prev")
        val oldidx = currentFiles.indexOf(currentFile.get())
        if (oldidx > 0 ) currentFile.set(currentFiles[oldidx - 1])
        else Notifications.create().text("No previous image!").show()
    }

    var pInfos: Form = form {}

    fun genInfos() = form {
        background = Background(BackgroundFill(Color.YELLOW, CornerRadii.EMPTY, Insets.EMPTY))
        fieldset("File info") {
            field("Name") {
                label(currentFile)
            }
            field("Size") {
                label(currentFile.get().length().toString())
            }

        }
        label("huhu")
        maxWidth = 300.0
        maxHeight = 300.0
    }

    fun showInfos() {
        if (root.children.contains(pInfos))
            root.children.remove(pInfos)
        else {
            pInfos = genInfos()
            root.add(pInfos)
        }
    }

    fun updateFiles(folder: File) {
        folder.listFiles()?.filter { f -> f.isDirectory || imageExtensions.any { f.name.endsWith(it) }}?.also {
            logger.debug("adding ${it.joinToString(", ")}")
            currentFiles.clear()
            currentFiles.addAll(it)
        }
        runLater { Notifications.create().text("Loaded files in $folder").show() }
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
        if (Helpers.isMac()) {
            java.awt.Taskbar.getTaskbar().iconImage = ImageIO.read(this::class.java.getResource("/icons/icon_256x256.png"))
        }

        super.start(stage)

        val mv = MainView()

        stage.scene = Scene(mv.root, 800.0, 600.0)
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
        mainstage = stage

        stage.scene?.setOnKeyPressed {
            when(it.code) {
                KeyCode.F -> stage.isFullScreen = !stage.isFullScreen
                KeyCode.DOWN, KeyCode.SPACE -> mv.showNext()
                KeyCode.UP -> mv.showPrev()
                KeyCode.I -> mv.showInfos()
                else -> {}
            }
        }

        mv.setFolderFile(File("/Users/wolle/tmp/imgtest"))
    }

    companion object {
        lateinit var mainstage: Stage
    }
}

fun main(args: Array<String>) {

    System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    System.setProperty(org.slf4j.simple.SimpleLogger.SHOW_DATE_TIME_KEY, "true")
    System.setProperty(org.slf4j.simple.SimpleLogger.DATE_TIME_FORMAT_KEY, "yyyy-MM-dd HH:mm:ss:SSS")
    System.setProperty(org.slf4j.simple.SimpleLogger.LOG_FILE_KEY, "System.out") // and use intellij "grep console" plugin
    // System.setProperty("javax.net.debug", "all")
//    System.setProperty("prism.verbose", "true")
    logger = KotlinLogging.logger {} // after set properties!

    logger.error("error")
    logger.warn("warn")
    logger.info("info jvm ${System.getProperty("java.version")}")
    logger.debug("debug")
    logger.trace("trace")

    ManagementFactory.getRuntimeMXBean().inputArguments.forEach { logger.debug("jvm arg: $it") }

    logger.info("starting wimageviewer!")
    launch<WImageViewer>(args)
}

