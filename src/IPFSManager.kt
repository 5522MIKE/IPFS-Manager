package fr.rhaz.ipfs

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import io.ipfs.kotlin.IPFS
import javafx.animation.FadeTransition
import javafx.application.Application
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.TransferMode
import javafx.scene.layout.*
import javafx.scene.text.Font
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.WindowEvent
import javafx.util.Duration
import java.awt.*
import java.awt.event.ActionListener
import java.io.File
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.FileSystems
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    Application.launch(IPFSManager::class.java, *args)
}

lateinit var manager: IPFSManager

class IPFSManager : Application() {

    override fun start(stage: Stage) {
        manager = this
        window = stage
        window.apply(Window).apply{show()}
    }

    lateinit var window: Stage

    val icon
        get() = IPFSManager::class.java.classLoader.getResourceAsStream("icon.png")

    val stylesheet
        get() = IPFSManager::class.java.classLoader.getResource("style.css").toExternalForm()

    val Window: Stage.() -> Unit = {
        Platform.setImplicitExit(false)
        tray()
        icons.add(Image(icon))
        title = "IPFS Manager"
        minWidth = 600.0
        minHeight = 400.0
        scene = Scene(Body, minWidth, minHeight).apply {
            stylesheets.add(stylesheet)
        }
    }

    fun tray() {

        if(!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()

        val close = ActionListener { System.exit(0) }
        val show = ActionListener { Platform.runLater { window.show() } }

        val popup = PopupMenu()

        MenuItem("Show me").apply {
            addActionListener(show)
            popup.add(this)
        }

        MenuItem("Close me").apply {
            addActionListener(close)
            popup.add(this)
        }

        val icon = ImageIO.read(icon)
        TrayIcon(icon, "IPFS Manager", popup).apply {
            addActionListener(show)
            isImageAutoSize = true
            tray.add(this)
        }

        window.onCloseRequest = EventHandler<WindowEvent> {
            if(!SystemTray.isSupported()) System.exit(0)
            window.hide()
        }
    }

    val Body: BorderPane by lazy {
        BorderPane().apply {
            top = Top
            center = content
        }
    }

    val Top: HBox by lazy {
        HBox().apply {

        }
    }

    var ipfs = IPFS()

    lateinit var status: Label
    val content: StackPane by lazy {
        StackPane().apply {

            requestFocus()
            setOnMouseClicked { requestFocus() }

            status = Label("Loading...").apply {
                font = Font.font(30.0)
                translateY = -30.0
            }.also { children.add(it); }

            val error: () -> Unit = here@{

                status.text = "Could not connect"

                Button("Start Daemon").apply {
                    translateY = 20.0
                    style = "-fx-background-color: white"
                    setOnAction {
                        isVisible = false
                        start()
                    }
                }.also { children.add(it) }

            }

            ipfsd.listeners.onDownloaded.add(Runnable {
                async(3, {ipfs.info.version()}, {console()}, error)
            })

            download()

        }
    }

    val ipfsd by lazy {
        IPFSDaemon().apply {
            listeners.onDownloading.add(Runnable {
                Platform.runLater {status.text = "Downloading..."}
            })
            listeners.onInitializing.add(Runnable {
                Platform.runLater {status.text = "Initializing..."}
            })
            listeners.onStarting.add(Runnable {
                Platform.runLater {status.text = "Starting..."}
            })
            callback = here@{ process, msg ->
                if(msg == "Daemon is ready")
                    return@here Platform.runLater {console()}

                if(msg == "ipfs: Reading from /dev/stdin; send Ctrl-z to stop.") {
                    process.destroy()
                    log.append("IPFS Manager: Please specify arguments")
                    return@here
                }

                Platform.runLater { log.append(msg) }
            }
        }
    }

    fun download() = Thread{ipfsd.download()}.apply{start()}
    fun start() = Thread{ipfsd.start(true)}.apply{start()}
    fun process(vararg args: String) = ipfsd.process(*args).also { ipfsd.gobble(it) }

    var log = TextArea()
    fun console(){
        status.text = "Connected"

        content.setOnDragOver { it.acceptTransferModes(*TransferMode.ANY); }
        content.setOnDragDropped here@{
            val drag = it.dragboard
            if(!drag.hasFiles()) return@here
            open(drag.files.singleOrNull() ?: return@here);
        }

        FadeTransition(Duration(2000.0), status).apply {
            fromValue = 1.0
            toValue = 0.0
            play()
        }

        log.apply {
            text = "Connected! Type something"
            style = "-fx-background-color: transparent; -fx-background-insets: 0px"
            padding = Insets(16.0, 16.0, 32.0, 16.0)
            isEditable = false
            background = Background.EMPTY
        }.also { content.children.add(it) }

        TextField().apply {
            translateY = 180.0
            padding = Insets(16.0)
            style = "-fx-background-color: transparent"
            promptText = "name publish Qm..."
            setOnAction {
                text = "".also{_->
                    log.text += "\n>$text"
                    log.layout()
                    log.scrollTop = Double.MAX_VALUE
                    plugins.filterKeys{text.startsWith(it, true)}.values.mapNotNull{it as? Task}.firstOrNull()?.onCall(text)
                        ?: process(*text.split(" ").toTypedArray())
                }
            }
        }.also { content.children.add(it) }

        loadAll().values.apply{
            forEach {it.log = log}
            forEach(KScript::onEnabled)
        }
    }

    val open: (File) -> Unit = content@{ file ->
        val hash = ipfs.add.file(file, file.nameWithoutExtension, file.name).Hash
        val url = "https://ipfs.io/ipfs/$hash"
        dialog(file.name, VBox().apply {
            padding = Insets(32.0)
            Label(hash).apply {
                font = Font.font(20.0)
                setOnMouseClicked { Desktop.getDesktop().browse(URI(url)) }
            }.also { children.add(it) }
            StackPane().apply {
                translateY = 20.0
                padding = Insets(32.0)
                ImageView(qr(url, 200, 200)).apply {
                    style = "-fx-background-color: transparent"
                }.also { children.add(it) }
            }.also { children.add(it) }
        });
    }

    fun qr(text: String, width: Int, height: Int) =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height, mapOf(EncodeHintType.MARGIN to 0))
            .let{MatrixToImageWriter.toBufferedImage(it)}
            .let{SwingFXUtils.toFXImage(it, null)}

