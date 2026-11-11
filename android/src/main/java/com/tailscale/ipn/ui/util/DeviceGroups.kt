// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

// Local, user-defined groupings of peers (e.g. "Office", "Mobile"). Peers are keyed by
// StableID so a group survives netmap updates that reorder or re-id nodes.
class DeviceGroups {
  private val membersByGroup = linkedMapOf<String, MutableSet<String>>()

  val names: List<String>
    get() = membersByGroup.keys.toList()

  fun create(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || membersByGroup.containsKey(trimmed)) return false
    membersByGroup[trimmed] = mutableSetOf()
    return true
  }

  fun delete(name: String) {
    membersByGroup.remove(name)
  }

  fun assign(group: String, stableId: String) {
    requireNotNull(membersByGroup[group]) { "Unknown group: $group" }.add(stableId)
  }

  fun unassign(group: String, stableId: String) {
    membersByGroup[group]?.remove(stableId)
  }

  fun members(group: String): Set<String> = membersByGroup[group]?.toSet() ?: emptySet()

  fun groupsOf(stableId: String): List<String> =
      membersByGroup.filterValues { stableId in it }.keys.toList()
}
