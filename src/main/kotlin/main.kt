//import com.sun.javafx.webkit.WebConsoleListener

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.CornerRadii
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.stage.Stage
import mu.KLogger
import mu.KotlinLogging
import tornadofx.App
import tornadofx.launch
import tornadofx.onChange
import javax.imageio.ImageIO
import kotlin.system.exitProcess


private lateinit var logger: KLogger


class WImageViewer : App() {

    override fun start(stage: Stage) {
        val root = Pane()
        root.background = Background(BackgroundFill(Color.BLACK, CornerRadii.EMPTY, Insets.EMPTY))

        root.children += Label("huhu")

        mainpane = root
        stage.scene = Scene(root, 800.0, 600.0)
//        stage.isFullScreen = true
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
        if (Helpers.isMac()) java.awt.Taskbar.getTaskbar().iconImage = ImageIO.read(this::class.java.getResource("/icons/icon_256x256.png"))
        mainstage = stage

    }

    companion object {
        lateinit var mainstage: Stage
        private lateinit var mainpane: Pane

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

    logger.info("starting wimageviewer!")
    launch<WImageViewer>(args)
}

