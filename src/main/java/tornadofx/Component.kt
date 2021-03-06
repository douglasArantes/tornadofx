package tornadofx

import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.event.EventTarget
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.Window
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.logging.Logger
import java.util.prefs.Preferences
import kotlin.concurrent.thread
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.*

interface Injectable

abstract class Component {
    val config: Properties
        get() = _config.value

    val clipboard: Clipboard by lazy { Clipboard.getSystemClipboard() }

    fun Properties.set(pair: Pair<String, Any?>) = set(pair.first, pair.second?.toString())
    fun Properties.string(key: String, defaultValue: String? = null) = config.getProperty(key, defaultValue)
    fun Properties.boolean(key: String) = config.getProperty(key)?.toBoolean() ?: false
    fun Properties.double(key: String) = config.getProperty(key)?.toDouble()
    fun Properties.save() = Files.newOutputStream(configPath.value).use { output -> store(output, "") }

    private val _config = lazy {
        Properties().apply {
            if (Files.exists(configPath.value))
                Files.newInputStream(configPath.value).use { load(it) }
        }
    }

    /**
     * Store and retrieve preferences.
     *
     * Preferences are stored automatically in a OS specific way.
     * <ul>
     *     <li>Windows stores it in the registry at HKEY_CURRENT_USER/Software/JavaSoft/....</li>
     *     <li>Mac OS stores it at ~/Library/Preferences/com.apple.java.util.prefs.plist</li>
     *     <li>Linux stores it at ~/.java</li>
     * </ul>
     */
    fun preferences(nodename: String? = null, op: Preferences.() -> Unit) {
        val node = if (nodename != null) Preferences.userRoot().node(nodename) else Preferences.userNodeForPackage(FX.application.javaClass)
        op(node)
    }

    val properties by lazy { FXCollections.observableHashMap<Any, Any>() }
    val log by lazy { Logger.getLogger(this@Component.javaClass.name) }

    private val configPath = lazy {
        val conf = Paths.get("conf")
        if (!Files.exists(conf))
            Files.createDirectories(conf)
        conf.resolve(javaClass.name + ".properties")
    }

    private val _messages: SimpleObjectProperty<ResourceBundle> = object : SimpleObjectProperty<ResourceBundle>() {
        override fun get(): ResourceBundle? {
            if (super.get() == null) {
                try {
                    val bundle = ResourceBundle.getBundle(this@Component.javaClass.name, FX.locale, FXResourceBundleControl.INSTANCE)
                    if (bundle is FXPropertyResourceBundle)
                        bundle.inheritFromGlobal()
                    set(bundle)
                } catch (ex: Exception) {
                    FX.log.fine({ "No Messages found for ${javaClass.name} in locale ${FX.locale}, using global bundle" })
                    set(FX.messages)
                }
            }
            return super.get()
        }
    }

    var messages: ResourceBundle
        get() = _messages.get()
        set(value) = _messages.set(value)

    val resources: ResourceLookup by lazy {
        ResourceLookup(this)
    }

    inline fun <reified T : Injectable> inject(): ReadOnlyProperty<Component, T> = object : ReadOnlyProperty<Component, T> {
        override fun getValue(thisRef: Component, property: KProperty<*>) = find(T::class)
    }

    inline fun <reified T : Fragment> fragment(): ReadOnlyProperty<Component, T> = object : ReadOnlyProperty<Component, T> {
        var fragment: T? = null

        override fun getValue(thisRef: Component, property: KProperty<*>): T {
            if (fragment == null) fragment = findFragment(T::class)
            return fragment!!
        }
    }

    inline fun <reified T : Any> di(): ReadOnlyProperty<Component, T> = object : ReadOnlyProperty<Component, T> {
        override fun getValue(thisRef: Component, property: KProperty<*>) = FX.dicontainer?.let { it.getInstance(T::class) } ?: throw AssertionError("Injector is not configured, so bean of type ${T::class} can not be resolved")
    }

    val primaryStage: Stage get() = FX.primaryStage

    @Deprecated("Clashes with Region.background, so runAsync is a better name", ReplaceWith("runAsync"), DeprecationLevel.WARNING)
    fun <T> background(func: () -> T) = task(func)

