/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ge.connect.ui.theme.LocalAppSurfaces

/** Primary-colored label above a group, like the section titles in Android 16 Settings. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * A rounded group of rows with small gaps between them (Material 3 Expressive "segmented" list).
 * Each row receives the shapes for its position in the group.
 */
@Composable
fun ListGroup(rows: List<@Composable (ListItemShapes) -> Unit>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { index, row -> row(ListItemDefaults.segmentedShapes(index, rows.size)) }
    }
}

/** A tappable settings-style row: icon badge, title, optional summary and trailing content. */
@Composable
fun NavRow(
    shapes: ListItemShapes,
    @DrawableRes icon: Int?,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    iconContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    // A Surface with a ListItem rather than SegmentedListItem: that one keeps a TV remote's focus from moving
    // between rows. With a remote (Android TV), the focused row gets a clear ring.
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shapes.shape,
        color = LocalAppSurfaces.current.card,
        border = if (focused) androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        interactionSource = interaction,
    ) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            leadingContent = (leading ?: icon?.let { { IconBadge(it, iconContainer, iconColor) } }),
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
        )
    }
}

/** A non-tappable row with the same look as [NavRow], for information and tips. */
@Composable
fun InfoRow(shapes: ListItemShapes, @DrawableRes icon: Int, title: String, summary: String? = null) {
    androidx.compose.material3.Surface(shape = shapes.shape, color = LocalAppSurfaces.current.card) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            leadingContent = { IconBadge(icon) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
fun IconBadge(
    @DrawableRes icon: Int,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    color: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Int = 40,
) {
    Box(
        Modifier.size(size.dp).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size((size * 0.55f).dp))
    }
}
