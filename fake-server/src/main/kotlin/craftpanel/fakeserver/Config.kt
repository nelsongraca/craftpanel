package craftpanel.fakeserver

data class Config(
    val gamePort: Int,
    val serverName: String,
    val motd: String,
    val maxPlayers: Int,
    val onlinePlayers: List<String>,
    val stopCommand: String,
) {
    companion object {
        fun fromEnv(): Config {
            val playersRaw = System.getenv("ONLINE_PLAYERS") ?: ""
            val players = if (playersRaw.isBlank()) emptyList()
                          else playersRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            return Config(
                gamePort      = System.getenv("GAME_PORT")?.toIntOrNull() ?: 25565,
                serverName    = System.getenv("SERVER_NAME") ?: "CraftPanel Fake Server",
                motd          = System.getenv("MOTD") ?: "A fake Minecraft server",
                maxPlayers    = System.getenv("MAX_PLAYERS")?.toIntOrNull() ?: 20,
                onlinePlayers = players,
                stopCommand   = System.getenv("STOP_COMMAND") ?: "stop",
            )
        }
    }
}
