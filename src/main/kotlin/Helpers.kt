
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.scene.image.WritableImage
import mu.KotlinLogging

import tornadofx.error

import java.awt.Desktop
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.image.BufferedImage

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.*

typealias SSP = SimpleStringProperty
typealias SDP = SimpleDoubleProperty
typealias SIP = SimpleIntegerProperty

private val logger = KotlinLogging.logger {}

object Helpers {
    fun isMac() = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("mac")
    fun isLinux() = System.getProperty("os.name").lowercase(Locale.getDefault()).matches("(.*nix)|(.*nux)".toRegex())
    fun isWin() = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")

    fun toJavaPathSeparator(input: String): String =
            if (isWin()) input.replace("""\\""", "/")
            else input

    fun revealFile(file: File, gointo: Boolean = false) {
        when {
            isMac() -> Runtime.getRuntime().exec(arrayOf("open", if (gointo) "" else "-R", file.path))
            isWin() -> Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,${file.path}"))
            isLinux() -> error("not supported OS, tell me how to do it!")
            else -> logger.error("not supported OS, tell me how to do it!")
        }
    }
    fun openFile(file: File) {
        if (Desktop.isDesktopSupported() && file.exists()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(file)
            }
        }
    }

    fun openURL(url: String) {
        if (Desktop.isDesktopSupported() && url != "") {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI(url))
            }
        }
    }

    fun trashOrDelete(f: File): String {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                desktop.moveToTrash(f)
                return "Trashed"
            }
        }
        Files.delete(f.toPath())
        return "Deleted"
    }

    // create image from text, can be called from any thread.
    fun textToImage(text: String): WritableImage { // https://stackoverflow.com/a/23568375
        var img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        var g2d: Graphics2D = img.createGraphics()
        val font = Font("Courier New", Font.PLAIN, 30)
        g2d.font = font
        var fm: FontMetrics = g2d.fontMetrics
        val width: Int = fm.stringWidth(text)
        val height: Int = fm.height
        logger.debug("textToImage: $width $height")
        g2d.dispose()
        img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        g2d = img.createGraphics()
        g2d.font = font
        fm = g2d.fontMetrics
        g2d.color = java.awt.Color.WHITE
        g2d.drawString(text, 0, fm.ascent)
        g2d.dispose()
        // convert to javafx image:
        val wr = WritableImage(img.width, img.height)
        val pw = wr.pixelWriter
        for (x in 0 until img.width) {
            for (y in 0 until img.height) {
                pw.setArgb(x, y, (0xff shl 24) or img.getRGB(x, y))
            }
        }
        return wr
        // return Text(text).snapshot(null, null) // this needs FX thread
    }

}
