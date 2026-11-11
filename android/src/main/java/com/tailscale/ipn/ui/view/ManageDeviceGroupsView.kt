// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.DeviceGroupsViewModel

@Composable
fun ManageDeviceGroupsView(
    backToSettings: BackNavigation,
    model: DeviceGroupsViewModel = viewModel()
) {
  val groupMembers by model.groupMembers.collectAsState()
  val peers by model.peers.collectAsState()
  var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
  var isPresentingNewGroupDialog by remember { mutableStateOf(false) }

  // The selection can outlive its group if the group is deleted, so check it still exists.
  val activeGroup = selectedGroup?.takeIf { it in groupMembers }
  val activeMembers = activeGroup?.let { groupMembers[it] }.orEmpty()

  Scaffold(topBar = { Header(R.string.device_groups, onBack = backToSettings) }) { innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      item("groupsHeader") { Lists.MutedHeader(stringResource(R.string.device_groups_header)) }

      if (groupMembers.isEmpty()) {
        item("noGroups") {
          ListItem(headlineContent = { Text(stringResource(R.string.no_device_groups)) })
        }
      }

      itemsWithDividers(groupMembers.keys.toList(), key = { it }) { name ->
        ListItem(
            headlineContent = { Text(name) },
            supportingContent = {
              Text(
                  stringResource(R.string.device_group_member_count, groupMembers[name]?.size ?: 0))
            },
            trailingContent = {
              IconButton(
                  onClick = {
                    model.deleteGroup(name)
                    if (selectedGroup == name) selectedGroup = null
                  }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.delete_device_group))
                  }
            },
            modifier = Modifier.clickable { selectedGroup = name })
      }

      item("addGroup") {
        Lists.ItemDivider()
        ListItem(
            headlineContent = {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.new_device_group))
              }
            },
            modifier = Modifier.clickable { isPresentingNewGroupDialog = true })
      }

      if (activeGroup != null) {
        item("membersHeader") {
          Lists.SectionDivider()
          Lists.MutedHeader(stringResource(R.string.device_group_members, activeGroup))
        }
        itemsWithDividers(peers, key = { it.StableID }) { peer ->
          val isMember = peer.StableID in activeMembers
          ListItem(
              headlineContent = { Text(peer.displayName) },
              trailingContent = {
                Checkbox(
                    checked = isMember,
                    onCheckedChange = { model.setMember(activeGroup, peer.StableID, it) })
              },
              modifier =
                  Modifier.clickable { model.setMember(activeGroup, peer.StableID, !isMember) })
        }
      }
    }
  }

  if (isPresentingNewGroupDialog) {
    NewDeviceGroupDialog(
        onDismiss = { isPresentingNewGroupDialog = false },
        onCreate = { name ->
          val created = model.createGroup(name)
          if (created) selectedGroup = name.trim()
          created
        })
  }
}

@Composable
private fun NewDeviceGroupDialog(onDismiss: () -> Unit, onCreate: (String) -> Boolean) {
  var name by remember { mutableStateOf("") }
  var isInvalid by remember { mutableStateOf(false) }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.new_device_group)) },
      text = {
        OutlinedTextField(
            value = name,
            onValueChange = {
              name = it
              isInvalid = false
            },
            singleLine = true,
            isError = isInvalid,
            label = { Text(stringResource(R.string.device_group_name)) },
            supportingText = {
              if (isInvalid) Text(stringResource(R.string.device_group_name_invalid))
            })
      },
      confirmButton = {
        TextButton(onClick = { if (onCreate(name)) onDismiss() else isInvalid = true }) {
          Text(stringResource(R.string.create))
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
