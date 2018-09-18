@file:Suppress("NON_EXHAUSTIVE_WHEN", "IMPLICIT_CAST_TO_ANY")

package fr.rhaz.ipfs

import com.google.gson.*
import com.google.gson.stream.JsonWriter
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.sun.java.accessibility.util.AWTEventMonitor.addActionListener
import io.ipfs.api.IPFS
import io.ipfs.api.IPFS.PinType.*
import io.ipfs.api.MerkleNode
import io.ipfs.api.NamedStreamable
import io.ipfs.api.NamedStreamable.*
import io.ipfs.multiaddr.MultiAddress
import io.ipfs.multihash.Multihash
import javafx.animation.FadeTransition
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectWrapper
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
import javafx.stage.*
import javafx.util.Duration
import java.awt.*
import java.awt.MenuItem as AWTMenuItem
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStreamReader
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.*
import javax.imageio.ImageIO
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TableView.*
import javafx.stage.FileChooser.*
import java.awt.datatransfer.DataFlavor

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
        try{update()}catch(ex:Exception){}
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

    fun update() {
        val conn = "https://raw.githubusercontent.com/RHazDev/IPFS-Manager/master/resources/version.json"
                    .let{URL(it)}.openConnection()
        val json = InputStreamReader(conn.inputStream).let{ JsonParser().parse(it).asJsonObject}
        val version = json.getAsJsonPrimitive("version").asString
        val url = json.getAsJsonPrimitive("url").asString
        val local = InputStreamReader(IPFSManager::class.java.classLoader.getResourceAsStream("version.json"))
        val myversion = JsonParser().parse(local).asJsonObject.getAsJsonPrimitive("version").asString
        if(version newerThan myversion)
            dialog("Update", StackPane().apply {
                padding = Insets(32.0)
                Label("A new version is available: $version").also{children.add(it)}.apply {
                    translateY = -20.0
                    font = Font.font(16.0)
                }
                Button("Download").also{children.add(it)}.apply {
                    cursor = Cursor.HAND
                    translateY = 20.0
                    style = "-fx-background-color: white"
                    setOnAction { Desktop.getDesktop().browse(URI(url)) }
                }
            })
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
                async(3, {ipfs}, {checkStore()}, error)
            })

            download()

        }
    }

    val ipfsd by lazy {
        IPFSDaemon().apply {
            args = arrayOf("--enable-pubsub-experiment")
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
                    return@here Platform.runLater {checkStore()}

                if(msg.contains("already have connection to peer"))
                    return@here

                Platform.runLater { log.append(msg) }
            }
        }
    }

    fun download() = Thread{ipfsd.download()}.apply{start()}
    fun start() = Thread{ipfsd.start(true)}.apply{start()}
    fun process(vararg args: String) = ipfsd.process(*args).also { ipfsd.gobble(it) }

    val config by lazy{JsonParser().parse(FileReader(ipfsd.store["config"])).asJsonObject}

    fun config(consumer: (JsonObject) -> Unit){
        consumer(config)
        Files.write(ipfsd.store["config"].toPath(), GsonBuilder().setPrettyPrinting().create().toJson(config).toByteArray())
    }

    fun checkStore(): Unit = when {

        ipfsd.store.exists() -> {
            menu()
            console()
        }

        File("../.ipfs").exists() -> {
            ipfsd.store = File("../.ipfs")
            menu()
            console()
        }

        else -> Unit.also{
            println(ipfsd.store.parentFile.name)
            status.text = "Could not find .ipfs folder"
            Button("Choose...").also{content.children.add(it)}.apply {
                cursor = Cursor.HAND
                style = "-fx-background-color: white"
                translateX = -80.0
                translateY = 20.0
                setOnMouseClicked {
                    do {ipfsd.store = DirectoryChooser().showDialog(window)}
                    while (ipfsd.store.name != ".ipfs")
                    content.children.remove(this)
                    menu()
                    console()
                }
            }
            Button("Continue anyway").also{content.children.add(it)}.apply {
                cursor = Cursor.HAND
                style = "-fx-background-color: white"
                translateX = 80.0
                translateY = 20.0
                setOnMouseClicked {
                    console()
                }
            }

        }
    }

    fun menu() = MenuBar().also{body.top = it}.apply {
        val notimpl = {dialog("IPFS Manager", StackPane().apply {
            padding = Insets(32.0)
            Label("Not yet implemented!").also{children.add(it)}.apply {
                translateY = -20.0
                font = Font.font(20.0)
            }
            Label("coming soon... :)").also{children.add(it)}.apply{
                translateY = 20.0
            }
        })}
        style = "-fx-background-color: transparent"
        Menu("Action").also{menus.add(it)}.apply{
            MenuItem("Open from clipboard").also{items+=it}.setOnAction {
                val txt = try {
                    Toolkit.getDefaultToolkit()
                        .systemClipboard.getData(DataFlavor.stringFlavor) as String
                }catch(ex:Exception){null}
                txt?.also { txt -> Multihash.fromBase58(txt)?.let{ipfs(txt, it)}} ?: {
                    minidialog(txt ?: "Cipboard", "Could not find hash, copy a valid base58 hash and try-again")
                }()
            }
            MenuItem("Open file...").also{items+=it}.setOnAction {
                FileChooser().run {
                    extensionFilters+=ExtensionFilter("IPFS file", "*.ipfs")
                    showOpenDialog(window)
                }?.apply {
                    Multihash.fromBase58(readText())?.let { ipfs(name, it) } ?: {
                        minidialog(name, "Could not find hash, ensure the file has been exported by this software")
                    }()
                }
            }
            MenuItem("Add files...").also{items.add(it)}.setOnAction{
                FileChooser().showOpenMultipleDialog(window).let(open)
            }
            MenuItem("Add folder...").also{items.add(it)}.setOnAction{
                open(listOf(DirectoryChooser().showDialog(window)))
            }
            MenuItem("Pins management").also{items.add(it)}.setOnAction{
                dialog("Pins management", BorderPane().apply {
                    minHeight = 500.0
                    minWidth = 800.0
                    top = StackPane().apply {
                        padding = Insets(16.0, 16.0, 0.0, 16.0)
                        Label("Pins Management").also{children.add(it)}.apply {
                            font = Font.font(20.0)
                        }
                    }
                    val table = TableView<Multihash>().apply {
                        padding = Insets(16.0, 16.0, 16.0, 16.0)
                        style = "-fx-background-color: transparent; -fx-background-insets: 0px"
                        background = Background.EMPTY
                        columnResizePolicy = CONSTRAINED_RESIZE_POLICY
                        TableColumn<Multihash, String>("Hash").also{columns+=it}.apply {
                            setCellValueFactory { ReadOnlyObjectWrapper(it.value.toBase58()) }
                        }
                        setRowFactory {
                            TableRow<Multihash>().apply {
                                setOnMouseClicked {
                                    if(it.clickCount > 1) ipfs(item.toBase58(), item)
                                }
                            }
                        }
                        items.addAll(ipfs.pin.ls(recursive).keys)
                    }
                    center = table
                    bottom = StackPane().apply {
                        padding = Insets(0.0, 16.0, 16.0, 16.0)
                        Button("Close").also{children.add(it)}.apply {
                            cursor = Cursor.HAND
                            style = "-fx-background-color: white"
                            setOnAction { scene.window.hide() }
                        }
                    }
                })
            }
            MenuItem("Keys management").also{items.add(it)}.setOnAction{
                dialog("Keys management", StackPane().apply {
                    padding = Insets(32.0)
                    minHeight = 160.0
                    Label("Keys management").also{children+=it}.apply {
                        font = Font.font(20.0)
                        translateY = -40.0
                    }
                    val box = ComboBox(keys).also{children+=it}.apply{
                        cursor = Cursor.HAND
                        style = "-fx-background-color: white"
                        value = keys[0]
                        maxWidth = 200.0
                    }
                    Button("Open").also{children+=it}.apply {
                        cursor = Cursor.HAND
                        style = "-fx-background-color: white"
                        isDefaultButton = true
                        translateY = 40.0
                        translateX = -60.0
                        setOnAction {
                            val id = box.value.split(" ")[0]
                            val hash = box.value.split(" ")[1].run{substring(1, length-1)}
                            ipns(id, hash)
                        }
                    }
                    val rename = Button("Rename").also{children+=it}.apply {
                        isDisable = true
                        cursor = Cursor.HAND
                        style = "-fx-background-color: white"
                        isDefaultButton = true
                        translateY = 40.0
                        setOnAction {
                            val id = box.value.split(" ")[0]
                            dialog("Rename $id", HBox().apply dialog@{
                                padding = Insets(16.0)
                                val field = TextField(id).also{children+=it}.apply {
                                    style = "-fx-background-color: white"
                                    textProperty().addListener { _,_,new ->
                                        text = new.replace(Regex("[^a-zA-Z]"), "").toLowerCase()
                                    }
                                }
                                val action = {
                                    this@dialog.cursor = Cursor.WAIT
                                    async(60,{
                                        ipfs.key.rename(id, field.text)
                                    }, {
                                        scene.window.hide()
                                        box.selectionModel.select(0)
                                        box.items = keys
                                    }, {this@dialog.cursor = Cursor.DEFAULT})
                                }
                                field.setOnAction{action()}
                                Button("Create").apply {
                                    cursor = Cursor.HAND
                                    style = "-fx-background-color: white"
                                    isDefaultButton = true
                                    setOnAction{action()}
                                }.also { children.add(it) }
                            })
                        }
                    }
                    val delete = Button("Delete").also{children+=it}.apply {
                        isDisable = true
                        cursor = Cursor.HAND
                        style = "-fx-background-color: white"
                        isDefaultButton = true
                        translateY = 40.0
                        translateX = 60.0
                        setOnAction {
                            val id = box.value.split(" ")[0]
                            ipfs.key.rm(id)
                            box.selectionModel.select(0)
                            box.items = keys
                        }
                    }
                    box.selectionModel.selectedItemProperty().addListener { _,_,value ->
                        if(value.split(" ")[0] == "self") {
                            rename.isDisable = true
                            delete.isDisable = true
                        }
                        else {
                            rename.isDisable = false
                            delete.isDisable = false
                        }
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
                                    box.selectionModel.select(0)
                                    box.items = keys
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
                })
            }
            Menu("Pub/Sub").also{items.add(it)}.apply{
                MenuItem("Publish...").also{items.add(it)}.setOnAction{
                    dialog("Publish", VBox().apply{
                        padding = Insets(16.0)
                        val action: (String, String) -> Unit = { topic, data ->
                            ipfs.pubsub.pub(topic, data)
                            scene.window.hide()
                        }
                        val topic = TextField("").apply {
                            minWidth = 300.0
                            style = "-fx-background-color: white"
                            promptText = "sunflowers"
                        }.also { children.add(it) }
                        val data = TextField("").apply {
                            minWidth = 300.0
                            style = "-fx-background-color: white"
                            promptText = "hello world"
                        }.also { children.add(it) }
                        Button("Publish").apply {
                            cursor = Cursor.HAND
                            style = "-fx-background-color: white"
                            setOnAction {action(topic.text, data.text)}
                        }.also { children.add(it) }
                        requestFocus()
                    })
                }
                MenuItem("Subscribe...").also{items.add(it)}.setOnAction{
                    dialog("Subscribe", HBox().apply {
                        padding = Insets(16.0)
                        val action: (String) -> Unit = { topic ->
                            ipfs.pubsub.sub(topic)
                            scene.window.hide()
                        }
                        val topic = TextField("").apply {
                            minWidth = 300.0
                            style = "-fx-background-color: white"
                            promptText = "sunflowers"
                        }.also { children.add(it) }
                        Button("Subscribe").apply {
                            cursor = Cursor.HAND
                            style = "-fx-background-color: white"
                            setOnAction {action(topic.text)}
                        }.also { children.add(it) }
                        requestFocus()
                    })
                }
            }
            Menu("Swarm").also{items.add(it)}.apply {
                MenuItem("Connect to...").also{items.add(it)}.setOnAction{
                    dialog("Connect to...", HBox().apply {
                        padding = Insets(16.0)
                        val action: (String) -> Unit = { text ->
                            ipfs.swarm.connect(text)
                            scene.window.hide()
                        }
                        val input = TextField("").apply {
                            minWidth = 300.0
                            style = "-fx-background-color: white"
                            promptText = "Multi address"
                        }.also { children.add(it) }
                        Button("Connect").apply {
                            cursor = Cursor.HAND
                            style = "-fx-background-color: white"
                            setOnAction {action(input.text)}
                        }.also { children.add(it) }
                        requestFocus()
                    })
                }
                MenuItem("Disconnect from...").also{items.add(it)}.setOnAction{
                    dialog("Disconnect from...", HBox().apply {
                        padding = Insets(16.0)
                        val action: (String) -> Unit = { text ->
                            ipfs.swarm.disconnect(text)
                            scene.window.hide()
                        }
                        val input = TextField("").apply {
                            minWidth = 300.0
                            style = "-fx-background-color: white"
                            promptText = "Multi address"
                        }.also { children.add(it) }
                        Button("Disconnect").apply {
                            cursor = Cursor.HAND
                            style = "-fx-background-color: white"
                            setOnAction {action(input.text)}
                        }.also { children.add(it) }
                        requestFocus()
                    })
                }
            }
            Menu("DHT").also{items.add(it)}.apply {
                MenuItem("Find peer...").also{items.add(it)}.setOnAction{
                    val title = "Find peer"
                    dialog(title, HBox().apply {
                        padding = Insets(16.0)
                        val action: (String) -> Unit = { input ->
                            val loading = minidialog(title, "Finding peer...")
                            async(60, {((
                                ipfs.dht.findpeer(Multihash.fromBase58(input))
                                ?.get("Responses") as? List<*>)
                                ?.get(0) as? Map<*,*>)
                                ?.get("Addrs")},
                                    { result ->
                                        loading.close()
                                        val list = (result as List<*>).map{it.toString()}
                                        listdialog(title, input, list, true)
                                    },
                                    {
                                        loading.close()
                                        minidialog(title, "Could not find peer")
                                    }
                            )
                        }
                        val input = TextField("").apply {
                            minWidth = 300.0
                            style = "-fx-background-color: white"
                            promptText = "PeerID (Qm...)"
                            setOnAction {action(text)}
                        }.also { children.add(it) }
                        Button("Apply").apply {
                            cursor = Cursor.HAND
                            style = "-fx-background-color: white"
                            setOnAction {action(input.text)}
                        }.also { children.add(it) }
                        requestFocus()
                    })
                }
                MenuItem("Find providers...").also{items.add(it)}.setOnAction{
                    val title = "Find providers"
                    dialog(title, HBox().apply {
                        padding = Insets(16.0)
                        val action: (String) -> Unit = { text ->
                            scene.window.hide()
                            val loading = minidialog(title, "Querying...")
                            async(60, {ipfs.dht.findprovs(Multihash.fromBase58(text))},
                                { result ->
                                    loading.close()
                                    minidialog(title, result.toString())
                                }, {
                                    loading.close()
                                    minidialog(title, "Could not find providers")
                                }
                            )
                        }
                        val input = TextField("").apply {
                            minWidth = 300.0
                            style = "-fx-background-color: white"
                            promptText = "Content hash (Qm...)"
                            setOnAction {action(text)}
                        }.also { children.add(it) }
                        Button("Apply").apply {
                            cursor = Cursor.HAND
                            style = "-fx-background-color: white"
                            setOnAction {action(input.text)}
                        }.also { children.add(it) }
                        requestFocus()
                    })
                }
                MenuItem("Query peer...").also{items.add(it)}.setOnAction{
                    val title = "Query peer"
                    dialog(title, HBox().apply {
                        padding = Insets(16.0)
                        val action: (String) -> Unit = { text ->
                            scene.window.hide()
                            val loading = minidialog(title, "Querying...")
                            async(60, {ipfs.dht.query(Multihash.fromBase58(text))},
                                { result ->
                                    loading.close()
                                    minidialog(title, result.toString())
                                }, {
                                    loading.close()
                                    minidialog(title, "Could not query peer")
                                }
                            )
                        }
                        val input = TextField("").apply {
                            minWidth = 300.0
                            style = "-fx-background-color: white"
                            promptText = "PeerID (Qm...)"
                            setOnAction {action(text)}
                        }.also { children.add(it) }
                        Button("Apply").apply {
                            cursor = Cursor.HAND
                            style = "-fx-background-color: white"
                            setOnAction {action(input.text)}
                        }.also { children.add(it) }
                        requestFocus()
                    })
                }
            }
        }
        Menu("Info").also{menus.add(it)}.apply {
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
                MenuItem("Private Key").also{items.add(it)}.setOnAction {
                    dialog("Private Key", StackPane().apply {
                        padding = Insets(16.0)
                        TextArea().also{children.add(it)}.apply {
                            style = "-fx-background-color: transparent; -fx-background-insets: 0px"
                            background = Background.EMPTY
                            isWrapText = true
                            isEditable = false
                            text = config.getAsJsonObject("Identity").getAsJsonPrimitive("PrivKey").asString
                        }
                    })
                }
            }
            MenuItem("Peers").also{items.add(it)}.setOnAction{
                val peers = ipfs.swarm.peers()
                listdialog("Peers", "${peers.size} peers", peers.map{"${it.id}: ${it.address}"}, false)
            }
            MenuItem("Others").also{items.add(it)}.setOnAction {
                dialog("Info", VBox().apply {
                    padding = Insets(16.0)
                    minWidth = 400.0
                    TextField().also{children.add(it)}.apply {
                        isEditable = false
                        style = "-fx-background-color: transparent; -fx-background-insets: 0px"
                        text = "go-ipfs version: ${ipfs.version()}"
                    }
                    val addresses = config.getAsJsonObject("Addresses")
                    TextField().also{children.add(it)}.apply {
                        isEditable = false
                        style = "-fx-background-color: transparent; -fx-background-insets: 0px"
                        text = "API address: ${addresses.getAsJsonPrimitive("API").asString}"
                    }
                    TextField().also{children.add(it)}.apply {
                        isEditable = false
                        style = "-fx-background-color: transparent; -fx-background-insets: 0px"
                        text = "Gateway address: ${addresses.getAsJsonPrimitive("Gateway").asString}"
                    }
                    Platform.runLater{requestFocus()}
                })
            }
        }
        Menu("Config").also{menus.add(it)}.apply {
            MenuItem("Bootstrap").also{items.add(it)}.setOnAction {
                dialog("Bootstrap", BorderPane().apply {
                    minHeight = 500.0
                    minWidth = 800.0
                    top = StackPane().apply {
                        padding = Insets(16.0, 16.0, 0.0, 16.0)
                        Label("Edit Bootstrap").also{children.add(it)}.apply {
                            font = Font.font(20.0)
                        }
                    }
                    val area = TextArea().apply {
                        padding = Insets(16.0, 16.0, 16.0, 16.0)
                        style = "-fx-background-color: transparent; -fx-background-insets: 0px"
                        background = Background.EMPTY
                        isWrapText = false
                        text = if(!config.has("Bootstrap")) ""
                            else config.getAsJsonArray("Bootstrap").map{it.asString}.joinToString("\n")
                    }
                    center = area
                    bottom = StackPane().apply {
                        padding = Insets(0.0, 16.0, 16.0, 16.0)
                        Button("Apply").also{children.add(it)}.apply {
                            cursor = Cursor.HAND
                            style = "-fx-background-color: white"
                            translateX = -40.0
                            setOnAction {
                                config{
                                    val list = area.text.split("\n").filterNot{it.isEmpty()}
                                    if(!it.has("Bootstrap")) it.remove("Bootstrap")
                                    it.add("Bootstrap", JsonArray().apply{list.forEach{add(it)}})
                                }
                                scene.window.hide()
                            }
                        }
                        Button("Close").also{children.add(it)}.apply {
                            cursor = Cursor.HAND
                            style = "-fx-background-color: white"
                            translateX = 40.0
                            setOnAction { scene.window.hide() }
                        }
                    }
                })
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
                Menu("HTTP Headers").also{items.add(it)}.apply {
                    MenuItem("Add origin...").also{items.add(it)}.apply {
                        setOnAction {
                            dialog("Add API origin", HBox().apply {
                                padding = Insets(16.0)
                                val action: (String) -> Unit = { origin ->
                                    config {
                                        it.getAsJsonObject("API").getAsJsonObject("HTTPHeaders").apply {
                                            if(!has("Access-Control-Allow-Origin")) {
                                                add("Access-Control-Allow-Origin", JsonArray())
                                                getAsJsonArray("Access-Control-Allow-Origin")
                                                        .add("http://localhost:3000")
                                            }
                                            getAsJsonArray("Access-Control-Allow-Origin").add(origin)
                                        }
                                    }
                                    scene.window.hide()
                                }
                                val input = TextField("").apply {
                                    minWidth = 300.0
                                    style = "-fx-background-color: white"
                                    promptText = "http://..."
                                    setOnAction{action(text)}
                                }.also { children.add(it) }
                                Button("Add").apply {
                                    cursor = Cursor.HAND
                                    style = "-fx-background-color: white"
                                    setOnAction {action(input.text)}
                                }.also { children.add(it) }
                            })
                        }
                    }
                }
            }
            Menu("Reprovider").also{items.add(it)}.apply {
                MenuItem("Interval").also{items.add(it)}.setOnAction {
                    dialog("Reprovider Interval", StackPane().apply {
                        padding = Insets(32.0)
                        Label("Reprovider Interval").also{children.add(it)}.apply{
                            font = Font.font(20.0)
                            translateY = -20.0
                        }
                        HBox().also{children.add(it)}.apply {
                            translateY = 20.0
                            alignment = Pos.CENTER
                            val field = TextField().also { children.add(it) }.apply {
                                maxWidth = 100.0
                                style = "-fx-background-color: white"
                                text = config.getAsJsonObject("Reprovider")
                                        .getAsJsonPrimitive("Interval").asString
                                setOnAction{config{
                                    it.getAsJsonObject("Reprovider").apply {
                                        remove("Interval")
                                        add("Interval", JsonPrimitive(text))
                                    }
                                    scene.window.hide()
                                }}
                            }
                            Button("Apply").also{children.add(it)}.apply {
                                cursor = Cursor.HAND
                                style = "-fx-background-color: white"
                                isDefaultButton = true
                                setOnAction{field.onAction}
                            }
                        }
                    })
                }
                MenuItem("Strategy").also{items.add(it)}.setOnAction {
                    dialog("Reprovider Strategy", StackPane().apply {
                        padding = Insets(32.0)
                        Label("Reprovider Strategy").also{children.add(it)}.apply{
                            font = Font.font(20.0)
                            translateY = -20.0
                        }
                        HBox().also{children.add(it)}.apply {
                            translateY = 20.0
                            alignment = Pos.CENTER
                            val list = FXCollections.observableArrayList("all", "pinned", "roots")
                            val box = ComboBox(list).also { children.add(it) }.apply {
                                maxWidth = 100.0
                                style = "-fx-background-color: white"
                                value = config.getAsJsonObject("Reprovider")
                                        .getAsJsonPrimitive("Strategy").asString
                            }
                            Button("Apply").also{children.add(it)}.apply {
                                cursor = Cursor.HAND
                                style = "-fx-background-color: white"
                                isDefaultButton = true
                                setOnAction{config{
                                    it.getAsJsonObject("Reprovider").apply {
                                        remove("Strategy")
                                        add("Strategy", JsonPrimitive(box.value))
                                    }
                                    scene.window.hide()
                                }}
                            }
                        }
                    })
                }
            }
            Menu("Experimental").also{items.add(it)}.apply {
                CheckMenuItem("Filestore").also{items.add(it)}.apply {
                    isSelected = config.getAsJsonObject("Experimental").run {
                        if(!has("FilestoreEnabled")) false
                        else getAsJsonPrimitive("FilestoreEnabled").asBoolean
                    }
                    setOnAction { config{
                        it.getAsJsonObject("Experimental").apply {
                            remove("FilestoreEnabled")
                            addProperty("FilestoreEnabled", isSelected)
                        }
                    } }
                }
                CheckMenuItem("URL Store").also{items.add(it)}.apply {
                    isSelected = config.getAsJsonObject("Experimental").run {
                        if(!has("UrlstoreEnabled")) false
                        else getAsJsonPrimitive("UrlstoreEnabled").asBoolean
                    }
                    setOnAction { config{
                        it.getAsJsonObject("Experimental").apply {
                            remove("UrlstoreEnabled")
                            addProperty("UrlstoreEnabled", isSelected)
                        }
                    } }
                }
                CheckMenuItem("Sharding").also{items.add(it)}.apply {
                    isSelected = config.getAsJsonObject("Experimental").run {
                        if(!has("ShardingEnabled")) false
                        else getAsJsonPrimitive("ShardingEnabled").asBoolean
                    }
                    setOnAction { config{
                        it.getAsJsonObject("Experimental").apply {
                            remove("ShardingEnabled")
                            addProperty("ShardingEnabled", isSelected)
                        }
                    } }
                }
                CheckMenuItem("Libp2p Stream Mounting").also{items.add(it)}.apply {
                    isSelected = config.getAsJsonObject("Experimental").run {
                        if(!has("Libp2pStreamMounting")) false
                        else getAsJsonPrimitive("Libp2pStreamMounting").asBoolean
                    }
                    setOnAction { config{
                        it.getAsJsonObject("Experimental").apply {
                            remove("Libp2pStreamMounting")
                            addProperty("Libp2pStreamMounting", isSelected)
                        }
                    } }
                }
            }
        }
    }

    fun minidialog(title: String, message: String) = dialog(title, StackPane().apply {
        padding = Insets(16.0)
        minWidth = 300.0
        TextField(message).also{children+=it}
    })

    fun listdialog(title: String, subtitle: String, list: List<String>, writable: Boolean) =
        dialog(title, BorderPane().apply {
            minHeight = 500.0
            minWidth = 800.0
            top = StackPane().apply {
                padding = Insets(16.0, 16.0, 0.0, 16.0)
                Label(subtitle).also{children.add(it)}.apply {
                    font = Font.font(20.0)
                }
            }
            val area = TextArea().apply {
                padding = Insets(16.0, 16.0, 16.0, 16.0)
                style = "-fx-background-color: transparent; -fx-background-insets: 0px"
                background = Background.EMPTY
                isWrapText = false
                isEditable = false
                text = list.joinToString("\n")
            }
            center = area
            bottom = StackPane().apply {
                padding = Insets(0.0, 16.0, 16.0, 16.0)
                Button("Close").also{children.add(it)}.apply {
                    cursor = Cursor.HAND
                    style = "-fx-background-color: white"
                    setOnAction { scene.window.hide() }
                }
            }
        });

    fun ipns(id: String, hash: String) = dialog(id, StackPane().apply {
        val url = "https://ipfs.io/ipfs/QmRyeA1xCjreUgbSCc4QLhYzfRnnPGyfQTMuxpf6kUYXoX/#$hash"
        padding = Insets(32.0)
        minHeight = 350.0
        minWidth = 400.0
        val hint = Label().also{children+=it}.apply {
            translateY = -90.0
            text = "Double-click to open         Right click to copy"
        }
        Label(hash).also{children+=it}.apply {
            translateY = -120.0
            font = Font.font(20.0)
            cursor = Cursor.HAND
            var switch = 1
            fun switch() = when(switch++%4){
                0 -> text = hash
                1 -> text = "http://ipfs.io/ipns/$hash"
                2 -> text = "/ipns/$hash"
                3 -> text = "ipns://$hash"
                else -> {}
            }
            var last: Thread? = null
            setOnMouseClicked { ev -> when(ev.button) {
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
        }
        StackPane().apply {
            translateY = 40.0
            maxHeight = 200.0
            maxWidth = 200.0
            ImageView(qr(url, 200, 200)).apply {
                padding = Insets(0.0)
            }.also { children.add(it) }
        }.also { children.add(it) }
    })

    var log = TextArea()
    fun console(){

        content.setOnDragOver { it.acceptTransferModes(*TransferMode.ANY); }
        content.setOnDragDropped here@{
            val drag = it.dragboard
            if(drag.hasFiles()) open(drag.files);
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

    val List<File>.name
        get() = joinToString(", "){it.name}.run {
            if(length < 20) this else substring(0, 20)+"..."
        }

    val open: (List<File>) -> Unit = content@{ files -> dialog(files.name, StackPane().apply {
        minHeight = 150.0
        minWidth = 300.0
        padding = Insets(16.0)
        val wrapper =
            if(files.size == 1) FileWrapper(files[0])
            else DirWrapper(files.name, files.map{FileWrapper(it)})
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
                var i: List<MerkleNode>? = null
                while(i == null) try {i = ipfs.add(wrapper, true)}
                catch(ex: NullPointerException){}
                i.last().hash.also { ipfs(files.name, it) }
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
                var i: List<MerkleNode>? = null
                while(i == null) try {i = ipfs.add(wrapper, false)}
                catch(ex: NullPointerException){}
                i!!.last().hash.also { ipfs(files.name, it) }
            }
        }.also { children.add(it) }
    })}

    fun ipfs(name: String, hash: Multihash) = dialog(name, StackPane().apply{
        val url = "https://ipfs.io/ipfs/$hash"
        padding = Insets(32.0)
        minHeight = 400.0
        minWidth = 400.0
        val hint = Label("Double-click to open         Right click to copy").apply {
            translateY = -130.0
        }.also { children.add(it) }
        Label("$hash").also{children+=it}.apply {
            cursor = Cursor.HAND
            translateY = -160.0
            font = Font.font(20.0)
            var switch = 1
            fun switch() = when(switch++%4){
                0 -> text = "$hash"
                1 -> text = "http://ipfs.io/ipfs/$hash"
                2 -> text = "/ipfs/$hash"
                3 -> text = "ipfs://$hash"
                else -> {}
            }
            var last: Thread? = null
            setOnMouseClicked { ev ->
                when(ev.button) {
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
                }
            }
            setOnDragDetected {
                ClipboardContent().apply {
                    putString(text)
                    startDragAndDrop(*TransferMode.ANY).setContent(this)
                }
            }
        }
        HBox(5.0).also{children+=it}.apply{
            alignment = Pos.CENTER;
            translateY = -90.0
            isPickOnBounds = false
            Button("Unpin").also{children+=it}.apply {
                cursor = Cursor.HAND
                style = "-fx-background-color: white"
                var pinned = true
                setOnAction {
                    when(pinned){
                        true -> {
                            ipfs.pin.rm(hash);
                            pinned = false
                            text = "Pin"
                        }
                        false -> {
                            ipfs.pin.add(hash);
                            pinned = true
                            text = "Unpin"
                        }
                    }

                }
            }
            Button("Publish to...").also{children+=it}.apply {
                cursor = Cursor.HAND
                style = "-fx-background-color: white"
                setOnAction {
                    dialog(name, StackPane().apply dialog@{
                        minHeight = 150.0
                        minWidth = 400.0
                        val txt = Label("Publish to:").apply {
                            translateY = -40.0
                            padding = Insets(32.0)
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
                                    this@dialog.children.remove(this@hbox)
                                    val id = box.value.split(" ")[0]

                                    txt.apply {
                                        text = "Publishing to $id..."
                                        translateY = -20.0
                                    }
                                    val hint = Label().also{this@dialog.children += it}.apply {
                                        text = "please wait"
                                        translateY = 20.0
                                    }

                                    val task = async(300, {
                                        ipfs.name.publish(hash, Optional.of(id))["Name"]
                                    }, { hash ->
                                        this@dialog.cursor = Cursor.DEFAULT
                                        this@dialog.scene.window.hide()
                                        ipns(id, hash as String);
                                        this@dialog.layout()
                                    }, {
                                        this@dialog.cursor = Cursor.DEFAULT
                                        txt.text = "Error!"
                                    })
                                }
                            }.also { children.add(it) }
                        }.also { children.add(it) }
                    })
                }
            }
            Button("Export to .ipfs").also{children+=it}.apply{
                cursor = Cursor.HAND
                style = "-fx-background-color: white"
                setOnAction {
                    FileChooser().run {
                        extensionFilters+=ExtensionFilter("IPFS file", "*.ipfs")
                        showSaveDialog(window)
                    }?.apply {
                        createNewFile()
                        writeText(hash.toBase58())
                    }
                }
            }
        }
        StackPane().apply {
            translateY = 60.0
            maxHeight = 200.0
            maxWidth = 200.0
            ImageView(qr(url, 200, 200)).apply {
                padding = Insets(0.0)
            }.also { children.add(it) }
        }.also { children.add(it) }
    });

    fun qr(text: String, width: Int, height: Int) =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height, mapOf(EncodeHintType.MARGIN to 0))
            .let{MatrixToImageWriter.toBufferedImage(it, MatrixToImageConfig(0xFF000000.toInt(), 0x00000000))}
            .let{SwingFXUtils.toFXImage(it, null)}

    fun dialog(title: String, pane: Pane) = Stage().apply {
        icons.add(Image(icon))
        this.title = title
        isAlwaysOnTop = true
        initModality(Modality.WINDOW_MODAL)
        scene = Scene(pane).apply { stylesheets.add(stylesheet) }
        show()
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

infix fun String.newerThan(v: String): Boolean = false.also{
    val s1 = split('.');
    val s2 = v.split('.');
    for(i in 0..Math.max(s1.size,s2.size)){
        if(i !in s1.indices) return false; // If there is no digit, v2 is automatically bigger
        if(i !in s2.indices) return true; // if there is no digit, v1 is automatically bigger
        if(s1[i].toInt() > s2[i].toInt()) return true;
        if(s1[i].toInt() < s2[i].toInt()) return false;
    }
}