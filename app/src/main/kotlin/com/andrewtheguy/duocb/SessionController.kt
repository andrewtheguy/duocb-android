package com.andrewtheguy.duocb

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central view model: owns the Rust FFI handle, polls its event queue on a
 * timer, and mirrors the desktop app's state machine. Every property is Compose
 * state, read by the screens and mutated only on the main thread.
 *
 * # One handle, three roles, and a hub that runs nothing
 *
 * The hub is **pure local state**. The trusted-device list is read from this
 * app's own storage; nothing is broadcast and nothing is discovered, so no
 * runtime instance exists while the hub is on screen. A handle is created only
 * for a session, and there is at most one at a time:
 *
 * - `connect` — a clipboard session with one device that already trusts this
 *   one's card, and whose card this one holds;
 * - `card_host` / `card_join` — **card setup**, a short-lived session that
 *   exists only to swap identity cards and never carries clipboard content.
 *
 * # Nobody picks a role
 *
 * A clipboard session still has a listening half and a dialing half, but the
 * user is never asked which one this device runs: they pick the *device* they
 * want to share with, and so does the person on the other one. The core
 * decides the split from the two application keys (`DuocbNative.sessionRole`),
 * so the two devices always reach opposite answers with nothing exchanged —
 * and it does not matter which of them is ready first, because both halves
 * simply wait for the other to turn up. [sessionHosting] is that answer, kept
 * only so the session screen can say which device is setting the link up.
 *
 * # Trust is imported, never inferred
 *
 * A card that arrives over card setup is verified as well-formed and correctly
 * signed and nothing more — the PIN is short enough that possession of it must
 * not be enough to become a trusted device. So [incomingCard] is parked for the
 * user to check the pairing code matches the other device's screen, and only
 * [importIncomingCard] writes it to the trusted list.
 *
 * # Persistence commit points
 *
 * The private key is saved to the secret store the moment it is generated or
 * imported; the device name and the freshly minted self-card the moment the
 * name is confirmed; a peer's card the moment its pairing code is confirmed.
 * Editing a field alone never persists. The permanent suffix is minted once on
 * first launch and survives an identity reset.
 *
 * # Android specifics
 *
 * Both mDNS paths in the core run in-process, and Wi-Fi drivers drop multicast
 * an app has not asked for, so a [WifiManager.MulticastLock] is held for the
 * life of every session whose channel uses the local network. On Android 17
 * that traffic also needs a runtime permission, asked for once before the
 * first session on a LAN channel — see [LocalNetworkPermission] and
 * [awaitingLocalNetworkPermission].
 */
