package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SpoofApp
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.SubscriptionDeviceProfiles
import io.nekohasekai.sagernet.group.SubscriptionDeviceSettings
import io.nekohasekai.sagernet.group.decodeSubscriptionDeviceHeaders
import io.nekohasekai.sagernet.group.generateSubscriptionDeviceHeaders
import io.nekohasekai.sagernet.group.isSubscriptionHwidValid
import io.nekohasekai.sagernet.group.normalizeSpoofUserAgent
import io.nekohasekai.sagernet.group.regenerateSubscriptionDeviceSettings
import io.nekohasekai.sagernet.group.shouldWarnAboutMissingSpoofHwid
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onDefaultDispatcher
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.routing.ProviderRoutingSource
import io.nekohasekai.sagernet.routing.RoutingPreviewPayloadStore
import io.nekohasekai.sagernet.routing.SubscriptionRoutingIntervals
import io.nekohasekai.sagernet.routing.SubscriptionRoutingRepository
import io.nekohasekai.sagernet.ui.compose.GroupSettingsScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.showBlockingProgressDialog
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import io.nekohasekai.sagernet.ui.compose.showComposeSingleChoiceDialog
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.utils.HwidGenerator
import org.json.JSONObject

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity : ThemedActivity(),
    OnPreferenceDataStoreChangeListener {
    private var screenRevision by mutableIntStateOf(0)
    private var editorInitialized = false

    fun ProxyGroup.init() {
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector
        DataStore.groupForceUTLS = forceUTLS
        DataStore.groupEnableMux = enableMux
        DataStore.groupMuxType = muxType
        DataStore.groupMuxMode = muxMode
        DataStore.groupMuxConcurrency = muxConcurrency
        DataStore.groupMuxMaxConnections = muxMaxConnections
        DataStore.groupMuxMinStreams = muxMinStreams
        DataStore.groupMuxPadding = muxPadding
        DataStore.groupMuxBrutal = muxBrutal
        DataStore.groupMuxBrutalUpMbps = muxBrutalUpMbps
        DataStore.groupMuxBrutalDownMbps = muxBrutalDownMbps

        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp =
            if (frontProxy >= 0) SELECT_PROFILE else 0
        DataStore.landingProxyTmp =
            if (landingProxy >= 0) SELECT_PROFILE else 0

        val subscription = subscription ?: SubscriptionBean().applyDefaultValues()
        DataStore.subscriptionLink = subscription.link
        DataStore.subscriptionForceResolve = subscription.forceResolve
        DataStore.subscriptionDeduplication = subscription.deduplication
        DataStore.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly
        DataStore.subscriptionUserAgent = if (subscription.deviceHeaders == null) {
            normalizeSpoofUserAgent(subscription.spoofApp ?: SpoofApp.NONE, subscription.customUserAgent)
        } else subscription.customUserAgent
        DataStore.subscriptionAutoUpdate = subscription.autoUpdate
        DataStore.subscriptionAutoUpdateDelay = subscription.autoUpdateDelay
        DataStore.subscriptionFilterMode = subscription.filterMode
        DataStore.subscriptionFilterRegex = subscription.filterRegex
        DataStore.subscriptionHwidEnabled = subscription.hwidEnabled
        DataStore.subscriptionSpoofApp = subscription.spoofApp
        DataStore.subscriptionHwid = subscription.hwid
            ?: HwidGenerator.generate(this@GroupSettingsActivity, DataStore.subscriptionSpoofApp)
        DataStore.subscriptionSpoofDevice = subscription.spoofDevice == true
        DataStore.subscriptionDevicePreset = subscription.devicePresetId.orEmpty()
        DataStore.subscriptionDeviceHeaders = subscription.deviceHeaders
            ?: JSONObject(generateSubscriptionDeviceHeaders(DataStore.subscriptionSpoofApp)).toString()
        DataStore.subscriptionServerDns = subscription.serverDnsResolver ?: ""
        DataStore.subscriptionBannerLayout =
            SubscriptionBannerLayout.toValues(subscription.bannerLayout ?: SubscriptionBannerLayout.ALL)
        DataStore.subscriptionRoutingEnabled = subscription.routingEnabled
        DataStore.subscriptionRoutingInterval =
            SubscriptionRoutingIntervals.normalize(subscription.routingUpdateInterval)
    }

    fun ProxyGroup.serialize() {
        name = DataStore.groupName.takeIf { it.isNotBlank() } ?: "My group"
        type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = DataStore.groupIsSelector
        forceUTLS = DataStore.groupForceUTLS
        enableMux = DataStore.groupEnableMux
        muxType = DataStore.groupMuxType
        muxMode = DataStore.groupMuxMode
        muxConcurrency = DataStore.groupMuxConcurrency
        muxMaxConnections = DataStore.groupMuxMaxConnections
        muxMinStreams = DataStore.groupMuxMinStreams
        muxPadding = DataStore.groupMuxPadding
        muxBrutal = DataStore.groupMuxBrutal
        muxBrutalUpMbps = DataStore.groupMuxBrutalUpMbps
        muxBrutalDownMbps = DataStore.groupMuxBrutalDownMbps

        frontProxy =
            if (DataStore.frontProxyTmp == SELECT_PROFILE) {
                DataStore.frontProxy
            } else {
                -1
            }
        landingProxy =
            if (DataStore.landingProxyTmp == SELECT_PROFILE) {
                DataStore.landingProxy
            } else {
                -1
            }

        val isSubscription = type == GroupType.SUBSCRIPTION
        if (isSubscription) {
            subscription =
                (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                    link = DataStore.subscriptionLink
                    forceResolve = DataStore.subscriptionForceResolve
                    deduplication = DataStore.subscriptionDeduplication
                    updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                    customUserAgent = DataStore.subscriptionUserAgent
                    autoUpdate = DataStore.subscriptionAutoUpdate
                    autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
                    filterMode = DataStore.subscriptionFilterMode
                    filterRegex = DataStore.subscriptionFilterRegex
                    hwidEnabled = DataStore.subscriptionHwidEnabled
                    spoofApp = DataStore.subscriptionSpoofApp
                    hwid = DataStore.subscriptionHwid
                    spoofDevice = DataStore.subscriptionSpoofDevice
                    devicePresetId = DataStore.subscriptionDevicePreset
                    deviceHeaders = DataStore.subscriptionDeviceHeaders
                    serverDnsResolver = DataStore.subscriptionServerDns
                    bannerLayout =
                        SubscriptionBannerLayout.fromValues(DataStore.subscriptionBannerLayout)
                    routingEnabled = DataStore.subscriptionRoutingEnabled
                    routingUpdateInterval =
                        SubscriptionRoutingIntervals.normalize(DataStore.subscriptionRoutingInterval)
                }
        }
    }

    private var isFromClipboard = false

    fun needSave(): Boolean = DataStore.dirty

    private fun sortProfileNameKey(profile: ProxyEntity): String {
        val name = profile.displayName().trim()
        val firstSortableIndex = name.indexOfFirst { it.isLetterOrDigit() }
        return if (firstSortableIndex >= 0) {
            name.substring(firstSortableIndex).trim()
        } else {
            name
        }
    }

    private fun applySubscriptionOriginOrder(
        group: ProxyGroup,
        profiles: List<ProxyEntity>,
    ): List<ProxyEntity> {
        if (group.type != GroupType.SUBSCRIPTION) return profiles
        val originOrderIds = group.originOrderIds()
        if (originOrderIds.isEmpty()) return profiles

        val originIndex = originOrderIds.withIndex().associate { it.value to it.index }
        val originalPosition = profiles.withIndex().associate { it.value.id to it.index }
        return profiles.sortedWith { left, right ->
            val leftOriginIndex = originIndex[left.id]
            val rightOriginIndex = originIndex[right.id]
            when {
                leftOriginIndex != null && rightOriginIndex != null -> {
                    leftOriginIndex.compareTo(rightOriginIndex)
                }

                leftOriginIndex != null -> {
                    -1
                }

                rightOriginIndex != null -> {
                    1
                }

                else -> {
                    originalPosition.getValue(left.id).compareTo(originalPosition.getValue(right.id))
                }
            }
        }
    }

    private fun persistManualOrderFromPreviousMode(group: ProxyGroup) {
        if (group.order == GroupOrder.MANUAL || DataStore.groupOrder != GroupOrder.MANUAL) return

        var profiles = SagerDatabase.proxyDao.getByGroup(group.id)
        profiles =
            when (group.order) {
                GroupOrder.ORIGIN -> {
                    applySubscriptionOriginOrder(group, profiles)
                }

                GroupOrder.BY_NAME -> {
                    val collator =
                        Collator.getInstance(Locale.ROOT).apply {
                            strength = Collator.PRIMARY
                        }
                    profiles.sortedWith { left, right ->
                        val nameCompare = collator.compare(sortProfileNameKey(left), sortProfileNameKey(right))
                        if (nameCompare != 0) nameCompare else left.id.compareTo(right.id)
                    }
                }

                GroupOrder.BY_DELAY -> {
                    profiles.sortedWith(
                        compareBy<ProxyEntity> { if (it.status == 1) it.ping else 114514 }
                            .thenBy { it.id },
                    )
                }

                else -> {
                    profiles
                }
            }

        val changed =
            profiles.mapIndexedNotNull { index, profile ->
                val newOrder = (index + 1).toLong()
                if (profile.userOrder == newOrder) null else profile.apply { userOrder = newOrder }
            }
        if (changed.isNotEmpty()) {
            SagerDatabase.proxyDao.updateProxy(changed)
        }
    }

    private fun importSubscriptionRouting() {
        if (!validateDeviceSettings()) return
        val link = DataStore.subscriptionLink.trim()
        if (link.isBlank()) {
            Toast.makeText(this, R.string.subscription_routing_not_found, Toast.LENGTH_LONG).show()
            return
        }
        val progress = showBlockingProgressDialog(R.string.routing_import_preparing)
        runOnDefaultDispatcher {
            val result = runCatching {
                val storedGroup = DataStore.editingId.takeIf { it > 0L }
                    ?.let(SagerDatabase.groupDao::getById)
                val group = storedGroup ?: ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
                    subscription = SubscriptionBean().applyDefaultValues()
                }
                group.subscription!!.apply {
                    this.link = link
                    customUserAgent = DataStore.subscriptionUserAgent
                    hwidEnabled = DataStore.subscriptionHwidEnabled
                    spoofApp = DataStore.subscriptionSpoofApp
                    hwid = DataStore.subscriptionHwid
                    spoofDevice = DataStore.subscriptionSpoofDevice
                    devicePresetId = DataStore.subscriptionDevicePreset
                    deviceHeaders = DataStore.subscriptionDeviceHeaders
                }
                SubscriptionRoutingRepository.fetchFromSubscription(group)
            }
            onMainDispatcher {
                progress.dismiss()
                result.onSuccess { (source, routing) ->
                    when {
                        source is ProviderRoutingSource.Off -> {
                            Toast.makeText(
                                this@GroupSettingsActivity,
                                R.string.subscription_routing_disabled_by_provider,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        routing == null -> {
                            Toast.makeText(
                                this@GroupSettingsActivity,
                                R.string.subscription_routing_not_found,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        else -> {
                            val token = RoutingPreviewPayloadStore.put(
                                this@GroupSettingsActivity,
                                routing.candidate(),
                            )
                            startActivity(
                                Intent(
                                    this@GroupSettingsActivity,
                                    RoutingImportPreviewActivity::class.java,
                                ).putExtra(RoutingImportPreviewActivity.EXTRA_PAYLOAD_TOKEN, token),
                            )
                        }
                    }
                }.onFailure {
                    showComposeMessageDialog(
                        title = getText(R.string.error_title),
                        message = it.readableMessage,
                    )
                }
            }
        }
    }

    companion object {
        private const val SELECT_PROFILE = 3
        private const val STATE_EDITOR_INITIALIZED = "groupEditorInitialized"
        const val EXTRA_GROUP_ID = "id"
        const val EXTRA_FROM_CLIPBOARD = "fromClipboard"
        const val EXTRA_GROUP_SUBSCRIPTION_LINK = "subscription_link"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeEditor()
        })
        val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
        isFromClipboard = intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)
        val subscriptionLink = intent.getStringExtra(EXTRA_GROUP_SUBSCRIPTION_LINK)
        // The editor cache already contains the draft during activity recreation.
        val restoreDraft = savedInstanceState?.getBoolean(STATE_EDITOR_INITIALIZED) == true &&
            DataStore.editingId == editingId
        DataStore.editingId = editingId
        lifecycleScope.launch {
            val loaded = onDefaultDispatcher {
                if (restoreDraft) {
                    editingId == 0L || SagerDatabase.groupDao.getById(editingId) != null
                } else if (editingId == 0L) {
                    ProxyGroup().init()
                    if (!subscriptionLink.isNullOrEmpty()) {
                        DataStore.groupType = GroupType.SUBSCRIPTION
                        DataStore.subscriptionLink = subscriptionLink
                    }
                    true
                } else {
                    SagerDatabase.groupDao.getById(editingId)?.also { it.init() } != null
                }
            }
            if (!loaded) {
                finish()
                return@launch
            }
            if (!restoreDraft) DataStore.dirty = false
            editorInitialized = true
            DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
            setContent {
                NekoComposeTheme {
                    screenRevision
                    GroupSettingsScreen(
                        canDelete = editingId == 0L || GroupManager.canDelete(editingId),
                        frontProxyName = outboundName(DataStore.frontProxyTmp, DataStore.frontProxy),
                        landingProxyName = outboundName(DataStore.landingProxyTmp, DataStore.landingProxy),
                        onClose = ::closeEditor,
                        onSave = { runOnDefaultDispatcher { saveAndExit() } },
                        onDelete = ::requestDelete,
                        onSelectFrontProxy = { selectProxy(true) },
                        onSelectLandingProxy = { selectProxy(false) },
                        onImportRouting = ::importSubscriptionRouting,
                        onHwidChanged = ::warnIfSpoofNeedsHwid,
                        onSpoofAppChanged = ::applySpoofApp,
                        onSpoofDeviceChanged = ::applySpoofDevice,
                        onDevicePresetChanged = ::applyDevicePreset,
                        validateServerDns = ::validateServerDns,
                        validateAutoUpdateDelay = { it.toIntOrNull()?.let { delay -> delay >= 15 } == true },
                    )
                }
            }
        }
    }

    suspend fun saveAndExit() {
        if (!onMainDispatcher { validateDeviceSettings() }) return
        val editingId = DataStore.editingId
        if (editingId == 0L) {
            val draft = ProxyGroup().apply { serialize() }
            val requestedRouting = draft.subscription?.routingEnabled == true
            if (requestedRouting) draft.subscription?.routingEnabled = false
            val newGroup = GroupManager.createGroup(draft)
            val routingResult =
                if (requestedRouting) downloadRoutingForSave(newGroup, newGroup.id) else Result.success(null)
            if (routingResult.isFailure) {
                DataStore.editingId = newGroup.id
                return
            }
            val routing = routingResult.getOrNull()
            if (requestedRouting && routing == null) {
                newGroup.subscription?.let(SubscriptionRoutingRepository::clearStored)
            }
            if (routing != null) {
                SubscriptionRoutingRepository.store(newGroup.subscription!!, routing)
                GroupManager.updateGroup(newGroup)
            } else if (requestedRouting) {
                GroupManager.updateGroup(newGroup)
                showRoutingNotProvided()
            }
            if (isFromClipboard && newGroup.type == GroupType.SUBSCRIPTION && !newGroup.subscription?.link.isNullOrEmpty()) {
                GroupUpdater.startUpdate(newGroup, true)
            }
        } else if (needSave()) {
            val entity = SagerDatabase.groupDao.getById(editingId)
            if (entity == null) {
                onMainDispatcher { finish() }
                return
            }
            val routingWasEnabled = entity.subscription?.routingEnabled == true
            val keepUserInfo = (
                entity.type == GroupType.SUBSCRIPTION &&
                    DataStore.groupType == GroupType.SUBSCRIPTION &&
                    entity.subscription?.link == DataStore.subscriptionLink
            )
            if (!keepUserInfo) {
                entity.subscription?.apply {
                    subscriptionUserinfo = ""
                    announcement = ""
                    announcementUrl = ""
                    supportUrl = ""
                    supportEmail = ""
                    profileWebPageUrl = ""
                    homepage = ""
                    SubscriptionRoutingRepository.clearStored(this)
                }
                SubscriptionRoutingRepository.deleteFiles(entity.id)
            }
            persistManualOrderFromPreviousMode(entity)
            entity.serialize()
            entity.subscription?.providerAutoUpdateDefaultsApplied = true
            val subscription = entity.subscription
            if (entity.type == GroupType.SUBSCRIPTION && subscription?.routingEnabled == true) {
                val routingResult = downloadRoutingForSave(entity, entity.id)
                if (routingResult.isFailure) return
                val routing = routingResult.getOrNull()
                if (routing == null) {
                    SubscriptionRoutingRepository.clearStored(subscription)
                    SubscriptionRoutingRepository.deleteFiles(entity.id)
                    GroupManager.updateGroup(entity)
                    showRoutingNotProvided()
                } else {
                    SubscriptionRoutingRepository.store(subscription, routing)
                    GroupManager.updateGroup(entity)
                }
            } else {
                if (routingWasEnabled) {
                    subscription?.let(SubscriptionRoutingRepository::clearStored)
                    SubscriptionRoutingRepository.deleteFiles(entity.id)
                }
                GroupManager.updateGroup(entity)
            }
        }

        onMainDispatcher { finish() }
    }

    private suspend fun downloadRoutingForSave(
        group: ProxyGroup,
        groupId: Long,
    ): Result<io.nekohasekai.sagernet.routing.ResolvedSubscriptionRouting?> {
        val dialog = onMainDispatcher {
            showBlockingProgressDialog(R.string.subscription_routing_downloading)
        }
        val result = runCatching {
            val (source, routing) = SubscriptionRoutingRepository.fetchFromSubscription(group)
            routing.takeUnless {
                source is ProviderRoutingSource.Missing || source is ProviderRoutingSource.Off
            }?.also {
                SubscriptionRoutingRepository.prepareAssets(groupId, it)
            }
        }
        onMainDispatcher { dialog.dismiss() }
        result.exceptionOrNull()?.let { showRoutingError(it) }
        return result
    }

    private suspend fun showRoutingNotProvided() {
        onMainDispatcher {
            MessageStore.showMessage(
                this@GroupSettingsActivity,
                R.string.subscription_routing_not_provided,
            )
        }
    }

    private suspend fun showRoutingError(error: Throwable) {
        onMainDispatcher {
            showComposeMessageDialog(
                title = getText(R.string.error_title),
                message = error.readableMessage,
            )
        }
    }

    private fun closeEditor() {
        if (!needSave()) {
            finish()
            return
        }
        showComposeMessageDialog(
            title = getText(R.string.unsaved_changes_prompt),
            positiveButton = getText(R.string.yes),
            negativeButton = getText(R.string.no),
            neutralButton = getText(android.R.string.cancel),
            onPositive = { runOnDefaultDispatcher { saveAndExit() } },
            onNegative = ::finish,
        )
    }

    private fun requestDelete() {
        val id = DataStore.editingId
        if (id == 0L) {
            finish()
            return
        }
        if (!GroupManager.canDelete(id)) {
            Toast.makeText(this, R.string.group_delete_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        fun delete() {
            runOnDefaultDispatcher { GroupManager.deleteGroup(id) }
            finish()
        }
        if (DataStore.confirmProfileDelete) {
            showComposeMessageDialog(
                title = getText(R.string.delete_group_prompt),
                positiveButton = getText(R.string.yes),
                negativeButton = getText(R.string.no),
                onPositive = ::delete,
            )
        } else delete()
    }

    private fun outboundName(mode: Int, profileId: Long): String {
        if (mode == SELECT_PROFILE) {
            return ProfileManager.getProfile(profileId)?.displayName() ?: getString(R.string.none)
        }
        val values = resources.getStringArray(R.array.front_proxy_value)
        val entries = resources.getStringArray(R.array.front_proxy_entry)
        return entries.getOrElse(values.indexOf(mode.toString())) { getString(R.string.none) }
    }

    private fun selectProxy(front: Boolean) {
        val entries = resources.getStringArray(R.array.front_proxy_entry).toList()
        val current = if (front) DataStore.frontProxyTmp else DataStore.landingProxyTmp
        showComposeSingleChoiceDialog(
            title = getText(if (front) R.string.front_proxy else R.string.landing_proxy),
            items = entries,
            selectedIndex = if (current == SELECT_PROFILE) 1 else 0,
            onItemSelected = { index ->
                if (index == 0) {
                    if (front) DataStore.frontProxyTmp = 0 else DataStore.landingProxyTmp = 0
                    screenRevision++
                    return@showComposeSingleChoiceDialog
                }
                val profileId = if (front) DataStore.frontProxy else DataStore.landingProxy
                val intent = Intent(this, ProfileSelectActivity::class.java).apply {
                    ProfileManager.getProfile(profileId)?.let {
                        putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                    }
                }
                if (front) selectProfileForAddFront.launch(intent)
                else selectProfileForAddLanding.launch(intent)
            }
        )
    }

    private fun warnIfSpoofNeedsHwid(enabled: Boolean) {
        if (shouldWarnAboutMissingSpoofHwid(DataStore.subscriptionSpoofApp, enabled)) {
            showComposeMessageDialog(
                title = getText(R.string.spoof_app_without_hwid_title),
                message = getText(R.string.spoof_app_without_hwid_message),
            )
        }
    }

    private fun deviceSettings() = SubscriptionDeviceSettings(
        hwid = DataStore.subscriptionHwid,
        userAgent = DataStore.subscriptionUserAgent,
        spoofDevice = DataStore.subscriptionSpoofDevice,
        devicePresetId = DataStore.subscriptionDevicePreset,
        headers = decodeSubscriptionDeviceHeaders(DataStore.subscriptionDeviceHeaders).orEmpty(),
    )

    private fun applyDeviceSettings(settings: SubscriptionDeviceSettings) {
        DataStore.subscriptionHwid = settings.hwid
        DataStore.subscriptionUserAgent = settings.userAgent
        DataStore.subscriptionSpoofDevice = settings.spoofDevice
        DataStore.subscriptionDevicePreset = settings.devicePresetId
        DataStore.subscriptionDeviceHeaders = JSONObject(settings.headers).toString()
        screenRevision++
    }

    private fun applySpoofApp(spoofApp: Int) {
        applyDeviceSettings(
            regenerateSubscriptionDeviceSettings(
                current = deviceSettings(),
                spoofApp = spoofApp,
                generatedHwid = HwidGenerator.generate(this, spoofApp),
                preserveIdentity = false,
            ),
        )
        warnIfSpoofNeedsHwid(DataStore.subscriptionHwidEnabled)
    }

    private fun applySpoofDevice(enabled: Boolean) {
        applyDevicePreset(if (enabled) null else "")
    }

    private fun applyDevicePreset(id: String?) {
        val spoofApp = DataStore.subscriptionSpoofApp
        val profile = when (id) {
            null -> SubscriptionDeviceProfiles.randomForApp(spoofApp)
            "" -> null
            else -> SubscriptionDeviceProfiles.forApp(spoofApp).firstOrNull { it.id == id }
                ?: return
        }
        applyDeviceSettings(regenerateSubscriptionDeviceSettings(deviceSettings(), spoofApp, profile))
    }

    private fun validateDeviceSettings(): Boolean {
        if (DataStore.groupType != GroupType.SUBSCRIPTION ||
            isSubscriptionHwidValid(DataStore.subscriptionHwidEnabled, DataStore.subscriptionHwid)) {
            return true
        }
        showInvalidProfileDialog(R.string.subscription_hwid_empty)
        return false
    }

    private fun validateServerDns(value: String): Boolean {
        if (isValidServerDns(value)) return true
        Toast.makeText(this, R.string.server_dns_invalid, Toast.LENGTH_LONG).show()
        return false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EDITOR_INITIALIZED, editorInitialized)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(
        store: PreferenceDataStore,
        key: String,
    ) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
            screenRevision++
        }
    }

    private fun showInvalidProfileDialog(messageResId: Int) {
        showComposeMessageDialog(
            title = getText(R.string.invalid_profile),
            message = getText(messageResId),
        )
    }

    val selectProfileForAddFront =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            if (it.resultCode == Activity.RESULT_OK) {
                runOnDefaultDispatcher {
                    val profile =
                        ProfileManager.getProfile(
                            it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0),
                        ) ?: return@runOnDefaultDispatcher
                    if (profile.containsByeDPI() && !profile.startsWithByeDPI()) {
                        onMainDispatcher {
                            showInvalidProfileDialog(R.string.byedpi_front_proxy_error)
                        }
                        return@runOnDefaultDispatcher
                    }
                    DataStore.frontProxy = profile.id
                    onMainDispatcher {
                        DataStore.frontProxyTmp = SELECT_PROFILE
                        screenRevision++
                    }
                }
            }
        }

    val selectProfileForAddLanding =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            if (it.resultCode == Activity.RESULT_OK) {
                runOnDefaultDispatcher {
                    val profile =
                        ProfileManager.getProfile(
                            it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0),
                        ) ?: return@runOnDefaultDispatcher
                    if (profile.containsByeDPI()) {
                        onMainDispatcher {
                            showInvalidProfileDialog(R.string.byedpi_landing_proxy_error)
                        }
                        return@runOnDefaultDispatcher
                    }
                    DataStore.landingProxy = profile.id
                    onMainDispatcher {
                        DataStore.landingProxyTmp = SELECT_PROFILE
                        screenRevision++
                    }
                }
            }
        }
}

private fun isValidServerDns(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty()) return true
    if (value.any { it.isISOControl() || it.isWhitespace() }) return false

    if (value.contains("://")) {
        val scheme = value.substringBefore("://").lowercase()
        if (scheme !in setOf("https", "tls", "quic")) return false
        val rest = value.substringAfter("://")
        val host = rest.substringBefore("/").substringBefore("?")
        val bare = host.substringBeforeLast(":").trim('[', ']')
        return bare.isNotEmpty()
    }

    val host = value.substringBeforeLast(":").trim('[', ']')
    return host.isNotEmpty()
}