    /**
     * Perform the given operation on an Injectable of the specified type asynchronousyly.
     *
     * MyController::class.runAsync { functionOnMyController() } ui { processResult(it) }
     */
    inline fun <reified T : Injectable, R> KClass<T>.runAsync(noinline op: T.() -> R) = task { op(find(T::class)) }

    /**
     * Perform the given operation on an Injectable class function member asynchronousyly.
     *
     * CustomerController::listContacts.runAsync(customerId).ui { processResult(it) }
     */
    inline fun <reified InjectableType : Injectable, reified ReturnType> KFunction1<InjectableType, ReturnType>.runAsync() = task { invoke(find(InjectableType::class)) }
    /**
     * Perform the given operation on an Injectable class function member asynchronousyly.
     *
     * CustomerController::listCustomers.runAsync().ui { processResult(it) }
     */
    inline fun <reified InjectableType : Injectable, reified P1, reified ReturnType> KFunction2<InjectableType, P1, ReturnType>.runAsync(p1: P1) = task { invoke(find(InjectableType::class), p1) }
    inline fun <reified InjectableType : Injectable, reified P1, reified P2, reified ReturnType> KFunction3<InjectableType, P1, P2, ReturnType>.runAsync(p1: P1, p2: P2) = task { invoke(find(InjectableType::class), p1, p2) }
    inline fun <reified InjectableType : Injectable, reified P1, reified P2, reified P3, reified ReturnType> KFunction4<InjectableType, P1, P2, P3, ReturnType>.runAsync(p1: P1, p2: P2, p3: P3) = task { invoke(find(InjectableType::class), p1, p2, p3) }
    inline fun <reified InjectableType : Injectable, reified P1, reified P2, reified P3, reified P4, reified ReturnType> KFunction5<InjectableType, P1, P2, P3, P4, ReturnType>.runAsync(p1: P1, p2: P2, p3: P3, p4: P4) = task { invoke(find(InjectableType::class), p1, p2, p3, p4) }

    fun <T> runAsync(func: () -> T) = task(func)
    infix fun <T> Task<T>.ui(func: (T) -> Unit) = success(func)
}

abstract class Controller : Component(), Injectable

abstract class UIComponent : Component() {
    var fxmlLoader: FXMLLoader? = null
    var modalStage: Stage? = null
    internal var reloadInit = false
    abstract val root: Parent

    fun init() {
        root.properties["tornadofx.uicomponent"] = this
        root.parentProperty().addListener({ observable, oldParent, newParent ->
            if (modalStage != null) return@addListener
            if (newParent == null && oldParent != null) onUndock()
            if (newParent != null && newParent != oldParent) onDock()
        })
        root.sceneProperty().addListener({ observable, oldParent, newParent ->
            if (modalStage != null) return@addListener
            if (newParent == null && oldParent != null) onUndock()
            if (newParent != null && newParent != oldParent) onDock()
        })
    }

    open fun onUndock() {
    }

    open fun onDock() {
    }

    fun openModal(stageStyle: StageStyle = StageStyle.DECORATED, modality: Modality = Modality.APPLICATION_MODAL, escapeClosesWindow: Boolean = true, owner: Window? = null, block: Boolean = false) {
        if (modalStage == null) {
            if (root !is Parent) {
                throw IllegalArgumentException("Only Parent Fragments can be opened in a Modal")
            } else {
                modalStage = Stage(stageStyle).apply {
                    titleProperty().bind(titleProperty)
                    initModality(modality)
                    if (owner != null) initOwner(owner)

                    if (root.scene != null) {
                        scene = root.scene
                        this@UIComponent.properties["tornadofx.scene"] = root.scene
                    } else {

                        Scene(root).apply {
                            if (escapeClosesWindow) {
                                addEventFilter(KeyEvent.KEY_PRESSED) {
                                    if (it.code == KeyCode.ESCAPE)
                                        closeModal()
                                }
                            }

                            stylesheets.addAll(FX.stylesheets)
                            icons += FX.primaryStage.icons
                            scene = this
                            this@UIComponent.properties["tornadofx.scene"] = this
                        }
                    }

                    if (block) {
                        if (FX.reloadStylesheetsOnFocus || FX.reloadStylesheetsOnFocus) {
                            thread(true) {
                                Thread.sleep(5000)
                                configureReloading()
                            }
                        }
                        showAndWait()
                    } else {
                        show()
                        configureReloading()
                    }
                    hookLayoutDebuggerShortcut()
                }

                modalStage!!.showingProperty().addListener { obs, old, showing ->
                    if (showing) onDock() else onUndock()
                }
            }
        }
    }