class SessionController(context: Context) {
    private val appContext = context.applicationContext
    private val secrets = DuocbSecrets(KeystoreSecretStore(appContext))
    private val configStore = ConfigStore(appContext.filesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val multicastLock: WifiManager.MulticastLock? =
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("duocb")
            ?.apply { setReferenceCounted(false) }

    sealed interface Phase {
        data object Idle : Phase
        data object Starting : Phase

        /** Ready, and the other device is not here yet — either half reports it. */
        data object Waiting : Phase
        data object Resolving : Phase
        data object Connecting : Phase
        data object Authenticating : Phase
        data object Connected : Phase
        data class Reconnecting(val attempt: Int, val max: Int) : Phase
        data class Failed(val message: String) : Phase
    }

    enum class Role(val wire: String) {
        /** Share the clipboard with one chosen trusted device — names the device, not a half. */
        CONNECT("connect"),
        CARD_HOST("card_host"),
        CARD_JOIN("card_join"),
        ;

        /** Card setup never carries clipboard traffic; it trades cards and ends. */
        val isCardSetup: Boolean get() = this != CONNECT

        companion object {
            fun fromWire(wire: String): Role? = entries.firstOrNull { it.wire == wire }
        }
    }

    /**
     * A card handed over by card setup, waiting on the user's pairing-code
     * check. Holding the encoded card alongside the decoded detail means
     * importing stores exactly the bytes that were verified.
     */
    data class IncomingCard(val card: String, val info: IdentityCardInfo)

    /** The last session start, for Reconnect after a failure. */
    data class LastSession(val role: Role, val peerKey: String?, val pin: String?, val ip: String?)

    /**
     * How the host-IP entry is constrained to this device's own subnet (see
     * `DuocbNative.joinIpContext`). [prefix] is the locked network part the
     * user types after (empty when no subnet was detected → free entry),
     * [placeholder] describes the editable tail, [hint] a range hint for a
     * partial-octet subnet, [label] the CIDR for the out-of-range message.
     */
    data class JoinIpContext(val prefix: String, val placeholder: String, val hint: String, val label: String) {
        companion object {
            val EMPTY = JoinIpContext("", "", "", "")
        }
    }

    /** The outcome of validating the host-IP entry against this device's subnet. */
    sealed interface JoinIpOutcome {
        data object Empty : JoinIpOutcome
        data class InRange(val ip: String) : JoinIpOutcome
        data object OutOfRange : JoinIpOutcome
        data object Malformed : JoinIpOutcome
    }

    /**
     * Hooks a **debug build's** E2E autostart installs (see `DebugAutostart` in
     * the debug source set). Nothing in the release source set ever sets
     * [testHooks], so a shipping build never bypasses the pairing-code screen.
     */
    interface TestHooks {
        /** Trust a traded card without the pairing-code screen. */
        val autoTrustIncoming: Boolean

        /** Text to send once connected; taken once. */
        fun takeAutosend(): String?
    }

    var testHooks: TestHooks? = null

    // ---------------------------------------------------------------------
    // Session state

    var phase: Phase by mutableStateOf(Phase.Idle)
        private set
    var nodeId: String? by mutableStateOf(null)
        private set
    var peerNodeId: String? by mutableStateOf(null)
        private set

    /** The display identity of the device this session is with. */
    var sessionPeer: String? by mutableStateOf(null)
        private set

    /**
     * Whether this device drew the hosting half of the running session: the
     * core's answer for a clipboard session, and "shows the PIN" for card
     * setup. Information for the session screen, never a choice.
     */
    var sessionHosting: Boolean by mutableStateOf(false)
        private set

    /** Non-null while the connection-path sheet is up; refreshed by [queryConnPath]. */
    var connPaths: List<ConnPath>? by mutableStateOf(null)

    /** Card host: the current PIN ("XXXX-XXXX"), until a peer pairs. */
    var pinDisplay: String? by mutableStateOf(null)
        private set

    /** Card host: when the displayed PIN rotates away (epoch millis). */
    var pinDeadlineMillis: Long? by mutableStateOf(null)
        private set

    /**
     * Card host: this device's LAN IPv4, shown so the joiner can type it for
     * the manual-IP side channel (null when the LAN channel is off, or before
     * an address is known).
     */
    var hostLanIp: String? by mutableStateOf(null)
        private set

    /** The peer's card, verified but not yet trusted — the confirmation screen. */
    var incomingCard: IncomingCard? by mutableStateOf(null)
        private set

    /** Received items, newest first, capped like the desktop inbox. */
    var inbox: List<ClipItem> by mutableStateOf(emptyList())
        private set

    /** The last successfully sent item. */
    var outbox: ClipItem? by mutableStateOf(null)
        private set

    /** Last error message, shown as a banner; errors are not always fatal. */
    var lastError: String? by mutableStateOf(null)

    /**
     * True while a session start is parked on Android 17's local-network
     * permission prompt (`DuocbRoot` launches the request and reports the
     * answer back to [onLocalNetworkPermissionResult]). At most one prompt per
     * process: the system remembers the answer, and a session that starts
     * without the permission is degraded, not impossible.
     */
    var awaitingLocalNetworkPermission: Boolean by mutableStateOf(false)
        private set

    /** The start held back by that prompt, replayed once the answer is in. */
    private var parkedStart: LastSession? = null

    /** A start asked for mid-teardown, replayed once the old instance is gone. */
    private var queuedStart: LastSession? = null

    /** Whether the prompt has already been answered (or shown) this process. */
    private var localNetworkAsked = false

    // ---------------------------------------------------------------------
    // Standing identity

    /** This installation's application private key (`nsec`), or null before setup. */
    var identitySecret: String? by mutableStateOf(secrets.loadIdentity())
        private set

    /**
     * This device's permanent identity suffix, minted on first launch. null
     * only when the secret store refused the write, which blocks setup — see
     * [DuocbSecrets.loadOrCreateSuffix].
     */
    val suffix: String? = secrets.loadOrCreateSuffix()

    /** The device name, self-card, trusted peer cards and channel choice. */
    var config: DuocbConfig by mutableStateOf(DuocbConfig.EMPTY)
        private set

    /**
     * Non-null when a config file exists that could not be read. Everything in
     * memory is then a default rather than this device's real state, so
     * [persist] refuses to write over the file until the user resolves it.
     */
    var configError: String? by mutableStateOf(null)
        private set

    /** `config.peers`, parsed — the trusted-device rows. */
    var peers: List<TrustedPeer> by mutableStateOf(emptyList())
        private set

    val deviceName: String? get() = config.myName
    val selfCard: String? get() = config.selfCard
    val channel: SignalChannel get() = config.channel

    /** Everything a session needs: a key, a confirmed name, and a self-card. */
    val hasIdentity: Boolean get() = identitySecret != null && config.myName != null && config.selfCard != null

    /** The identity other devices see, e.g. "phone_a7B2c3D4". */
    val displayIdentity: String?
        get() {
            val name = config.myName ?: return null
            val suffix = suffix ?: return null
            return "${name}_$suffix"
        }

    /** This device's key fingerprint — on the hub, and its half of any pairing code. */
    val ownFingerprint: String? get() = identitySecret?.let { identityFingerprint(it) }

    /**
     * The single pairing code the card-setup confirmation screen shows, derived
     * from this device's self-card and the incoming one. Both devices render
     * the identical value; null while no card is pending.
     */
    val incomingPairingCode: String?
        get() {
            val own = config.selfCard ?: return null
            val incoming = incomingCard ?: return null
            return pairingCode(own, incoming.card)
        }

    /** The self-card's decoded detail, for the hub's expiry line. */
    val selfCardInfo: IdentityCardInfo? get() = config.selfCard?.let { IdentityCardInfo.parse(it) }

    // ---------------------------------------------------------------------
    // Session plumbing

    private var handle: Long by mutableStateOf(0L)
    private var currentRole: Role? by mutableStateOf(null)

    /**
     * True while an old runtime instance is still shutting down off-thread;
     * the FFI allows one instance per process, so new starts wait for the
     * teardown completion instead of racing it.
     */
    private var stopping = false
    private var pollJob: Job? = null

    /** The one in-flight send (one outbox slot), promoted to [outbox] on `item_sent`. */
    private var pendingOutbox: String? by mutableStateOf(null)

    var lastSession: LastSession? by mutableStateOf(null)
        private set

    val isSessionActive: Boolean get() = handle != 0L

    /**
     * The role of a session that has been asked for but has no handle yet.
     * Starting a session while another is still winding down is asynchronous:
     * [teardown] clears the handle immediately and [startRuntime] runs only
     * once `stop` has returned off-thread. Without this the app would drop
     * back to the hub for that gap and then jump to the session screen — a
     * flash that reads like the tap did not register.
     */
    private val pendingRole: Role?
        get() = if (handle == 0L && phase == Phase.Starting) lastSession?.role else null

    /** The running session's role, or the one that is about to start. */
    private val activeRole: Role? get() = currentRole ?: pendingRole

    /** Card setup is on screen: the PIN/dialing screen, not the clipboard one. */
    val isCardSetupActive: Boolean get() = activeRole?.isCardSetup == true

    /** A clipboard session is on screen. */
    val isClipboardSessionActive: Boolean get() = activeRole?.isCardSetup == false

    /**
     * One line naming which device is setting the clipboard link up, or null
     * when that is not what is on screen. Said quietly and never as a control.
     */
    val sessionRoleNote: String?
        get() {
            if (!isClipboardSessionActive) return null
            val peer = sessionPeer ?: "the other device"
            return if (sessionHosting) {
                "This device is hosting the link; $peer connects to it."
            } else {
                "$peer is hosting the link; this device connects to it."
            }
        }

    /** One in-flight send at a time, like the desktop outbox. */
    val canSend: Boolean get() = phase == Phase.Connected && pendingOutbox == null

    init {
        when (val outcome = configStore.load()) {
            is ConfigStore.Outcome.Loaded -> config = outcome.config
            ConfigStore.Outcome.Missing -> {} // first launch: the defaults are correct
            // Left at the defaults deliberately, and flagged: the real file
            // stays on disk untouched until the user says to discard it.
            is ConfigStore.Outcome.Unreadable -> configError = outcome.reason
        }
        reloadPeers()
        renewSelfCardIfNeeded()
    }

    /**
     * Give up on a config file that could not be read and start from defaults,
     * overwriting it. The only way out of [configError], and deliberately an
     * explicit user action.
     */
    fun discardUnreadableConfig() {
        configError = null
        config = DuocbConfig.EMPTY
        peers = emptyList()
        persist()
    }

    // ---------------------------------------------------------------------
    // Pure FFI helpers — no network, no storage; views call them freely.

    companion object {
        /** Max retained inbox items (matches desktop MAX_INBOX_ITEMS). */
        private const val MAX_INBOX_ITEMS = 5

        /** The trusted-peer cap the FFI enforces (duocb_core MAX_TRUSTED_PEERS). */
        const val MAX_TRUSTED_PEERS = 128

        private const val POLL_INTERVAL_MS = 300L
        private const val TAG = "duocb"

        fun generateIdentity(): String = DuocbNative.generateIdentity()

        /** null if valid, else the reason. */
        fun validateIdentity(nsec: String): String? = DuocbNative.validateIdentity(nsec)

        fun identityPublicKey(nsec: String): String? = DuocbNative.identityPublicKey(nsec)

        fun identityFingerprint(nsec: String): String? = DuocbNative.identityFingerprint(nsec)

        /** The order-normalized pairing code, or null for an invalid card or a self-pair. */
        fun pairingCode(cardA: String, cardB: String): String? = DuocbNative.pairingCode(cardA, cardB)

        /** null if valid, else the reason (the Rust core's identity::validate_name). */
        fun validateName(name: String): String? = DuocbNative.validateName(name)

        fun displayIdentity(name: String, suffix: String): String = DuocbNative.displayIdentity(name, suffix)

        fun createIdentityCard(nsec: String, name: String, suffix: String): String? =
            DuocbNative.createIdentityCard(nsec, name, suffix)

        /** null if well formed and correctly signed, else the reason. Says nothing about expiry. */
        fun validateIdentityCard(card: String): String? = DuocbNative.validateIdentityCard(card)

        /** Canonical PIN, or null while the entry isn't a valid PIN yet. */
        fun normalizePin(input: String): String? = DuocbNative.normalizePin(input)

        /** Only characters a PIN can contain, uppercased, aliases mapped. */
        fun sanitizePin(input: String): String = DuocbNative.sanitizePinChars(input)

        /** (entered, total) for the "keep typing" hint. */
        fun pinProgress(input: String): Pair<Int, Int> {
            val obj = runCatching { JSONObject(DuocbNative.pinProgress(input)) }.getOrNull() ?: return 0 to 8
            return obj.optInt("entered", 0) to obj.optInt("total", 8)
        }

        fun joinIpContext(): JoinIpContext {
            val obj = runCatching { JSONObject(DuocbNative.joinIpContext()) }.getOrNull() ?: return JoinIpContext.EMPTY
            return JoinIpContext(
                prefix = obj.optString("prefix", ""),
                placeholder = obj.optString("placeholder", ""),
                hint = obj.optString("hint", ""),
                label = obj.optString("label", ""),
            )
        }

        fun resolveJoinIp(entry: String): JoinIpOutcome {
            val obj = runCatching { JSONObject(DuocbNative.resolveJoinIp(entry)) }.getOrNull()
                ?: return JoinIpOutcome.Malformed
            return when (obj.optString("outcome")) {
                "in_range" -> JoinIpOutcome.InRange(obj.optString("ip"))
                "out_of_range" -> JoinIpOutcome.OutOfRange
                "empty" -> JoinIpOutcome.Empty
                else -> JoinIpOutcome.Malformed
            }
        }
    }

    // ---------------------------------------------------------------------
    // Identity mutation (wizard commit points)

    /**
     * Adopt a newly generated or imported private key, persisting it first and
     * only adopting it in memory if that write succeeds — so setup never
     * advances on a secret that did not reach secure storage.
     *
     * A key change invalidates everything keyed to the old one: the self-card
     * was signed by it, and the trusted peers hold *its* public key. Both are
     * cleared, exactly as the desktop's reset does.
     */
    fun setIdentity(nsec: String): Boolean {
        if (!secrets.saveIdentity(nsec)) return false
        identitySecret = nsec
        var next = config.copy(selfCard = null, peers = emptyList())
        // Keep the name as a prefill, but re-mint the card under the new key if
        // one was already confirmed.
        val name = next.myName
        val suffix = suffix
        if (name != null && suffix != null) {
            next = next.copy(selfCard = createIdentityCard(nsec, name, suffix))
        }
        config = next
        reloadPeers()
        persist()
        return true
    }

    /**
     * Persist the confirmed device name and mint the self-card that names it.
     * The name and the card go in together or not at all: a name with no card
     * is an identity that cannot connect or be trusted.
     */
    fun saveName(name: String): Boolean {
        val nsec = identitySecret ?: return false
        val suffix = suffix ?: return false
        val card = createIdentityCard(nsec, name, suffix)
        if (card == null) {
            lastError = "Could not issue this device's identity card"
            return false
        }
        config = config.copy(myName = name, selfCard = card)
        persist()
        return true
    }

    /**
     * Start over with a fresh application identity: a new keypair, no name, no
     * self-card, and an empty trusted list. The permanent suffix survives
     * (desktop parity: `reset_identity` never touches `device_suffix`), and so
     * does the channel — a transport preference, not part of the identity.
     */
    fun resetIdentity() {
        teardown {}
        secrets.clearIdentity()
        identitySecret = null
        config = DuocbConfig.EMPTY.copy(channel = config.channel)
        peers = emptyList()
        lastSession = null
        queuedStart = null
        incomingCard = null
        // A reset is also the way out of an unreadable config: it is the one
        // action whose whole purpose is to discard what was stored.
        configError = null
        persist()
    }

    fun setChannel(channel: SignalChannel) {
        if (config.channel == channel) return
        config = config.copy(channel = channel)
        persist()
    }

    // ---------------------------------------------------------------------
    // Trusted peers

    /** Import a card pasted from another device. null on success, else the reason. */
    fun importPeerCard(pasted: String): String? {
        val trimmed = pasted.trim()
        if (trimmed.isEmpty()) return "paste the card copied from the other device"
        validateIdentityCard(trimmed)?.let { return it }
        val peer = TrustedPeer.from(trimmed) ?: return "invalid identity card"
        val ownKey = identitySecret?.let { identityPublicKey(it) }
        if (ownKey != null && peer.info.npub == ownKey) return "that is this device's own card"
        return store(peer)
    }

    /**
     * Trust the card card setup handed over, after the user checked the
     * pairing code matches the other screen. The only path that turns a
     * verified card into a trusted one.
     */
    fun importIncomingCard() {
        val incoming = incomingCard
        val peer = incoming?.let { TrustedPeer.from(it.card) }
        if (peer == null) {
            dismissIncomingCard()
            return
        }
        // A null pairing code means there was nothing the user could have
        // compared — the peer sent back this device's own card, or no
        // self-card exists. Refused here rather than only by the disabled
        // button, so the debug auto-trust hook can never store a card the
        // human path would refuse. (An expired card is refused by store().)
        if (incomingPairingCode == null) {
            lastError = "no pairing code could be built for that card, so it was not imported"
            dismissIncomingCard()
            return
        }
        store(peer)?.let { lastError = it }
        dismissIncomingCard()
    }

    /** Decline the card and end card setup without trusting anything. */
    fun dismissIncomingCard() {
        incomingCard = null
        phase = Phase.Idle
        teardown {}
    }

    fun removePeer(publicKey: String) {
        val peer = peers.firstOrNull { it.id == publicKey } ?: return
        config = config.copy(peers = config.peers.filter { it != peer.card })
        reloadPeers()
        persist()
    }

    /**
     * Add or replace a peer, keyed on public key: a re-traded card from the
     * same device is a *renewal*, so it replaces the stored copy. The single
     * place trust is granted, so a card that arrived over card setup is held
     * to exactly the same rules as one pasted in by hand. null on success.
     */
    private fun store(peer: TrustedPeer): String? {
        if (peer.info.expired) {
            return "That identity card expired on ${peer.info.expiryDateText} — get a fresh one from the other device"
        }
        val existing = peers.firstOrNull { it.id == peer.id }
        var list = config.peers
        if (existing != null) {
            list = list.filter { it != existing.card }
        } else if (peers.size >= MAX_TRUSTED_PEERS) {
            return "this device already trusts the maximum of $MAX_TRUSTED_PEERS devices"
        }
        config = config.copy(peers = list + peer.card)
        reloadPeers()
        persist()
        return null
    }

    private fun reloadPeers() {
        peers = config.peers.mapNotNull { TrustedPeer.from(it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.info.name })
    }

    /**
     * Write the config, unless an unreadable file is still on disk: what is in
     * memory is then a set of defaults, and saving it would replace a
     * trusted-device list that was never actually read with an empty one.
     */
    private fun persist() {
        configError?.let {
            lastError = "$it. Nothing was saved."
            return
        }
        if (!configStore.save(config)) {
            lastError = "Could not save this device's settings"
        }
    }

    /** Re-mint the self-card once it is inside its renewal window (at launch). */
    private fun renewSelfCardIfNeeded() {
        val nsec = identitySecret ?: return
        val name = config.myName ?: return
        val suffix = suffix ?: return
        val info = config.selfCard?.let { IdentityCardInfo.parse(it) }
        if (info != null && !info.needsRenewal) return
        val card = createIdentityCard(nsec, name, suffix) ?: return
        config = config.copy(selfCard = card)
        persist()
    }

    // ---------------------------------------------------------------------
    // Session lifecycle

    /** Share the clipboard with one trusted device; the other side does the same for this one. */
    fun connect(peer: TrustedPeer) {
        sessionPeer = peer.info.name
        sessionHosting = hosts(peer)
        startSession(Role.CONNECT, peerKey = peer.id)
    }

    /** Whether this device would run the hosting half with [peer]. */
    private fun hosts(peer: TrustedPeer): Boolean {
        val selfCard = selfCard ?: return false
        return DuocbNative.sessionRole(selfCard, peer.card) == 1
    }

    /** Card setup: show a rotating PIN on this device and trade cards. */
    fun startCardHost() {
        sessionHosting = true
        startSession(Role.CARD_HOST, peerKey = null)
    }

    /**
     * Card setup: dial the PIN shown on the other device. [canonical] comes
     * from [normalizePin]. [ip] is the optional host IP for the unicast side
     * channel where multicast is blocked; null browses DNS-SD.
     */
    fun joinCardSetup(canonical: String, ip: String? = null) {
        val trimmed = ip?.trim()?.takeIf { it.isNotEmpty() }
        sessionHosting = false
        startSession(Role.CARD_JOIN, peerKey = null, pin = canonical, ip = trimmed)
    }

    private fun startSession(role: Role, peerKey: String?, pin: String? = null, ip: String? = null) {
        if (stopping) {
            // A previous instance is still winding down off-thread and the FFI
            // allows only one at a time. Queue the ask instead of dropping it:
            // a tap that vanished would read as an unregistered tap. The screen
            // already follows `lastSession` while `phase` is Starting.
            lastError = null
            queuedStart = LastSession(role, peerKey, pin, ip)
            lastSession = queuedStart
            phase = Phase.Starting
            return
        }
        // Android 17 gates every local-network socket behind a runtime
        // permission, so ask before the session starts rather than letting the
        // LAN half fail as if the network were at fault. Only once per
        // process: the answer is the system's to remember, and a denial still
        // leaves the relay paths.
        if (!localNetworkAsked && config.channel.usesLan &&
            !LocalNetworkPermission.isGranted(appContext)
        ) {
            lastError = null
            parkedStart = LastSession(role, peerKey, pin, ip)
            awaitingLocalNetworkPermission = true
            return
        }
        lastError = null
        incomingCard = null
        // Only a clipboard session has a peer to name; a session re-entered by
        // reconnect keeps the value it was given.
        if (role != Role.CONNECT) sessionPeer = null
        lastSession = LastSession(role, peerKey, pin, ip)
        // Instant feedback; the runtime's own status events take over once any
        // previous session has wound down off-thread.
        phase = Phase.Starting
        teardown(clearSessionPeer = false) {
            startRuntime(role, peerKey, pin, ip)
        }
    }

    /**
     * Resume after a failure. A parked session (it ended on its own but the
     * runtime was kept alive — see [fail]) resumes on the same runtime,
     * retaining its node id; when no runtime is left, a new one starts with
     * the persisted identities but empty transient session memory.
     */
    fun reconnect() {
        if (handle != 0L && !stopping && DuocbNative.reconnect(handle) == 0) {
            lastError = null
            phase = Phase.Starting
            return
        }
        val session = lastSession ?: return
        // A card-setup PIN is not replayable: it rotates every 60 seconds and
        // by the time a failure has been read the stored PIN is very likely
        // dead. Back to the entry screen instead.
        if (session.role == Role.CARD_JOIN) {
            lastSession = null
            stop()
            return
        }
        startSession(session.role, session.peerKey, session.pin, session.ip)
    }

    /** Stop the session and return to the hub. */
    fun stop() {
        phase = Phase.Idle
        lastError = null
        incomingCard = null
        parkedStart = null
        queuedStart = null
        teardown {}
    }

    /** Dismiss a failure banner without reconnecting. */
    fun clearFailure() {
        if (phase is Phase.Failed) phase = Phase.Idle
    }

    /** On return to the foreground: catch up on events and detect a dead runtime. */
    fun noteForegrounded() {
        if (handle == 0L) return
        tick()
        checkRuntimeAlive()
    }

    /**
     * The answer to the local-network prompt, from `DuocbRoot`. The parked
     * start goes ahead either way: a denial costs the local paths, not the
     * session — `lan_then_nostr` still reaches the other device over relays,
     * and only `lan_only` is left with nothing to try, which is the one case
     * worth a banner.
     */
    fun onLocalNetworkPermissionResult(granted: Boolean) {
        awaitingLocalNetworkPermission = false
        localNetworkAsked = true
        val parked = parkedStart ?: return
        parkedStart = null
        startSession(parked.role, parked.peerKey, parked.pin, parked.ip)
        if (!granted) {
            lastError = if (config.channel == SignalChannel.LAN_ONLY) {
                "Local network access is off, and this device is set to Local network only. " +
                    "Turn it on in Android's app permissions, or pick another channel in Settings."
            } else {
                "Local network access is off, so this session can only use relay servers. " +
                    "Turn it on in Android's app permissions to use the local network."
            }
        }
    }

    /** Build the role's config and start a runtime instance. */
    private fun startRuntime(role: Role, peerKey: String?, pin: String?, ip: String?): Boolean {
        val selfCard = config.selfCard
        if (selfCard == null) {
            phase = Phase.Failed("Set up this device's identity first")
            return false
        }
        val json = JSONObject()
        json.put("role", role.wire)
        json.put("iroh_secret", secrets.loadOrCreateIrohSecret())
        json.put("self_card", selfCard)
        json.put("channel", config.channel.wire)
        if (role.isCardSetup) {
            // Card setup is identity-less on the wire: the private key and the
            // trusted list have no part in it, and the FFI rejects them outright
            // rather than ignoring them.
            if (role == Role.CARD_JOIN && pin != null) {
                json.put("pin", pin)
                // Only meaningful on a channel that uses the local network; the
                // FFI refuses the combination rather than dropping it silently.
                if (!ip.isNullOrEmpty() && config.channel.usesLan) json.put("ip", ip)
            }
        } else {
            val identitySecret = identitySecret
            if (identitySecret == null) {
                phase = Phase.Failed("Set up this device's identity first")
                return false
            }
            json.put("identity_secret", identitySecret)
            json.put("peers", JSONArray(peers.map { it.card }))
            if (peerKey != null) json.put("peer_public_key", peerKey)
        }

        val out = arrayOfNulls<String>(1)
        val started = DuocbNative.start(json.toString(), out)
        if (started == 0L) {
            phase = Phase.Failed(out[0] ?: "could not start the session")
            return false
        }
        handle = started
        currentRole = role
        if (config.channel.usesLan) acquireMulticastLock()
        pollJob = scope.launch {
            while (isActive) {
                tick()
                delay(POLL_INTERVAL_MS)
            }
        }
        return true
    }

    /**
     * Release the current instance and run [next] once it has actually shut
     * down. `stop` performs a graceful runtime shutdown — normally fast, but up
     * to a few seconds with a live session — so it runs off the main thread.
     */
    private fun teardown(clearSessionPeer: Boolean = true, next: () -> Unit) {
        pollJob?.cancel()
        pollJob = null
        currentRole = null
        nodeId = null
        peerNodeId = null
        pinDisplay = null
        pinDeadlineMillis = null
        hostLanIp = null
        connPaths = null
        pendingOutbox = null
        if (clearSessionPeer) sessionPeer = null
        val h = handle
        if (h == 0L) {
            releaseMulticastLock()
            next()
            return
        }
        handle = 0L
        stopping = true
        scope.launch {
            withContext(Dispatchers.IO) { DuocbNative.stop(h) }
            stopping = false
            releaseMulticastLock()
            // A start asked for while this teardown ran supersedes [next]: it
            // is the newer request, and its own teardown is now a no-op.
            val queued = queuedStart
            if (queued != null) {
                queuedStart = null
                startSession(queued.role, queued.peerKey, queued.pin, queued.ip)
            } else {
                next()
            }
        }
    }

    /**
     * Surface a session failure. A failed session whose runtime is still alive
     * is *parked*, not stopped, so Reconnect can reuse its node id; only a
     * runtime that actually died is torn down here immediately.
     */
    private fun fail(message: String) {
        phase = Phase.Failed(message)
        if (handle == 0L || DuocbNative.isRunning(handle) != 1) {
            teardown(clearSessionPeer = false) {}
        }
    }

    private fun checkRuntimeAlive() {
        if (handle != 0L && DuocbNative.isRunning(handle) == 0) {
            fail(lastError ?: "Session ended")
        }
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: return
        if (!lock.isHeld) {
            runCatching { lock.acquire() }.onFailure { Log.w(TAG, "multicast lock: ${it.message}") }
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }.onFailure { Log.w(TAG, "multicast lock: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------------
    // Commands

    fun send(text: String) {
        if (handle == 0L || !canSend || text.isEmpty()) return
        lastError = null
        pendingOutbox = text
        // 0 = queued, and the outcome then arrives as `item_sent` or `error`.
        // Anything else never reached the runtime, so the outbox slot has to be
        // freed here or sending stays disabled for the rest of the session.
        if (DuocbNative.sendClipboard(handle, text) != 0) {
            pendingOutbox = null
            lastError = "Could not send that item"
        }
    }

    fun queryConnPath() {
        if (handle != 0L) DuocbNative.queryConnPath(handle)
    }

    /** Card host: mint and publish a fresh PIN now; the replacement arrives as `pin_rotated`. */
    fun refreshPin() {
        if (handle != 0L) DuocbNative.refreshPin(handle)
    }

    fun clearInbox() {
        inbox = emptyList()
    }

    fun togglePeek(id: Long) {
        inbox = inbox.map {
            if (it.id == id) it.copy(peekedAt = if (it.expanded) null else System.currentTimeMillis()) else it
        }
    }

    // ---------------------------------------------------------------------
    // Event pump

    private fun tick() {
        drainEvents()
        tickPeeks()
    }

    private fun drainEvents() {
        val h = handle
        if (h == 0L) return
        while (true) {
            // Re-checked every iteration, and it must be: `apply` can tear the
            // session down from inside this loop (a card imported, a fatal
            // `idle`), which clears `handle` and hands it to `stop` on another
            // thread. Comparing identity also catches a handler that started a
            // *new* session.
            if (handle != h) return
            val json = DuocbNative.nextEvent(h) ?: break
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val type = obj.optString("type").takeIf { it.isNotEmpty() } ?: continue
            apply(type, obj)
        }
    }

    /** Collapse peeks that have been open longer than the timeout. */
    private fun tickPeeks() {
        val now = System.currentTimeMillis()
        if (inbox.any { it.peekedAt != null && now - it.peekedAt > ClipItem.PEEK_TIMEOUT_MS }) {
            inbox = inbox.map {
                if (it.peekedAt != null && now - it.peekedAt > ClipItem.PEEK_TIMEOUT_MS) it.copy(peekedAt = null) else it
            }
        }
    }

    private fun apply(type: String, obj: JSONObject) {
        when (type) {
            "server_ready", "client_ready" -> nodeId = obj.optString("node_id").takeIf { it.isNotEmpty() }

            "status" -> when (obj.optString("state")) {
                "starting" -> phase = Phase.Starting
                "waiting" -> phase = Phase.Waiting
                "resolving" -> phase = Phase.Resolving
                "connecting" -> phase = Phase.Connecting
                "authenticating" -> phase = Phase.Authenticating
                "connected" -> {
                    phase = Phase.Connected
                    testHooks?.takeAutosend()?.let { send(it) }
                }
                "reconnecting" -> phase = Phase.Reconnecting(obj.optInt("attempt", 0), obj.optInt("max", 0))
                "idle" -> {
                    // Card setup goes idle the moment the cards have crossed,
                    // which is success, not failure — the confirmation screen is
                    // already up (peer_card_received is guaranteed to arrive
                    // first). Any other idle means the session died; the
                    // preceding error event carries the reason.
                    if (incomingCard != null) phase = Phase.Idle else fail(lastError ?: "Session ended")
                }
            }

            "pin_rotated" -> {
                pinDisplay = obj.optString("pin_display").takeIf { it.isNotEmpty() }
                pinDeadlineMillis = System.currentTimeMillis() + (obj.optDouble("seconds_left", 60.0) * 1000).toLong()
                hostLanIp = obj.optString("host_lan_ip").takeIf { !obj.isNull("host_lan_ip") && it.isNotEmpty() }
            }

            "pin_cleared" -> {
                pinDisplay = null
                pinDeadlineMillis = null
                hostLanIp = null
            }

            "peer_paired" -> {
                peerNodeId = obj.optString("peer_node_id").takeIf { it.isNotEmpty() }
                lastError = null
            }

            "peer_card_received" -> {
                // Verified as well formed and correctly signed — and nothing
                // more. Parking it here rather than storing it is the whole
                // point: only the user's pairing-code comparison can tell this
                // card from an interposer's.
                val card = obj.optString("card").takeIf { it.isNotEmpty() }
                val info = obj.optJSONObject("info")?.let { IdentityCardInfo.decode(it) }
                if (card != null && info != null) {
                    incomingCard = IncomingCard(card, info)
                    // E2E only (debug builds): skip the screen whose entire job
                    // is the human pairing-code check.
                    if (testHooks?.autoTrustIncoming == true) importIncomingCard()
                } else {
                    lastError = "The other device sent a card that could not be read"
                }
            }

            "peer_disconnected" -> {
                peerNodeId = null
                connPaths = null
                pendingOutbox = null
            }

            // Only refresh an open sheet; an unsolicited snapshot shouldn't pop one.
            "conn_path" -> if (connPaths != null) connPaths = ConnPath.parse(obj.optJSONArray("paths"))

            "item_received" -> {
                val text = obj.optString("text")
                if (obj.has("text")) {
                    // pulled=true is a resume re-delivery of the peer's latest
                    // sent item; it may duplicate content received before the
                    // connection dropped — skip it if the inbox already holds it.
                    val pulled = obj.optBoolean("pulled", false)
                    if (!(pulled && inbox.any { it.text == text })) {
                        inbox = (listOf(ClipItem(text)) + inbox).take(MAX_INBOX_ITEMS)
                    }
                }
            }

            "item_sent" -> {
                pendingOutbox?.let { outbox = ClipItem(it) }
                pendingOutbox = null
            }

            "error" -> {
                pendingOutbox = null
                // Never cleared by an event that carries no readable message:
                // the closing `idle` reports lastError as the reason the
                // session died.
                val message = obj.optString("message")
                if (message.isNotEmpty()) {
                    lastError = message
                } else if (lastError == null) {
                    lastError = "The session reported an error"
                }
            }
        }
    }
}
