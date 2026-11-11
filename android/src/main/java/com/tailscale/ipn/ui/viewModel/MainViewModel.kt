// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.viewModel

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainViewModelFactory(private val appViewModel: AppViewModel) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
      return MainViewModel(appViewModel) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

@OptIn(FlowPreview::class)
class MainViewModel(private val appViewModel: AppViewModel) : IpnViewModel() {
  // The user readable state of the system
  val stateRes: StateFlow<Int> = MutableStateFlow(userStringRes(State.NoState, State.NoState, true))
  // The expected state of the VPN toggle
  private val _vpnToggleState = MutableStateFlow(false)
  val vpnToggleState: StateFlow<Boolean> = _vpnToggleState
  // Keeps track of whether a toggle operation is in progress. This ensures that toggleVpn cannot be
  // invoked until the current operation is complete.
  var isToggleInProgress = MutableStateFlow(false)
  // Permission to prepare VPN
  private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null
  private val _requestVpnPermission = MutableStateFlow(false)
  val requestVpnPermission: StateFlow<Boolean> = _requestVpnPermission
  // Select Taildrop directory
  private var directoryPickerLauncher: ActivityResultLauncher<Uri?>? = null
  // The list of peers
  private val _peers = MutableStateFlow<List<PeerSet>>(emptyList())
  val peers: StateFlow<List<PeerSet>> = _peers
  // The list of peers
  private val _searchViewPeers = MutableStateFlow<List<PeerSet>>(emptyList())
  val searchViewPeers: StateFlow<List<PeerSet>> = _searchViewPeers
  // The current state of the IPN for determining view visibility
  val ipnState = Notifier.state
  // The active search term for filtering peers
  private val _searchTerm = MutableStateFlow("")
  val searchTerm: StateFlow<String> = _searchTerm
  var autoFocusSearch by mutableStateOf(true)
    private set

  // True if we should render the key expiry bannder
  val showExpiry: StateFlow<Boolean> = MutableStateFlow(false)
  // The peer for which the dropdown menu is currently expanded. Null if no menu is expanded
  var expandedMenuPeer: StateFlow<Tailcfg.Node?> = MutableStateFlow(null)

  var pingViewModel: PingViewModel = PingViewModel()

  val isVpnPrepared: StateFlow<Boolean> = appViewModel.vpnPrepared

  val isVpnActive: StateFlow<Boolean> = appViewModel.vpnActive

  // Device groups for filtering
  private val deviceGroupsViewModel = DeviceGroupsViewModel()
  val groupMembers: StateFlow<Map<String, Set<String>>> = deviceGroupsViewModel.groupMembers

  // The currently selected group filter (null = no filter, show all)
  private val _selectedGroup = MutableStateFlow<String?>(null)
  val selectedGroup: StateFlow<String?> = _selectedGroup

  // Serialises peer recomputation. Netmap updates and search both mutate
  // peerCategorizer and publish into _peers/_searchViewPeers; without this they can
  // interleave and publish a list built from a half-replaced peer set.
  private val peerUpdateMutex = Mutex()

  // Icon displayed in the button to present the health view
  val healthIcon: StateFlow<Int?> = MutableStateFlow(null)

  fun updateSearchTerm(term: String) {
    _searchTerm.value = term
  }

  fun hidePeerDropdownMenu() {
    expandedMenuPeer.set(null)
  }

  fun copyIpAddress(peer: Tailcfg.Node, clipboardManager: ClipboardManager) {
    clipboardManager.setText(AnnotatedString(peer.primaryIPv4Address ?: ""))
  }

  fun startPing(peer: Tailcfg.Node) {
    this.pingViewModel.startPing(peer)
  }

  fun onPingDismissal() {
    this.pingViewModel.handleDismissal()
  }

  // Returns true if we should skip all of the user-interactive permissions prompts
  // (with the exception of the VPN permission prompt)
  fun skipPromptsForAuthKeyLogin(): Boolean {
    val v = MDMSettings.authKey.flow.value.value
    return v != null && v != ""
  }

  private val peerCategorizer = PeerCategorizer()

