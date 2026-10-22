package lucid.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import lucid.app.ui.screens.ExperimentsScreen
import lucid.app.ui.screens.InsightsScreen
import lucid.app.ui.screens.JournalEditScreen
import lucid.app.ui.screens.JournalScreen
import lucid.app.ui.screens.LiveScreen
import lucid.app.ui.screens.PreSleepScreen
import lucid.app.ui.screens.SessionDetailScreen
import lucid.app.ui.screens.SettingsScreen
import lucid.app.ui.screens.TonightScreen
import lucid.app.ui.screens.TrainingScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("tonight", "Tonight", Icons.Filled.Bedtime),
    Tab("journal", "Journal", Icons.Filled.MenuBook),
    Tab("train", "Train", Icons.Filled.SelfImprovement),
    Tab("insights", "Insights", Icons.Filled.Insights),
)

/** Navigation actions shared by screens. */
class Nav(private val c: NavHostController) {
    fun back() = c.popBackStack()
    fun tab(route: String) = c.navigate(route) {
        popUpTo(c.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun session(id: String) = c.navigate("session/$id")
    fun live() = c.navigate("live") { launchSingleTop = true }
    fun journalNew(sessionId: String? = null) = c.navigate("journal/edit/0" + (sessionId?.let { "?session=$it" } ?: ""))
    fun journalEdit(id: Long) = c.navigate("journal/edit/$id")
    fun settings() = c.navigate("settings")
    fun experiments() = c.navigate("experiments")
    fun presleep() = c.navigate("presleep")
}

@Composable
fun LucidNavHost() {
    val controller = rememberNavController()
    val nav = Nav(controller)
    val entry by controller.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.tab(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(controller, startDestination = "tonight", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable("tonight") { TonightScreen(nav) }
            composable("live") { LiveScreen(nav) }
            composable("journal") { JournalScreen(nav) }
            composable(
                "journal/edit/{id}?session={session}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { e -> JournalEditScreen(nav, e.arguments?.getLong("id") ?: 0L, e.arguments?.getString("session")) }
            composable("train") { TrainingScreen(nav) }
            composable("presleep") { PreSleepScreen(nav) }
            composable("insights") { InsightsScreen(nav) }
            composable("session/{id}") { e -> SessionDetailScreen(nav, e.arguments?.getString("id") ?: "") }
            composable("experiments") { ExperimentsScreen(nav) }
            composable("settings") { SettingsScreen(nav) }
        }
    }
}
