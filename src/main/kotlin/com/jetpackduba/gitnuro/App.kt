@file:OptIn(ExperimentalComposeUiApi::class)

package com.jetpackduba.gitnuro

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jetpackduba.gitnuro.di.DaggerAppComponent
import com.jetpackduba.gitnuro.extensions.preferenceValue
import com.jetpackduba.gitnuro.extensions.toWindowPlacement
import com.jetpackduba.gitnuro.git.AppGpgSigner
import com.jetpackduba.gitnuro.keybindings.KeybindingOption
import com.jetpackduba.gitnuro.keybindings.matchesBinding
import com.jetpackduba.gitnuro.lfs.GLfsFactory
import com.jetpackduba.gitnuro.logging.printError
import com.jetpackduba.gitnuro.managers.AppStateManager
import com.jetpackduba.gitnuro.managers.TempFilesManager
import com.jetpackduba.gitnuro.repositories.AppSettingsRepository
import com.jetpackduba.gitnuro.repositories.ProxySettings
import com.jetpackduba.gitnuro.system.OS
import com.jetpackduba.gitnuro.system.currentOs
import com.jetpackduba.gitnuro.system.systemSeparator
import com.jetpackduba.gitnuro.theme.AppTheme
import com.jetpackduba.gitnuro.theme.Theme
import com.jetpackduba.gitnuro.theme.onBackgroundSecondary
import com.jetpackduba.gitnuro.ui.AppTab
import com.jetpackduba.gitnuro.ui.TabsManager
import com.jetpackduba.gitnuro.ui.components.RepositoriesTabPanel
import com.jetpackduba.gitnuro.ui.components.TabInformation
import com.jetpackduba.gitnuro.ui.context_menu.AppPopupMenu
import com.jetpackduba.gitnuro.ui.dialogs.settings.ProxyType
import kotlinx.coroutines.launch
import org.eclipse.jgit.lfs.BuiltinLFS
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.Signer
import org.eclipse.jgit.lib.SignerFactory
import org.eclipse.jgit.lib.Signers
import org.eclipse.jgit.util.LfsFactory
import java.io.File
import java.io.FileOutputStream
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.nio.file.Paths
import javax.inject.Inject


private const val TAG = "App"


class App {
    private val appComponent = DaggerAppComponent.create()

    @Inject
    lateinit var appStateManager: AppStateManager

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var appEnvInfo: AppEnvInfo

    @Inject
    lateinit var tabsManager: TabsManager

    @Inject
    lateinit var tempFilesManager: TempFilesManager

    @Inject
    lateinit var logging: Logging

    @Inject
    lateinit var signer: AppGpgSigner

    @Inject
    lateinit var gLfsFactory: GLfsFactory

    init {
        appComponent.inject(this)
    }