    val dialog: (title: String, pane: Pane) -> Unit = { title, pane ->

        val dialog = Stage()

        dialog.apply {
            icons.add(Image(icon))
            this.title = title
            isAlwaysOnTop = true
            initModality(Modality.WINDOW_MODAL)
            scene = Scene(pane)
            show()
        }
    }

}

fun TextArea.append(msg: String){
    text += "\n$msg"
    layout()
    scrollTop = Double.MAX_VALUE
}

fun async(timeout: Int, runnable: () -> Any?, success: () -> Unit, error: () -> Unit) = Thread{
    try {
        if (runnable() != null)
            Platform.runLater(success)
        else Platform.runLater(error)
    }catch(ex: Exception) {Platform.runLater(error)}
}.apply{tasker(timeout, error)}

fun Thread.tasker(timeout: Int, error: () -> Unit) = {
    start()
    val start = System.currentTimeMillis()
    while (this.isAlive) {
        Thread.sleep(1000)
        if (System.currentTimeMillis() - start > (timeout * 1000)){
            interrupt(); Platform.runLater(error); break
        }
    }
}.let {Thread(it).start()}

fun wait(timeout: Long, callback: () -> Unit) = {
    Thread.sleep(timeout)
    Platform.runLater { callback() }
}.let { Thread(it).start() }

val dfolder = File("scripts")

var plugins = emptyMap<String, KScript>()

fun loadAll(folder: File = dfolder): Map<String, KScript> {
    if(!folder.exists()) return emptyMap()
    plugins = folder.listFiles().mapNotNull(::load).associateBy{it.name}
    return plugins
}

fun load(file: File): KScript? {
    try {
        val urls = arrayOf(file.parentFile.toURI().toURL())
        val loader = URLClassLoader(urls)
        val cls = loader.loadClass(file.nameWithoutExtension)
        val obj = cls.newInstance()
        if(obj is KScript) return obj
        return cls.getMethod("main").invoke(obj) as? KScript
    }catch (ex: Exception){
        ex.printStackTrace()
        return null
    }
}

fun unloadAll() = plugins.values.forEach(KScript::onDisabled)

open class KScript(val name: String) {
    val ipfs = manager.ipfs
    lateinit var log: TextArea
    open fun onEnabled(){}
    open fun onDisabled(){}
}

abstract class Task(name: String): KScript(name){
    abstract fun onCall(line: String)
}