    private fun Stage.configureReloading() {
        if (FX.reloadStylesheetsOnFocus) reloadStylesheetsOnFocus()
        if (FX.reloadViewsOnFocus) reloadViewsOnFocus()
    }

    fun closeModal() = modalStage?.apply {
        close()
        modalStage = null
    }

    val titleProperty = SimpleStringProperty()
    var title: String
        get() = titleProperty.get()
        set(value) = titleProperty.set(value)

    fun <T : Node> fxml(location: String? = null): ReadOnlyProperty<UIComponent, T> = object : ReadOnlyProperty<UIComponent, T> {
        val value: T

        init {
            val componentType = this@UIComponent.javaClass
            val targetLocation = location ?: componentType.simpleName + ".fxml"
            val fxml = componentType.getResource(targetLocation) ?:
                    throw IllegalArgumentException("FXML not found for $componentType")

            fxmlLoader = FXMLLoader(fxml).apply {
                resources = this@UIComponent.messages
                setController(this@UIComponent)
            }

            value = fxmlLoader!!.load()
        }

        override fun getValue(thisRef: UIComponent, property: KProperty<*>) = value
    }


    inline fun <reified T : EventTarget> fxid(propName: String? = null) = object : ReadOnlyProperty<UIComponent, T> {
        override fun getValue(thisRef: UIComponent, property: KProperty<*>): T {
            val key = propName ?: property.name
            val value = thisRef.fxmlLoader!!.namespace[key]
            if (value is T) return value
            if (value == null)
                log.warning("Property $key of $thisRef was not resolved because there is no matching fx:id in ${thisRef.fxmlLoader!!.location}")
            else
                log.warning("Property $key of $thisRef did not resolve to the correct type. Check declaration in ${thisRef.fxmlLoader!!.location}")

            throw IllegalArgumentException("Property $key does not match fx:id declaration")
        }
    }

    fun replaceWith(replacement: UIComponent, transition: ((UIComponent, UIComponent, transitionCompleteCallback: () -> Unit) -> Unit)? = null): Boolean {
        if (root.scene != null) {
            if (transition != null) {
                val scene = root.scene
                val temp = StackPane(root, replacement.root)
                scene.root = temp

                transition.invoke(this@UIComponent, replacement, {
                    temp.children.remove(replacement.root)
                    undockFromParent(replacement)
                    scene.root = replacement.root
                })

                return true
            } else {
                undockFromParent(replacement)
                root.scene.root = replacement.root
            }

            return true
        } else if (root.parent is Pane) {
            (root.parent as Pane).apply {
                if (transition != null) {
                    val temp = StackPane(root, replacement.root)

                    children.remove(root)
                    val idx = children.indexOf(root)
                    children.add(idx, temp)

                    transition.invoke(this@UIComponent, replacement, {
                        val index = children.indexOf(temp)
                        temp.children.remove(replacement.root)
                        children.remove(temp)
                        children.add(index, replacement.root)
                    })
                } else {
                    val index = children.indexOf(root)
                    children.remove(root)
                    children.add(index, replacement.root)
                }
                return true
            }
        }

        return false
    }

    private fun undockFromParent(replacement: UIComponent) {
        if (replacement.root.parent is Pane) (replacement.root.parent as Pane).children.remove(replacement.root)
    }

}

abstract class Fragment : UIComponent()

abstract class View : UIComponent(), Injectable

class ResourceLookup(val component: Component) {
    operator fun get(resource: String): String? = component.javaClass.getResource(resource)?.toExternalForm()
    fun url(resource: String): URL? = component.javaClass.getResource(resource)
}