    @OptIn(ExperimentalFoundationApi::class)
    fun start(args: Array<String>) {
        tabsManager.appComponent = this.appComponent

        initNativeDependencies()
        logging.initLogging()
        initProxySettings()

        Signers.set(GpgConfig.GpgFormat.OPENPGP, signer)

        val windowPlacement = appSettingsRepository.windowPlacement.toWindowPlacement
        val dirToOpen = getDirToOpen(args)

        appEnvInfo.isFlatpak = File("/.flatpak-info").exists()
        appStateManager.loadRepositoriesTabs()

        try {
            if (appSettingsRepository.theme == Theme.CUSTOM) {
                appSettingsRepository.loadCustomTheme()
            }
        } catch (ex: Exception) {
            printError(TAG, "Failed to load custom theme")
            ex.printStackTrace()
        }

        tabsManager.loadPersistedTabs()
        gLfsFactory.register()

        if (dirToOpen != null)
            addDirTab(dirToOpen)

        application {
            var isOpen by remember { mutableStateOf(true) }
            val theme by appSettingsRepository.themeState.collectAsState()
            val customTheme by appSettingsRepository.customThemeFlow.collectAsState()
            val scale by appSettingsRepository.scaleUiFlow.collectAsState()
            val linesHeightType by appSettingsRepository.linesHeightTypeState.collectAsState()

            val windowState = rememberWindowState(
                placement = windowPlacement,
                size = DpSize(1280.dp, 720.dp)
            )

            // Save window state for next time the Window is started
            appSettingsRepository.windowPlacement = windowState.placement.preferenceValue

            if (isOpen) {
                Window(
                    title = System.getenv("title") ?: AppConstants.APP_NAME,
                    onCloseRequest = {
                        isOpen = false
                    },
                    state = windowState,
                    icon = painterResource(AppIcons.LOGO),
                ) {
                    val compositionValues: MutableList<ProvidedValue<*>> =
                        mutableListOf(LocalTextContextMenu provides AppPopupMenu())

                    if (scale != -1f) {
                        compositionValues.add(LocalDensity provides Density(scale, 1f))
                    }

                    CompositionLocalProvider(
                        values = compositionValues.toTypedArray()
                    ) {
                        AppTheme(
                            selectedTheme = theme,
                            customTheme = customTheme,
                            linesHeightType = linesHeightType,
                        ) {
                            Box(modifier = Modifier.background(MaterialTheme.colors.background)) {
                                AppTabs()
                            }
                        }
                    }
                }
            } else {
                tempFilesManager.clearAll()
                appStateManager.cancelCoroutines()
                this.exitApplication()
            }

        }
    }

    private fun initNativeDependencies() {
        val gitnuroRsName = when (currentOs) {
            OS.LINUX -> "libgitnuro_rs.so"
            OS.WINDOWS -> "gitnuro_rs.dll"
            OS.MAC -> "libgitnuro_rs.dylib"
            else -> throw Exception("OS not supported")
        }

        val gitnuroRsInputStream = javaClass.getResourceAsStream("/$gitnuroRsName")

        gitnuroRsInputStream?.use { inputStream ->
            val tempDir = tempFilesManager.tempDir()
            val gitnuroRsFile = File(tempDir, gitnuroRsName)
            val outputStream = FileOutputStream(gitnuroRsFile)

            inputStream.copyTo(outputStream)
            outputStream.flush()
            outputStream.close()

            System.load(gitnuroRsFile.absolutePath)
        } ?: throw Exception("GitnuroRs native dependency not found")
    }

    private fun initProxySettings() {
        appStateManager.appScope.launch {
            appSettingsRepository.proxyFlow.collect { proxySettings ->
                if (proxySettings.useProxy) {
                    when (proxySettings.proxyType) {
                        ProxyType.HTTP -> setHttpProxy(proxySettings)
                        ProxyType.SOCKS -> setSocksProxy(proxySettings)
                    }
                } else {
                    clearProxySettings()
                }
            }
        }
    }

    private fun clearProxySettings() {
        System.setProperty("http.proxyHost", "")
        System.setProperty("http.proxyPort", "")
        System.setProperty("https.proxyHost", "")
        System.setProperty("https.proxyPort", "")
        System.setProperty("socksProxyHost", "")
        System.setProperty("socksProxyPort", "")
    }

