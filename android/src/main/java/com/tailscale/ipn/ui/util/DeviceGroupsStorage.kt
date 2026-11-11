// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class DeviceGroupsFile(val groups: Map<String, List<String>> = emptyMap())

object DeviceGroupsStorage {
  const val FILE_NAME = "device_groups.json"

  private val json = Json { ignoreUnknownKeys = true }

  fun load(file: File): Map<String, Set<String>> {
    if (!file.exists()) return emptyMap()
    return try {
      json.decodeFromString<DeviceGroupsFile>(file.readText()).groups.mapValues { it.value.toSet() }
    } catch (e: SerializationException) {
      // Keep the unreadable file for inspection instead of overwriting it on the next save.
      file.renameTo(File(file.path + ".corrupt"))
      emptyMap()
    }
  }

  fun save(file: File, groups: Map<String, Set<String>>) {
    val tmp = File(file.path + ".tmp")
    tmp.writeText(
        json.encodeToString(
            DeviceGroupsFile.serializer(),
            DeviceGroupsFile(groups.mapValues { it.value.toList() })))
    try {
      Files.move(
          tmp.toPath(),
          file.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE)
    } catch (e: IOException) {
      tmp.delete()
      throw e
    }
  }
}
