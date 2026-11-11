// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailcale.ipn.ui.util

import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerCategorizerTest {

  private fun node(
      stableId: String,
      name: String,
      user: Long = 1L,
      online: Boolean? = true,
      addresses: List<String>? = null
  ) =
      Tailcfg.Node(
          ID = stableId.hashCode().toLong(),
          StableID = stableId,
          Name = name,
          User = user,
          ComputedName = name,
          Online = online,
          Addresses = addresses)

  private fun netmap(self: Tailcfg.Node, peers: List<Tailcfg.Node>?) =
      Netmap.NetworkMap(
          SelfNode = self,
          Peers = peers,
          Domain = "example.com",
          UserProfiles =
              mapOf(
                  "1" to
                      Tailcfg.UserProfile(
                          ID = 1L, DisplayName = "Tim", LoginName = "tim@example.com")),
          TKAEnabled = false)

  private fun names(sets: List<PeerSet>) =
      sets.flatMap { it.peers }.map { it.displayName }.sorted()

  @Test
  fun selfNodeIsListedWhenTailnetHasNoOtherPeers() {
    // Regression: `netmap.Peers ?: return` bailed out before SelfNode was grouped, so a
    // tailnet with a single device rendered an empty device list.
    val categorizer = PeerCategorizer()

    categorizer.regenerateGroupedPeers(netmap(node("self", "my-phone"), null))

    assertEquals(listOf("my-phone"), names(categorizer.peerSets))
  }

  @Test
  fun searchResultsRefreshAfterNetmapChanges() {
    // Regression: regenerateGroupedPeers left lastSearchTerm/lastSearchResult intact, so
    // repeating a search term hit the equality fast path and returned the pre-update peer
    // objects. While a search was active the list never picked up joined or removed peers.
    val self = node("self", "my-phone")
    val categorizer = PeerCategorizer()

    categorizer.regenerateGroupedPeers(netmap(self, listOf(node("a", "work-laptop"))))
    assertEquals(listOf("work-laptop"), names(categorizer.groupedAndFilteredPeers("lap")))

    // A second laptop joins while "lap" is still in the search box.
    categorizer.regenerateGroupedPeers(
        netmap(self, listOf(node("a", "work-laptop"), node("b", "home-laptop"))))

    assertEquals(
        listOf("home-laptop", "work-laptop"), names(categorizer.groupedAndFilteredPeers("lap")))
  }

  @Test
  fun offlineTransitionIsVisibleWhileSearchIsActive() {
    // Same stale-cache root cause, but the symptom users actually notice: a peer going
    // offline never updated its indicator until the search box was cleared.
    val self = node("self", "my-phone")
    val categorizer = PeerCategorizer()

    categorizer.regenerateGroupedPeers(netmap(self, listOf(node("a", "work-laptop", online = true))))
    assertEquals(true, categorizer.groupedAndFilteredPeers("lap").single().peers.single().Online)

    categorizer.regenerateGroupedPeers(
        netmap(self, listOf(node("a", "work-laptop", online = false))))
    assertEquals(false, categorizer.groupedAndFilteredPeers("lap").single().peers.single().Online)
  }

  @Test
  fun narrowingSearchTermFiltersFurther() {
    val categorizer = PeerCategorizer()
    categorizer.regenerateGroupedPeers(
        netmap(
            node("self", "my-phone"), listOf(node("a", "work-laptop"), node("b", "work-desktop"))))

    assertEquals(
        listOf("work-desktop", "work-laptop"), names(categorizer.groupedAndFilteredPeers("work")))
    assertEquals(listOf("work-laptop"), names(categorizer.groupedAndFilteredPeers("work-l")))
  }

  @Test
  fun wideningSearchTermReSearchesFullSet() {
    // Backspacing fails the startsWith prefix check, so the filter has to restart from the
    // full peer set instead of the previous, narrower result.
    val categorizer = PeerCategorizer()
    categorizer.regenerateGroupedPeers(
        netmap(
            node("self", "my-phone"), listOf(node("a", "work-laptop"), node("b", "work-desktop"))))

    assertEquals(listOf("work-laptop"), names(categorizer.groupedAndFilteredPeers("work-l")))
    assertEquals(
        listOf("work-desktop", "work-laptop"), names(categorizer.groupedAndFilteredPeers("work")))
  }

  @Test
  fun emptySearchTermReturnsEveryPeer() {
    val categorizer = PeerCategorizer()
    categorizer.regenerateGroupedPeers(
        netmap(node("self", "my-phone"), listOf(node("a", "work-laptop"))))

    assertEquals(
        listOf("my-phone", "work-laptop"), names(categorizer.groupedAndFilteredPeers("")))
  }

  @Test
  fun searchMatchesIpAddress() {
    val categorizer = PeerCategorizer()
    categorizer.regenerateGroupedPeers(
        netmap(
            node("self", "my-phone"),
            listOf(node("a", "work-laptop", addresses = listOf("100.64.1.5/32")))))

    assertEquals(
        listOf("work-laptop"), names(categorizer.groupedAndFilteredPeers("100.64.1.5")))
  }
}