    private fun setHttpProxy(proxySettings: ProxySettings) {
        System.setProperty("http.proxyHost", proxySettings.hostName)
        System.setProperty("http.proxyPort", proxySettings.hostPort.toString())
        System.setProperty("https.proxyHost", proxySettings.hostName)
        System.setProperty("https.proxyPort", proxySettings.hostPort.toString())

        if (proxySettings.useAuth) {
            Authenticator.setDefault(
                object : Authenticator() {
                    public override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(proxySettings.hostUser, proxySettings.hostPassword.toCharArray())
                    }
                }
            )

            System.setProperty("http.proxyUser", proxySettings.hostUser)
            System.setProperty("http.proxyPassword", proxySettings.hostPassword)
            System.setProperty("https.proxyUser", proxySettings.hostUser)
            System.setProperty("https.proxyPassword", proxySettings.hostPassword)
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
        }
    }

    private fun setSocksProxy(proxySettings: ProxySettings) {
        System.setProperty("socksProxyHost", proxySettings.hostName)
        System.setProperty("socksProxyPort", proxySettings.hostPort.toString())

        if (proxySettings.useAuth) {
            Authenticator.setDefault(
                object : Authenticator() {
                    public override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(proxySettings.hostUser, proxySettings.hostPassword.toCharArray())
                    }
                }
            )

            System.setProperty("java.net.socks.username", proxySettings.hostUser)
            System.setProperty("java.net.socks.password", proxySettings.hostPassword)
        }
    }

    private fun addDirTab(dirToOpen: File) {
        val absolutePath = dirToOpen.normalize().absolutePath
            .removeSuffix(systemSeparator)
            .removeSuffix("$systemSeparator.git")

        tabsManager.addNewTabFromPath(absolutePath, true)
    }


    @Composable
    fun AppTabs() {
        val tabs by tabsManager.tabsFlow.collectAsState()
        val currentTab = tabsManager.currentTab.collectAsState().value

        if (currentTab != null) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colors.background)
                    .onPreviewKeyEvent {
                        when {
                            it.matchesBinding(KeybindingOption.OPEN_NEW_TAB) -> {
                                tabsManager.addNewEmptyTab()
                                true
                            }

                            it.matchesBinding(KeybindingOption.CLOSE_CURRENT_TAB) -> {
                                tabsManager.closeTab(currentTab)
                                true
                            }

                            it.matchesBinding(KeybindingOption.CHANGE_CURRENT_TAB_LEFT) -> {
                                val tabToSelect = tabs.getOrNull(tabs.indexOf(currentTab) - 1)
                                if (tabToSelect != null) {
                                    tabsManager.selectTab(tabToSelect)
                                }
                                true
                            }

                            it.matchesBinding(KeybindingOption.CHANGE_CURRENT_TAB_RIGHT) -> {
                                val tabToSelect = tabs.getOrNull(tabs.indexOf(currentTab) + 1)
                                if (tabToSelect != null) {
                                    tabsManager.selectTab(tabToSelect)
                                }
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                Tabs(
                    tabsInformationList = tabs,
                    currentTab = currentTab,
                    onAddedTab = {
                        tabsManager.addNewEmptyTab()
                    },
                    onCloseTab = { tab ->
                        tabsManager.closeTab(tab)
                    }
                )

                TabContent(currentTab)
            }
        }
    }

    @Composable
    fun Tabs(
        tabsInformationList: List<TabInformation>,
        currentTab: TabInformation?,
        onAddedTab: () -> Unit,
        onCloseTab: (TabInformation) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RepositoriesTabPanel(
                tabs = tabsInformationList,
                currentTab = currentTab,
                onTabSelected = { selectedTab ->
                    tabsManager.selectTab(selectedTab)
                },
                onTabClosed = onCloseTab,
                onAddNewTab = onAddedTab,
                onMoveTab = { fromIndex, toIndex ->
                    tabsManager.onMoveTab(fromIndex, toIndex)
                },
            )
        }
    }

    private fun getDirToOpen(args: Array<String>): File? {
        if (args.isNotEmpty()) {
            val repoToOpen = args.first()
            val path = Paths.get(repoToOpen)

            val repoDir = if (!path.isAbsolute)
                File(System.getProperty("user.dir"), repoToOpen)
            else
                path.toFile()

            return if (repoDir.isDirectory)
                repoDir
            else
                null
        }

        return null
    }
}

@Composable
private fun TabContent(currentTab: TabInformation?) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colors.background)
            .fillMaxSize(),
    ) {
        if (currentTab != null) {
            AppTab(currentTab.tabViewModel)
        }
    }
}

@Composable
fun LoadingRepository(repoPath: String) {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Opening repository", fontSize = 36.sp, color = MaterialTheme.colors.onBackground)
            Text(repoPath, fontSize = 24.sp, color = MaterialTheme.colors.onBackgroundSecondary)
        }
    }
}
