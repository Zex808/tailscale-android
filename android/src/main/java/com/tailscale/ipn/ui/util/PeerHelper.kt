// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.ui.util.fastAny
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.UserID

data class PeerSet(
    val userID: UserID,
    val user: Tailcfg.UserProfile?,
    val peers: List<Tailcfg.Node>
)

class PeerCategorizer {
  // Guards every mutable field below. regenerateGroupedPeers() is driven by netmap
  // notifications and groupedAndFilteredPeers() by the search box; both run on
  // Dispatchers.Default, which is a multi-threaded pool. Without this lock the two
  // could interleave and leave lastSearchTerm paired with another term's results.
  private val lock = Any()

  private var _peerSets: List<PeerSet> = emptyList()
  private var lastSearchResult: List<PeerSet> = emptyList()
  private var lastSearchTerm: String = ""

  // Bumped on every netmap regeneration. A search that started before the bump must
  // not publish into the cache, because it filtered Node objects that are no longer
  // current.
  private var generation: Long = 0L

  val peerSets: List<PeerSet>
    get() = synchronized(lock) { _peerSets }

  fun regenerateGroupedPeers(netmap: Netmap.NetworkMap) {
    // Computed outside the lock: this is the expensive part (group + sort) and it
    // only reads the netmap it was handed.
    val newPeerSets = computePeerSets(netmap)

    synchronized(lock) {
      _peerSets = newPeerSets
      generation++
      // The netmap just changed, so any cached search result is stale. Without this
      // reset the "searchTerm == lastSearchTerm" fast path in groupedAndFilteredPeers
      // kept handing back the pre-update list for as long as a search was active, so
      // peers never appeared, disappeared, or changed online state while filtering.
      lastSearchTerm = ""
      lastSearchResult = emptyList()
    }
  }

  private fun computePeerSets(netmap: Netmap.NetworkMap): List<PeerSet> {
    // Peers is null (not empty) for a tailnet with no other devices. The previous
    // `?: return` bailed out before SelfNode was added, so a solo tailnet rendered an
    // empty device list instead of showing your own device.
    val peers: List<Tailcfg.Node> = netmap.Peers ?: emptyList()
    val selfNode = netmap.SelfNode
    val grouped = mutableMapOf<UserID, MutableList<Tailcfg.Node>>()

    val mdm = MDMSettings.hiddenNetworkDevices.flow.value.value
    val hideMyDevices = mdm?.contains("current-user") ?: false
    val hideOtherDevices = mdm?.contains("other-users") ?: false
    val hideTaggedDevices = mdm?.contains("tagged-devices") ?: false

    val me = netmap.currentUserProfile()

    // asSequence() avoids copying the whole peer list just to append SelfNode, which
    // happened on every netmap notification.
    for (peer in peers.asSequence() + selfNode) {

      val userId = peer.User
      val profile = netmap.userProfile(userId)

      // Mullvad nodes should not be shown in the peer list
      if (peer.isMullvadNode) {
        continue
      }

      // Hide devices based on MDM settings
      if (hideMyDevices && userId == me?.ID) {
        continue
      }

      if (hideOtherDevices && userId != me?.ID) {
        continue
      }

      if (hideTaggedDevices && (profile?.isTaggedDevice() == true)) {
        continue
      }

      if (!grouped.containsKey(userId)) {
        grouped[userId] = mutableListOf()
      }
      grouped[userId]?.add(peer)
    }

    return grouped
        .map { (userId, peers) ->
          val profile = netmap.userProfile(userId)
          PeerSet(
              userId,
              profile,
              peers.sortedWith { a, b ->
                when {
                  a.StableID == b.StableID -> 0
                  a.isSelfNode(netmap) -> -1
                  b.isSelfNode(netmap) -> 1
                  else ->
                      (a.ComputedName?.lowercase() ?: "").compareTo(
                          b.ComputedName?.lowercase() ?: "")
                }
              })
        }
        .sortedBy {
          if (it.user?.ID == me?.ID) {
            ""
          } else {
            it.user?.DisplayName?.lowercase() ?: "unknown user"
          }
        }
  }

  fun groupedAndFilteredPeers(searchTerm: String = ""): List<PeerSet> {
    if (searchTerm.isEmpty()) {
      return peerSets
    }

    val setsToSearch: List<PeerSet>
    val startGeneration: Long
    synchronized(lock) {
      if (searchTerm == lastSearchTerm) {
        return lastSearchResult
      }
      startGeneration = generation
      // Typing forward can only narrow the result, so re-filter the previous (smaller)
      // result rather than the full set. Backspacing fails startsWith and correctly
      // falls back to the full set.
      setsToSearch =
          if (lastSearchTerm.isNotEmpty() && searchTerm.startsWith(lastSearchTerm)) lastSearchResult
          else _peerSets
    }

    // Filtering happens outside the lock so a slow search cannot block a netmap update.
    val matchingSets = filterPeerSets(setsToSearch, searchTerm)

    synchronized(lock) {
      // Publish only if no netmap update landed while we were filtering. Otherwise this
      // result describes peers that are already stale and must not become the cache.
      // Both fields are written under one lock acquisition so a reader can never see
      // one term's key next to another term's value.
      if (generation == startGeneration) {
        lastSearchTerm = searchTerm
        lastSearchResult = matchingSets
      }
    }
    return matchingSets
  }

  private fun filterPeerSets(sets: List<PeerSet>, searchTerm: String): List<PeerSet> =
      sets.mapNotNull { peerSet ->
        val user = peerSet.user

        // A matching user name keeps that user's whole set, so narrowing a later
        // keystroke still has every one of their peers available to filter.
        if (user?.DisplayName?.contains(searchTerm, ignoreCase = true) == true) {
          return@mapNotNull peerSet
        }

        val matchingPeers =
            peerSet.peers.filter {
              it.displayName.contains(searchTerm, ignoreCase = true) ||
                  (it.Addresses ?: emptyList()).fastAny { addr -> addr.contains(searchTerm) }
            }
        if (matchingPeers.isNotEmpty()) PeerSet(peerSet.userID, user, matchingPeers) else null
      }
}
