package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.ukulele.data.GuildProperties
import dev.arbjerg.ukulele.data.GuildPropertiesService
import dev.arbjerg.ukulele.jda.CommandContext
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.dv8tion.jda.api.audio.AudioSendHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture

class Player(val beans: Beans, guildProperties: GuildProperties, cc: CommandContext) : AudioEventAdapter(), AudioSendHandler {
    @Component
    class Beans(
            val apm: AudioPlayerManager,
            val guildProperties: GuildPropertiesService
    )

    private var task: ScheduledFuture<*>? = null;
    private val context = cc
    private val guildId = guildProperties.guildId
    private val queue = TrackQueue()
    private val player = beans.apm.createPlayer().apply {
        addListener(this@Player)
        volume = guildProperties.volume
    }
    private val buffer = ByteBuffer.allocate(1024)
    private val frame: MutableAudioFrame = MutableAudioFrame().apply { setBuffer(buffer) }
    private val log: Logger = LoggerFactory.getLogger(Player::class.java)
    var volume: Int
        get() = player.volume
        set(value) {
            player.volume = value
            beans.guildProperties.transform(guildId) {
                it.volume = player.volume
            }.subscribe()
        }

    val tracks: List<AudioTrack> get() {
        val tracks = queue.tracks.toMutableList()
        player.playingTrack?.let { tracks.add(0, it) }
        return tracks
    }

    val remainingDuration: Long get() {
        var duration = 0L
        if (player.playingTrack != null && !player.playingTrack.info.isStream)
            player.playingTrack?.let { duration = it.info.length - it.position }
        return duration + queue.duration
    }

    val isPaused : Boolean
        get() = player.isPaused

    var isRepeating : Boolean = false
        
    /**
     * @return whether or not we started playing
     */
    fun add(vararg tracks: AudioTrack): Boolean {
        // Cancel the task, if there is one
        if(task != null) {
            task?.cancel(false)
        }

        queue.add(*tracks)
        if (player.playingTrack == null) {
            player.playTrack(queue.take()!!)
            return true
        }
        return false
    }

    fun skip(range: IntRange): List<AudioTrack> {
        val rangeFirst = range.first.coerceAtMost(queue.tracks.size)
        val rangeLast = range.last.coerceAtMost(queue.tracks.size)
        val skipped = mutableListOf<AudioTrack>()
        var newRange = rangeFirst .. rangeLast 
        // Skip the first track if it is stored here
        if (newRange.contains(0) && player.playingTrack != null) {
            skipped.add(player.playingTrack)
            // Reduce range if found
            newRange = 0 .. rangeLast - 1
        } else {
            newRange = newRange.first - 1 .. newRange.last - 1
        }
        if (newRange.last >= 0) skipped.addAll(queue.removeRange(newRange))
        if (skipped.first() == player.playingTrack) {
            if(isRepeating){
                queue.add(player.playingTrack.makeClone())
            }
            player.stopTrack()
        }
        return skipped
    }

    fun clear() {
        queue.clear();
    }

    fun pause() {
        player.isPaused = true
    }

    fun resume() {
        player.isPaused = false
    }

    fun stop() {
        queue.clear()
        player.stopTrack()
    }

    private fun  delayedDisconnect() {
            if(queue.peek() == null)
                context.disconnect()
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        if (isRepeating && endReason.mayStartNext) {
            queue.add(track.makeClone())
        }
        else if(queue.peek() == null) {
            context.emptyQueue()

            val scheduledExecutorService = Executors.newScheduledThreadPool(1)
            // Create the task to execute
            val r = Runnable { delayedDisconnect() }
            task = scheduledExecutorService.schedule(r, 60, TimeUnit.SECONDS)
        }


        val new = queue.take() ?: return
        player.playTrack(new)
    }

    override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) {
        log.error("Track exception", exception)
    }

    override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
        log.error("Track $track got stuck!")
    }

    override fun canProvide(): Boolean {
        return player.provide(frame)
    }

    override fun provide20MsAudio(): ByteBuffer {
        // flip to make it a read buffer
        (buffer as Buffer).flip()
        return buffer
    }

    override fun isOpus() = true
}
