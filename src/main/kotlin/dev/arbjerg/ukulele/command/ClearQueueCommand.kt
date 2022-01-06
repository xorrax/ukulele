package dev.arbjerg.ukulele.command

import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import org.springframework.stereotype.Component

@Component
class ClearQueueCommand : Command("clear") {
    override suspend fun CommandContext.invoke() {
        val skipped = player.tracks.size

        player.clear()
        reply("Clear stopped. Removed **$skipped** tracks.")
    }

    override fun HelpContext.provideHelp() {
        addUsage("")
        addDescription("Clear all tracks from the queue.")
    }
}