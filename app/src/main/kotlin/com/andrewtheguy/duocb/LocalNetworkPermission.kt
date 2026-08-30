package com.andrewtheguy.duocb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17's local-network gate.
 *
 * For an app targeting SDK 37 every socket that touches a local address needs
 * `ACCESS_LOCAL_NETWORK`: both mDNS paths in the core (the DNS-SD responder
 * carrying the rendezvous records and iroh's own address lookup), the unicast
 * side channel behind the manual host IP, and a direct LAN path once one is
 * found. It is a runtime permission, so it is asked for — see
 * [SessionController.awaitingLocalNetworkPermission].
 *
 * Below API 37 the permission does not exist: `INTERNET` grants local access
 * implicitly, and `checkSelfPermission` answers "denied" for a permission the
 * platform has never heard of. So the API check comes first, always.
 *
 * A denial is not fatal. Relay signalling and relayed transport are not local
 * traffic, so `nostr_only` never needs this and `lan_then_nostr` still
 * connects over its fallback; only `lan_only` has nothing left to try.
 */
object LocalNetworkPermission {
    /**
     * `Manifest.permission.ACCESS_LOCAL_NETWORK`, spelled out because this
     * name is compiled against every API level down to [minSdk 29][Build].
     */
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Whether this device gates local traffic at all. */
    val exists: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN

    /** Whether local-network traffic is allowed — true on every device that does not gate it. */
    fun isGranted(context: Context): Boolean =
        !exists || context.checkSelfPermission(NAME) == PackageManager.PERMISSION_GRANTED
}
