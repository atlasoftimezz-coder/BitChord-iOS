package com.music.bitchord.auth

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Google account state — the account half of the Android MainViewModel:
 * restore the saved session at launch, validate and store a newly captured
 * one ([onWebSession], same rules as Android), sign out.
 */
class AccountController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = AuthStore(Context.app)

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    /** Bumped whenever the identity behind requests changes, so pages reload. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    init {
        val session = store.activeSession
        if (session != null && store.isSignedIn) {
            val profile = session.profiles.firstOrNull { it.profileId == session.activeProfileId }
            Innertube.cookie = session.cookie
            Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, profile?.authUser)
            _signedIn.value = true
            _account.value = Account(session.name.ifBlank { profile?.name.orEmpty() }, session.email, profile?.avatar)
            scope.launch {
                YtMusicRepository.account().onSuccess { _account.value = it }
                    .onFailure { Log.w(TAG, "could not refresh account: ${it.message}") }
            }
        }
    }

    /** Validates [session] against YouTube Music before keeping any of it. */
    fun onWebSession(session: CapturedSession, mode: WebSessionMode, onComplete: (Boolean) -> Unit) {
        if (!session.loggedIn) {
            onComplete(false)
            return
        }
        val previous = store.activeSession
        scope.launch {
            Innertube.cookie = session.cookie
            Innertube.adoptSessionScope(
                pageId = session.pageId,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser,
                visitorData = session.visitorData,
                clientVersion = session.clientVersion,
                loggedIn = true,
            )
            Innertube.selectChannel(session.pageId, session.dataSyncId, session.authUser)

            val account = withTimeoutOrNull(20_000L) {
                var result = YtMusicRepository.account()
                if (result.isFailure) {
                    delay(750L)
                    result = YtMusicRepository.account()
                }
                result.getOrNull()
            }
            if (account == null) {
                restore(previous)
                onComplete(false)
                return@launch
            }
            val accountId = when (mode) {
                WebSessionMode.SWITCH_CHANNEL -> previous?.accountId ?: sessionId(session.cookie, null)
                WebSessionMode.SIGN_IN -> store.sessions.firstOrNull {
                    account.email.isNotBlank() && it.email.equals(account.email, ignoreCase = true)
                }?.accountId ?: sessionId(session.cookie, null)
            }
            val known = store.sessions.firstOrNull { it.accountId == accountId }
            val profile = YouTubeProfile(
                profileId = profileId(session.pageId, session.dataSyncId, account.name),
                name = account.name,
                handle = account.email,
                avatar = account.thumbnailUrl,
                pageId = session.pageId,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser,
                isBrandAccount = session.pageId != null,
            )
            val hasRealIdentity = profile.pageId != null || profile.dataSyncId != null
            val profiles = known?.profiles.orEmpty().filterNot {
                it.profileId == profile.profileId || (hasRealIdentity && it.profileId.startsWith("profile:"))
            } + profile
            store.upsertSession(
                GoogleAccountSession(
                    accountId = accountId,
                    cookie = session.cookie,
                    name = account.name,
                    email = account.email,
                    profiles = profiles,
                    activeProfileId = profile.profileId,
                ),
            )
            store.cookie = session.cookie
            _account.value = account
            _signedIn.value = true
            StreamResolver.onSessionChanged()
            _generation.value++
            onComplete(true)
        }
    }

    fun signOut() {
        store.signOut()
        Innertube.cookie = null
        Innertube.selectChannel(null, null)
        _account.value = null
        _signedIn.value = false
        StreamResolver.onSessionChanged()
        _generation.value++
    }

    private fun restore(session: GoogleAccountSession?) {
        val profile = session?.profiles?.firstOrNull { it.profileId == session.activeProfileId }
        Innertube.cookie = session?.cookie
        Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, profile?.authUser)
    }

    private companion object {
        const val TAG = "BitChord"
    }
}
