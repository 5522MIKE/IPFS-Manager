package fr.rhaz.ipfs

import io.ipfs.kotlin.IPFS
import javafx.animation.FadeTransition
import javafx.application.Application
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.image.Image
import javafx.scene.layout.Background
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.WindowEvent
import javafx.util.Duration
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.ActionListener
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    Application.launch(IPFSManager::class.java, *args)
}

class IPFSManager : Application() {

    override fun start(stage: Stage) {
        window = stage
        window.apply(Window).apply { show() }
    }

    lateinit var window: Stage;

    val icon
        get() = IPFSManager::class.java.classLoader.getResourceAsStream("icon.png")

    val stylesheet
        get() = IPFSManager::class.java.classLoader.getResource("style.css").toExternalForm()

    val Window: Stage.() -> Unit = {
        Platform.setImplicitExit(false);
        tray();
        icons.add(Image(icon))
        title = "IPFS Manager"
        initStyle(StageStyle.UNDECORATED);
        minWidth = 600.0
        minHeight = 400.0
        scene = Scene(Body, minWidth, minHeight).apply {
            stylesheets.add(stylesheet)
        }
    }

    fun tray() {

        if(!SystemTray.isSupported()) return;
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
            tray.add(this);
        }

        window.onCloseRequest = EventHandler<WindowEvent> {
            if(!SystemTray.isSupported()) System.exit(0);
            window.hide();
        }
    }

    val Body: BorderPane by lazy {
        BorderPane().apply {
            top = Top
            center = Content
        }
    }

    val Top: HBox by lazy {
        HBox().apply {

            Label("IPFS Manager").apply {
                padding = Insets(16.0, 16.0, 16.0, 16.0)
            }.also { children.add(it); }

            var x = 0 ; var y = 0
            setOnMousePressed {
                x = it.sceneX.toInt();
                y = it.sceneY.toInt();
            }
            setOnMouseDragged {
                window.x = it.screenX - x
                window.y = it.screenY - y
            }

            Label("—").apply {
                translateX = 400.0
                padding = Insets(16.0, 0.0, 16.0, 0.0)
                setOnMouseClicked { window.isIconified = true }
            }.also { children.add(it); }

            Label("X").apply {
                translateX = 430.0
                padding = Insets(16.0, 0.0, 16.0, 0.0)
                setOnMouseClicked { window.hide() }
            }.also { children.add(it); }
        }
    }

    var ipfs: IPFS? = null

    lateinit var status: Label;
    val Content: StackPane by lazy {
        StackPane().apply {

            requestFocus();
            setOnMouseClicked { requestFocus() }

            status = Label("Loading...").apply {
                font = Font.font(30.0);
                translateY = -30.0
            }.also { children.add(it); }

            val callback: () -> Unit = here@{

                val ipfs = ipfs ?: return@here {

                    status.text = "Could not connect"

                    Button("Start Daemon").apply {
                        translateY = 20.0
                        style = "-fx-background-color: white";
                        setOnAction {
                            isVisible = false
                            start()
                        }
                    }.also { children.add(it) }
                }()

                console();
            }

            ipfsd.listeners.onDownloaded.add(Runnable {
                async(3, {ipfs = IPFS().takeIf{it.info.version() != null}}, callback)
            })

            download();


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
                    log?.append("IPFS Manager: Please specify arguments")
                }

                Platform.runLater { log?.append(msg) }
            }
        }
    }

    fun download() = Thread{ipfsd.download()}.apply{start()}
    fun start() = Thread{ipfsd.start(true)}.apply{start()}
    fun process(vararg args: String) = ipfsd.process(*args).also { ipfsd.gobble(it) }

    var log: TextArea? = null;
    fun TextArea.append(msg: String){
        text += "\n$msg"
        layout()
        scrollTop = Double.MAX_VALUE
    }
    fun console(){
        status.text = "Connected"

        FadeTransition(Duration(2000.0), status).apply {
            fromValue = 1.0
            toValue = 0.0
            play();
        }

        val log = TextArea().apply {
            text = "Connected! Type something"
            style = "-fx-background-color: transparent; -fx-background-insets: 0px";
            padding = Insets(0.0, 16.0, 32.0, 16.0)
            isEditable = false
            background = Background.EMPTY
        }.also { Content.children.add(it) }
        this@IPFSManager.log = log;

        TextField().apply {
            translateY = 160.0
            padding = Insets(0.0, 16.0, 16.0, 16.0)
            style = "-fx-background-color: transparent";
            promptText = "name publish Qm..."
            setOnAction {
                text = "".also{_->
                    log.text += "\n>$text"
                    log.layout()
                    log.scrollTop = Double.MAX_VALUE
                    process(*text.split(" ").toTypedArray())
                }
            }
        }.also { Content.children.add(it) }
    }

}

fun async(timeout: Int, runnable: () -> Unit, callback: () -> Unit) = Thread(runnable).apply { tasker(timeout, callback) }

fun Thread.tasker(timeout: Int, callback: () -> Unit) = {
    start()
    val start = System.currentTimeMillis()
    while (this.isAlive) {
        Thread.sleep(1000)
        if (System.currentTimeMillis() - start > (timeout * 1000))
            interrupt()
    }
    Platform.runLater(callback)
}.let { Thread(it).start() }

fun wait(timeout: Long, callback: () -> Unit) = {
    Thread.sleep(timeout)
    Platform.runLater { callback() }
}.let { Thread(it).start() }
