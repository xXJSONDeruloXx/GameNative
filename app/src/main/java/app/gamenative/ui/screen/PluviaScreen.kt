package app.gamenative.ui.screen

/**
 * Destinations for top level screens, excluding home screen destinations.
 */
sealed class PluviaScreen(val route: String) {
    data object LoginUser : PluviaScreen("login")
    data object Home : PluviaScreen("home")
    data object XServer : PluviaScreen("xserver")
    data object Settings : PluviaScreen("settings") {
        const val ARG_OPEN_ITCH_API_DIALOG = "openItchApiDialog"
        val routeWithArgs = "$route?$ARG_OPEN_ITCH_API_DIALOG={$ARG_OPEN_ITCH_API_DIALOG}"

        fun withItchApiDialog(open: Boolean): String =
            if (open) "$route?$ARG_OPEN_ITCH_API_DIALOG=true" else route
    }
    data object Chat : PluviaScreen("chat/{id}") {
        fun route(id: Long) = "chat/$id"
        const val ARG_ID = "id"
    }
}