  init {
    viewModelScope.launch {
      var previousState: State? = null
      combine(Notifier.state, isVpnActive) { state, active -> state to active }
          .collect { (currentState, active) ->
            // Determine the correct state resource string
            stateRes.set(userStringRes(currentState, previousState, active))
            // Determine if the VPN toggle should be on
            val isOn =
                when {
                  active && (currentState == State.Running || currentState == State.Starting) ->
                      true
                  previousState == State.NoState && currentState == State.Starting -> true
                  else -> false
                }
            // Update the VPN toggle state
            _vpnToggleState.value = isOn
            // Update the previous state
            previousState = currentState
          }
    }
    viewModelScope.launch {
      // collectLatest cancels the previous term's work as soon as a newer term arrives.
      // The old searchJob?.cancel() could not do this reliably: the filter body has no
      // suspension points, so a cancelled job ran to completion anyway and still
      // published its result.
      _searchTerm.debounce(250L).collectLatest { term ->
        withContext(Dispatchers.Default) {
          peerUpdateMutex.withLock {
            _searchViewPeers.value = peerCategorizer.groupedAndFilteredPeers(term)
          }
        }
      }
    }
    viewModelScope.launch {
      combine(Notifier.netmap, _selectedGroup) { netmap, selectedGroup -> netmap to selectedGroup }
          .collect { (netmap, selectedGroup) ->
            if (netmap == null) return@collect

            // Awaited rather than launched. The previous code launched this without keeping
            // the Job, so two netmap updates arriving close together raced each other
            // through PeerCategorizer. Notifier.netmap is a StateFlow, so suspending here
            // just conflates intermediate netmaps instead of queueing them.
            withContext(Dispatchers.Default) {
              peerUpdateMutex.withLock {
                peerCategorizer.regenerateGroupedPeers(netmap)
                val allPeerSets = peerCategorizer.peerSets
                val filteredSets =
                    if (selectedGroup != null) {
                      val groupMembers = groupMembers.value[selectedGroup] ?: emptySet()
                      allPeerSets.map { peerSet ->
                        peerSet.copy(peers = peerSet.peers.filter { it.StableID in groupMembers })
                      }
                    } else {
                      allPeerSets
                    }
                _peers.value = filteredSets
                _searchViewPeers.value = peerCategorizer.groupedAndFilteredPeers(_searchTerm.value)
              }
            }

        if (netmap.SelfNode.keyDoesNotExpire) {
          showExpiry.set(false)
        } else {
          val expiryNotificationWindowMDM = MDMSettings.keyExpirationNotice.flow.value.value
          val window =
              expiryNotificationWindowMDM?.let { TimeUtil.duration(it) } ?: Duration.ofHours(24)
          showExpiry.set(
              TimeUtil.isWithinExpiryNotificationWindow(window, netmap.SelfNode.KeyExpiry ?: ""))
        }
      }
    }
    viewModelScope.launch {
      App.get().healthNotifier?.currentIcon?.collect { icon -> healthIcon.set(icon) }
    }
  }

  fun maybeRequestVpnPermission() {
    _requestVpnPermission.value = true
  }

  fun showVPNPermissionLauncherIfUnauthorized() {
    val vpnIntent = VpnService.prepare(App.get())
    TSLog.d("VpnPermissions", "vpnIntent=$vpnIntent")
    if (vpnIntent != null) {
      vpnPermissionLauncher?.launch(vpnIntent)
    } else {
      appViewModel.setVpnPrepared(true)
      startVPN()
    }
    _requestVpnPermission.value = false // reset
  }

  fun toggleVpn(desiredState: Boolean) {
    if (isToggleInProgress.value) {
      // Prevent toggling while a previous toggle is in progress
      return
    }

    viewModelScope.launch {
      isToggleInProgress.value = true
      try {
        val currentState = Notifier.state.value

        if (desiredState) {
          // User wants to turn ON the VPN
          when {
            currentState != Ipn.State.Running -> showVPNPermissionLauncherIfUnauthorized()
          }
        } else {
          // User wants to turn OFF the VPN
          if (currentState == Ipn.State.Running) {
            stopVPN()
          }
        }
      } finally {
        isToggleInProgress.value = false
      }
    }
  }

  fun searchPeers(searchTerm: String) {
    this.searchTerm.set(searchTerm)
  }

  fun enableSearchAutoFocus() {
    autoFocusSearch = true
  }

  fun disableSearchAutoFocus() {
    autoFocusSearch = false
  }

  fun setSelectedGroup(groupName: String?) {
    _selectedGroup.value = groupName
  }

  fun setVpnPermissionLauncher(launcher: ActivityResultLauncher<Intent>) {
    // No intent means we're already authorized
    vpnPermissionLauncher = launcher
  }
}

private fun userStringRes(currentState: State?, previousState: State?, vpnActive: Boolean): Int {
  return when {
    previousState == State.NoState && currentState == State.Starting -> R.string.starting
    currentState == State.NoState -> R.string.placeholder
    currentState == State.InUseOtherUser -> R.string.placeholder
    currentState == State.NeedsLogin ->
        if (vpnActive) R.string.please_login else R.string.connect_to_vpn
    currentState == State.NeedsMachineAuth -> R.string.needs_machine_auth
    currentState == State.Stopped -> R.string.stopped
    currentState == State.Starting -> R.string.starting
    currentState == State.Running -> if (vpnActive) R.string.connected else R.string.placeholder
    else -> R.string.placeholder
  }
}
