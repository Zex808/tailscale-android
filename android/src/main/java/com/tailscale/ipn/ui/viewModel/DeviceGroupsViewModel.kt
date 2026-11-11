// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.DeviceGroups
import com.tailscale.ipn.ui.util.DeviceGroupsStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DeviceGroupsViewModel : ViewModel() {
  private val file = File(App.get().filesDir, DeviceGroupsStorage.FILE_NAME)

  // Only touched from the main thread, so mutations don't need a lock.
  private val groups = DeviceGroups(DeviceGroupsStorage.load(file))

  // A single-parallelism dispatcher runs saves in launch order, so an older snapshot
  // can never finish writing after a newer one.
  private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

  private val _groupMembers = MutableStateFlow(groups.snapshot)
  val groupMembers: StateFlow<Map<String, Set<String>>> = _groupMembers

  val peers: StateFlow<List<Tailcfg.Node>> =
      Notifier.netmap
          .map { netmap -> netmap?.Peers.orEmpty().filter { !it.isMullvadNode } }
          .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  fun createGroup(name: String): Boolean {
    val created = groups.create(name)
    if (created) publish()
    return created
  }

  fun deleteGroup(name: String) {
    groups.delete(name)
    publish()
  }

  fun setMember(group: String, stableId: String, isMember: Boolean) {
    if (isMember) groups.assign(group, stableId) else groups.unassign(group, stableId)
    publish()
  }

  private fun publish() {
    val snapshot = groups.snapshot
    _groupMembers.value = snapshot
    viewModelScope.launch(saveDispatcher) { DeviceGroupsStorage.save(file, snapshot) }
  }
}
