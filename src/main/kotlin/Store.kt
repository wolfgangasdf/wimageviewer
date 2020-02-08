
import javafx.beans.property.IntegerProperty
import mu.KotlinLogging
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.*

private val logger = KotlinLogging.logger {}

object StoreSettings {
    private var settpath = ""

    init {
        settpath = when {
            Helpers.isMac() -> System.getProperty("user.home") + "/Library/Application Support/WImageViewer"
            Helpers.isLinux() -> System.getProperty("user.home") + "/.wimageviewer"
            Helpers.isWin() -> Helpers.toJavaPathSeparator(System.getenv("APPDATA")) + "\\WImageViewer"
            else -> throw Exception("operating system not found")
        }
    }

    fun getSettingFile(): File = File("$settpath/settings.properties")
}

///////////////////////// settings

class MainSettings(val hideTimeout: IntegerProperty = SIP(50),
                   val quickFolders: MutableMap<Int, String> = mutableMapOf()
)

object Settings {
    val settings = MainSettings()
    private const val nquickfolder = 6

    fun saveSettings() {
        val props = Properties()
        props["settingsversion"] = "1"
        props["wiv.hideTimeout"] = settings.hideTimeout.value.toString()
        settings.quickFolders.forEach { e -> props["qf.${e.key}"] = e.value }
        StoreSettings.getSettingFile().parentFile.mkdirs()
        val fw = FileWriter(StoreSettings.getSettingFile())
        props.store(fw, null)
        logger.info("settings saved!")
    }

    private fun loadSettings() {
        logger.info("load settings ${StoreSettings.getSettingFile()}")
        val propsx = Properties()
        if (StoreSettings.getSettingFile().exists()) {
            val fr = FileReader(StoreSettings.getSettingFile())
            propsx.load(fr)
        }
        val props = propsx.map { (k, v) -> k.toString() to v.toString() }.toMap()
        if (props.getOrDefault("settingsversion", "1") != "1") throw Exception("wrong settingsversion!")
        try {
            settings.hideTimeout.set(props.getOrDefault("wb.hideTimeout", "50").toInt())
            settings.quickFolders.clear()
            for (i in 1..nquickfolder) { settings.quickFolders[i]=props.getOrDefault("qf.$i", "") }
            println("XXXXXXXXXXX")
        } catch (e: Exception) {
            logger.error("error loading settings: ${e.message}")
            e.printStackTrace()
        }
        logger.info("settings loaded!")
    }

    init {
        loadSettings()
        saveSettings()
    }

}

