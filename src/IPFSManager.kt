@file:Suppress("NON_EXHAUSTIVE_WHEN")

package fr.rhaz.ipfs

import com.google.gson.*
import com.google.gson.stream.JsonWriter
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.sun.java.accessibility.util.AWTEventMonitor.addActionListener
import io.ipfs.api.IPFS
import io.ipfs.api.NamedStreamable.FileWrapper
import io.ipfs.multihash.Multihash
import javafx.animation.FadeTransition
import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.embed.swing.SwingFXUtils
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.ClipboardContent
import javafx.scene.input.MouseButton.PRIMARY
import javafx.scene.input.MouseButton.SECONDARY
import javafx.scene.input.TransferMode
import javafx.scene.layout.*
import javafx.scene.text.Font
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.WindowEvent
import javafx.util.Duration
import java.awt.*
import java.awt.MenuItem as AWTMenuItem
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.*
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
        scene = Scene(body, minWidth, minHeight).apply {
            stylesheets.add(stylesheet)
        }
    }

    fun tray() {

        if(!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()

        val close = ActionListener { System.exit(0) }
        val show = ActionListener { Platform.runLater { window.show() } }

        val popup = PopupMenu()

        AWTMenuItem("Show me").apply {
            addActionListener(show)
            popup.add(this)
        }

        AWTMenuItem("Close me").apply {
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

    val body by lazy { BorderPane().apply{
        center = content
    }}

    val ipfs: IPFS
        get() = IPFS("/ip4/127.0.0.1/tcp/5001")

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
                    cursor = Cursor.HAND
                    setOnAction {
                        isVisible = false
                        start()
                    }
                }.also { children.add(it) }

            }

            ipfsd.listeners.onDownloaded.add(Runnable {
                async(3, {ipfs}, {console()}, error)
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

    val configfile by lazy {ipfsd.store["config"]}
    val config by lazy{JsonParser().parse(FileReader(configfile)).asJsonObject}

    fun config(consumer: (JsonObject) -> Unit){
        consumer(config)
        Files.write(configfile.toPath(), GsonBuilder().setPrettyPrinting().create().toJson(config).toByteArray())
    }

    var log = TextArea()
    fun console(){
        status.text = "Connected"



        val menu = MenuBar().also{body.top = it}.apply {
            style = "-fx-background-color: transparent"
            Menu("Other").also{menus.add(it)}.apply {
                MenuItem("Info").also{items.add(it)}.setOnAction {
                    dialog("Info", StackPane().apply {
                        padding = Insets(16.0)
                        Label(ipfs.version()).also{children.add(it)}.apply {

                        }
                    })
                }
            }
            Menu("Config").also{menus.add(it)}.apply {
                Menu("Identity").also{items.add(it)}.apply {
                    MenuItem("PeerID").also{items.add(it)}.setOnAction {
                        dialog("PeerID", StackPane().apply {
                            padding = Insets(16.0)
                            val id = config.getAsJsonObject("Identity").getAsJsonPrimitive("PeerID").asString
                            Label(id).also{children.add(it)}.apply {
                                font = Font.font(20.0)
                                setOnMouseClicked { ev -> when(ev.button){
                                    SECONDARY -> {
                                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(id), null)
                                        text = "Copied to clipboard"
                                        wait(1000){text = id}
                                    }
                                } }
                                setOnDragDetected {
                                    ClipboardContent().apply {
                                        putString(id)
                                        startDragAndDrop(*TransferMode.ANY).setContent(this)
                                    }
                                }
                            }
                        })
                    }
                }
                Menu("Gateway").also{items.add(it)}.apply {
                    CheckMenuItem("Writable").also{items.add(it)}.apply {
                        isSelected = config.getAsJsonObject("Gateway").run {
                            if(!has("Writable")) false
                            else getAsJsonPrimitive("Writable").asBoolean
                        }
                        setOnAction { config{
                            it.getAsJsonObject("Gateway").apply {
                                remove("Writable")
                                addProperty("Writable", isSelected)
                            }
                        } }
                    }
                }
                Menu("API").also{items.add(it)}.apply {
                    Menu("HTTPHeaders").also{items.add(it)}.apply {
                        MenuItem("Add origin...").also{items.add(it)}.apply {
                            setOnAction {
                                dialog("Add origin", HBox().apply {
                                    padding = Insets(16.0)
                                    val action: (String) -> Unit = { origin ->
                                        config {
                                            it.getAsJsonObject("API").getAsJsonObject("HTTPHeaders").apply {
                                                if(!has("Access-Control-Allow-Origin")) {
                                                    add("Access-Control-Allow-Origin", JsonArray())
                                                    getAsJsonArray("Access-Control-Allow-Origin").add("http://localhost:3000")
                                                }
                                                getAsJsonArray("Access-Control-Allow-Origin").add(origin)
                                            }
                                        }
                                        scene.window.hide()
                                    }
                                    val input = TextField("").apply {
                                        style = "-fx-background-color: white"
                                        promptText = "http://..."
                                        setOnAction{action(text)}
                                    }.also { children.add(it) }
                                    Button("Add").apply {
                                        style = "-fx-background-color: white"
                                        setOnAction {action(input.text)}
                                    }.also { children.add(it) }
                                })
                            }
                        }
                    }
                }
            }
        }

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
            text = "Connected! Type something or Drag & Drop a file/folder"
            style = "-fx-background-color: transparent; -fx-background-insets: 0px"
            padding = Insets(16.0)
            isEditable = false
            background = Background.EMPTY
        }.also { content.children.add(it) }

        TextField().apply {
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
        }.also { body.bottom = it }

        loadAll().values.apply{
            forEach {it.log = log}
            forEach(KScript::onEnabled)
        }
    }

    val keys
        get() = FXCollections.observableArrayList(ipfs.key.list().map {"${it.name} (${it.id.toBase58()})"})
                .apply { add("Create new key") }

    val open: (File) -> Unit = content@{ file -> dialog(file.name, StackPane().apply {
        minHeight = 150.0
        minWidth = 300.0
        padding = Insets(16.0)
        Label("Wrap into a directory?").apply {
            translateY = -20.0
            font = Font.font(20.0)
        }.also { children.add(it) }
        Button("Yes").apply {
            cursor = Cursor.HAND
            translateY = 20.0
            translateX = -60.0
            minWidth = 100.0
            style = "-fx-background-color: white"
            isDefaultButton = true
            setOnAction {
                scene.window.hide()
                ipfs.add(FileWrapper(file), true)[1].hash.also { info(file, it) }
            }
        }.also { children.add(it) }
        Button("No").apply {
            cursor = Cursor.HAND
            translateY = 20.0
            translateX = 60.0
            minWidth = 100.0
            style = "-fx-background-color: white"
            isCancelButton = true
            setOnAction {
                scene.window.hide()
                ipfs.add(FileWrapper(file), false)[0].hash.also { info(file, it) }
            }
        }.also { children.add(it) }
    })}

    val info: (File, Multihash) -> Unit = content@{ file, hash ->
        dialog(file.name, StackPane().apply{
            var url = "https://ipfs.io/ipfs/$hash"
            padding = Insets(32.0)
            minHeight = 400.0
            minWidth = 400.0
            val hint = Label("Double-click to open         Right click to copy").apply {
                translateY = -130.0
            }.also { children.add(it) }
            Label("$hash").apply {
                cursor = Cursor.HAND
                translateY = -160.0
                font = Font.font(20.0)
                var switch = 1
                fun switch() = when(switch++%4){
                    0 -> text = "$hash"
                    1 -> text = "http://ipfs.io/ipfs/$hash"
                    2 -> text = "ipfs://$hash"
                    3 -> text = "/ipfs/$hash"
                    else -> {}
                }
                var last: Thread? = null
                setOnMouseClicked { ev -> when(ev.button) {
                    PRIMARY -> when(ev.clickCount){
                        1 -> last = wait(500){switch()}
                        2 -> Desktop.getDesktop().browse(URI(url)).also{last?.interrupt()}
                    }
                    SECONDARY ->  {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                        val txt = hint.text
                        hint.text = "Copied to clipboard"
                        wait(1000){hint.text = txt}
                    }
                } }
                setOnDragDetected {
                    ClipboardContent().apply {
                        putString(text)
                        startDragAndDrop(*TransferMode.ANY).setContent(this)
                    }
                }
            }.also { children.add(it) }
            Button("Publish to...").apply {
                cursor = Cursor.HAND
                translateY = -90.0
                style = "-fx-background-color: white"
                setOnAction {
                    dialog(file.name, StackPane().apply dialog@{
                        minHeight = 200.0
                        minWidth = 400.0
                        val txt = Label("Publish to:").apply {
                            translateY = -40.0
                            padding = Insets(16.0)
                            font = Font.font(20.0)
                        }.also { children.add(it) }
                        HBox().apply hbox@{
                            alignment = Pos.CENTER
                            val box = ComboBox(keys).apply box@{
                                cursor = Cursor.HAND
                                style = "-fx-background-color: white"
                                value = keys[0]
                                maxWidth = 200.0
                                selectionModel.selectedItemProperty().addListener { _,_,value ->
                                    if(value != "Create new key") return@addListener
                                    dialog(value, HBox().apply dialog@{
                                        padding = Insets(16.0)
                                        val id = TextField("")
                                        val action = {
                                            this@dialog.cursor = Cursor.WAIT
                                            async(60,{
                                                ipfs.key.gen(id.text, Optional.of("rsa"), Optional.of("2048"))
                                            }, {
                                                scene.window.hide()
                                                this@box.selectionModel.select(0)
                                                this@box.items = keys
                                            }, {this@dialog.cursor = Cursor.DEFAULT})
                                        }
                                        id.apply {
                                            style = "-fx-background-color: white"
                                            promptText = "id"
                                            textProperty().addListener { _,_,new ->
                                                text = new.replace(Regex("[^a-zA-Z]"), "").toLowerCase()
                                            }
                                            setOnAction{action()}
                                        }.also { children.add(it) }
                                        Button("Create").apply {
                                            cursor = Cursor.HAND
                                            style = "-fx-background-color: white"
                                            isDefaultButton = true
                                            setOnAction{action()}
                                        }.also { children.add(it) }
                                    })
                                }
                            }.also { children.add(it) }
                            Button("Publish").apply {
                                cursor = Cursor.HAND
                                style = "-fx-background-color: white"
                                isDefaultButton = true
                                setOnAction {
                                    this@dialog.cursor = Cursor.WAIT
                                    val id = box.value.split(" ")[0]
                                    txt.text = "Publishing to $id..."
                                    txt.translateY = -30.0
                                    this@dialog.children.remove(this@hbox)
                                    val hint = Label("please wait")
                                    this@dialog.children.add(hint)
                                    val task = async(300, {
                                        ipfs.name.publish(hash, Optional.of(id))["Name"]
                                    }, { hash ->
                                        this@dialog.cursor = Cursor.DEFAULT
                                        val url = "https://ipfs.io/ipns/$hash"
                                        hint.text = "Double-click to open         Right click to copy"
                                        txt.apply {
                                            cursor = Cursor.HAND
                                            var switch = 1
                                            fun switch() = when(switch++%4){
                                                0 -> text = "$hash"
                                                1 -> text = "http://ipfs.io/ipns/$hash"
                                                2 -> text = "ipns://$hash"
                                                3 -> text = "/ipns/$hash"
                                                else -> {}
                                            }
                                            var last: Thread? = null
                                            txt.setOnMouseClicked { ev -> when(ev.button) {
                                                PRIMARY -> when(ev.clickCount){
                                                    1 -> last = wait(500){switch()}
                                                    2 -> Desktop.getDesktop().browse(URI(url)).also{last?.interrupt()}
                                                }
                                                SECONDARY -> {
                                                    Toolkit.getDefaultToolkit().systemClipboard
                                                            .setContents(StringSelection(text), null)
                                                    val htext = hint.text
                                                    hint.text = "Copied to clipboard"
                                                    wait(1000){hint.text = htext}
                                                }
                                            }}
                                            text = "$hash"
                                        }
                                    }, {
                                        this@dialog.cursor = Cursor.DEFAULT
                                        txt.text = "Error!"
                                    })
                                }
                            }.also { children.add(it) }
                        }.also { children.add(it) }
                    })
                }
            }.also { children.add(it) }
            StackPane().apply {
                translateY = 60.0
                maxHeight = 200.0
                maxWidth = 200.0
                ImageView(qr(url, 200, 200)).apply {
                    padding = Insets(0.0)
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

fun async(timeout: Int, runnable: () -> Any?, success: (Any) -> Unit, error: () -> Unit) = Thread{
    try {
        val result = runnable()
        if(result != null)
            Platform.runLater{success(result)}
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
}.let { Thread(it) }.apply { start() }

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
