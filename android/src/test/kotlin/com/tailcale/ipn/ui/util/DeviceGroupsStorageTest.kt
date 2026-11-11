// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceGroupsStorageTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun missingFileLoadsAsNoGroups() {
    assertEquals(emptyMap<String, Set<String>>(), DeviceGroupsStorage.load(file()))
  }

  @Test
  fun savedGroupsRoundTrip() {
    val file = file()
    val groups = mapOf("Office" to setOf("stable-a", "stable-b"), "Mobile" to setOf("stable-a"))

    DeviceGroupsStorage.save(file, groups)

    assertEquals(groups, DeviceGroupsStorage.load(file))
  }

  @Test
  fun saveReplacesPreviousContents() {
    val file = file()
    DeviceGroupsStorage.save(file, mapOf("Office" to setOf("stable-a")))

    DeviceGroupsStorage.save(file, mapOf("Remote" to setOf("stable-z")))

    assertEquals(mapOf("Remote" to setOf("stable-z")), DeviceGroupsStorage.load(file))
    assertFalse(File(file.path + ".tmp").exists())
  }

  @Test
  fun corruptFileIsSetAsideInsteadOfLoaded() {
    val file = file()
    file.writeText("{not json")

    assertEquals(emptyMap<String, Set<String>>(), DeviceGroupsStorage.load(file))

    assertFalse(file.exists())
    assertTrue(File(file.path + ".corrupt").exists())
  }

  private fun file() = File(tmp.root, DeviceGroupsStorage.FILE_NAME)
}
