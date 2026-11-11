// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGroupsTest {

  @Test
  fun createRejectsBlankAndDuplicateNames() {
    val groups = DeviceGroups()

    assertTrue(groups.create("Office"))
    assertFalse(groups.create("  "))
    assertFalse(groups.create("Office"))
    assertFalse(groups.create(" Office "))
    assertEquals(listOf("Office"), groups.names)
  }

  @Test
  fun assignedPeerIsReportedAsMemberAndGroupMembership() {
    val groups = DeviceGroups()
    groups.create("Office")
    groups.assign("Office", "stable-a")

    assertEquals(setOf("stable-a"), groups.members("Office"))
    assertEquals(listOf("Office"), groups.groupsOf("stable-a"))
    assertEquals(emptyList<String>(), groups.groupsOf("stable-b"))
  }

  @Test
  fun peerCanBelongToSeveralGroups() {
    val groups = DeviceGroups()
    groups.create("Office")
    groups.create("Mobile")
    groups.assign("Office", "stable-a")
    groups.assign("Mobile", "stable-a")

    assertEquals(listOf("Office", "Mobile"), groups.groupsOf("stable-a"))
  }

  @Test
  fun unassignRemovesOnlyThatMembership() {
    val groups = DeviceGroups()
    groups.create("Office")
    groups.create("Mobile")
    groups.assign("Office", "stable-a")
    groups.assign("Mobile", "stable-a")

    groups.unassign("Office", "stable-a")

    assertEquals(listOf("Mobile"), groups.groupsOf("stable-a"))
  }

  @Test
  fun deleteGroupDropsItsMemberships() {
    val groups = DeviceGroups()
    groups.create("Office")
    groups.assign("Office", "stable-a")

    groups.delete("Office")

    assertEquals(emptyList<String>(), groups.names)
    assertEquals(emptyList<String>(), groups.groupsOf("stable-a"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun assignToUnknownGroupFails() {
    DeviceGroups().assign("Ghost", "stable-a")
  }
}
