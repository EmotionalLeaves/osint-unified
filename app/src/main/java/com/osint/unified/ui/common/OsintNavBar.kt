package com.osint.unified.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.osint.unified.ui.theme.Intel

/**
 * Where everything in the app is, written down.
 *
 * The six ways out of the globe used to be six unlabelled icons wedged
 * into the title bar next to a one-line world brief — a tree, a network
 * meter, a spanner, an eye, a clock and a cog, at the top of the screen,
 * none of them reachable with a thumb. Nobody finds a watch list behind an
 * eye. They are five named destinations along the bottom now, and the
 * things that are genuinely occasional live behind MORE rather than
 * competing for space with the things that are not.
 */
private data class Destination(
    val screen: String,
    val label: String,
    val icon: ImageVector,
    val description: String
)

private val destinations = listOf(
    Destination("godeye", "GLOBE", Icons.Filled.Public, "The live map"),
    Destination("watch", "WATCH", Icons.Filled.Visibility, "Targets being re-checked"),
    Destination("history", "CASES", Icons.Filled.History, "Investigations already run"),
    Destination("framework", "TOOLS", Icons.Filled.AccountTree, "The tool directory"),
    Destination("more", "MORE", Icons.Filled.MoreHoriz, "Forensics, self-test, sources")
)

@Composable
fun OsintNavBar(current: String, badge: Int, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = Intel.Surface, contentColor = Intel.TextMid) {
        destinations.forEach { destination ->
            val selected = current == destination.screen ||
                // A dossier belongs to the globe it was opened from: the
                // bar should not say you are nowhere while you read one.
                (destination.screen == "godeye" && current == "investigation")
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination.screen) },
                icon = {
                    Icon(
                        destination.icon,
                        contentDescription = destination.description
                    )
                },
                label = {
                    Text(
                        if (destination.screen == "watch" && badge > 0)
                            "${destination.label} $badge"
                        else destination.label,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Intel.Base,
                    selectedTextColor = Intel.CyanBright,
                    indicatorColor = Intel.Cyan,
                    unselectedIconColor = Intel.TextLow,
                    unselectedTextColor = Intel.TextLow
                )
            )
        }
    }
}
