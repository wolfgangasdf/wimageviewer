
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import tornadofx.error
import java.awt.Desktop
import java.io.File
import java.net.URI

typealias SSP = SimpleStringProperty
typealias SDP = SimpleDoubleProperty
typealias SIP = SimpleIntegerProperty

object Helpers {
    fun isMac() = System.getProperty("os.name").toLowerCase().contains("mac")
    fun isLinux() = System.getProperty("os.name").toLowerCase().matches("(.*nix)|(.*nux)".toRegex())
    fun isWin() = System.getProperty("os.name").toLowerCase().contains("win")

    fun toJavaPathSeparator(input: String): String =
            if (isWin()) input.replace("""\\""", "/")
            else input

    fun revealFile(file: File, gointo: Boolean = false) {
        when {
            isMac() -> Runtime.getRuntime().exec(arrayOf("open", if (gointo) "" else "-R", file.path))
            isWin() -> Runtime.getRuntime().exec("explorer.exe /select,${file.path}")
            isLinux() -> error("not supported OS, tell me how to do it!")
            else -> error("not supported OS, tell me how to do it!")
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

}
