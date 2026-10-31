package com.winlator.cmod.steam

import com.winlator.cmod.store.DosBox
import java.io.File

/**
 * Picks a Steam game's first on-screen controls from what Steam says about it, so games
 * that ignore gamepads (visual novels, point-and-click, mouse-driven indies) do not start
 * with a virtual gamepad that does nothing. Only a default: the user can change it.
 */
object SteamControls {
    /** Bundled profiles (assets/inputcontrols/profiles/controls-1001/1002/1003.icp). */
    const val PROFILE_KEYBOARD_MOUSE = 1001
    const val PROFILE_WASD_MOUSE = 1002
    const val PROFILE_DOS_KEYBOARD = 1003

    enum class Mode {
        /** The game takes a gamepad: keep the user's usual controls (virtual gamepad). */
        GAMEPAD,

        /** Mouse-driven: keyboard/mouse profile, and touches click where the finger is. */
        MOUSE_TOUCH,

        /** Keyboard-and-mouse action game: WASD stick, touch-pad mouse look. */
        WASD,

        /** DOS game: arrow keys, Space, Enter, Ctrl, Alt, Shift, Esc, Y and N. */
        DOS_KEYBOARD,
    }

    /** The profile [mode] gives a game (null: the user's usual controls). */
    fun profileOf(mode: Mode): Int? = when (mode) {
        Mode.GAMEPAD -> null
        Mode.MOUSE_TOUCH -> PROFILE_KEYBOARD_MOUSE
        Mode.WASD -> PROFILE_WASD_MOUSE
        Mode.DOS_KEYBOARD -> PROFILE_DOS_KEYBOARD
    }

    /** Store tags of games played with the mouse and a few keys. */
    private val MOUSE_TAGS = setOf(
        3799, // Visual Novel
        1698, // Point & Click
        9551, // Dating Sim
        1664, // Puzzle
        1666, // Card Game
        9, // Strategy
        1676, // RTS
        1741, // Turn-Based Strategy
        1738, // Hidden Object
        11014, // Interactive Fiction
        31275, // Text-Based
        4486, // Choose Your Own Adventure
    )

    /** Store tags of games that need movement keys and mouse look. */
    private val ACTION_TAGS = setOf(
        1663, // FPS
        1774, // Shooter
        3814, // Third-Person Shooter
        699, // Racing
        1625, // Platformer
        1743, // Fighting
        1646, // Hack and Slash
        4758, // Twin Stick Shooter
        29482, // Souls-like
        3955, // Character Action Game
        3839, // First-Person
        1697, // Third Person
    )

    /** Valve's "Keyboard (WASD) and Mouse" Steam Input template. */
    private const val TEMPLATE_WASD = 6

    fun choose(info: SteamControllerInfo?, gameExe: File): Mode {
        if (info == null) return decide(padSupport = false, wasd = false, mouseRank = -1, actionRank = -1, gameExe = gameExe)
        return decide(
            padSupport = info.support == "full" || info.support == "partial" || info.storeCategory,
            wasd = info.templateIndex == TEMPLATE_WASD,
            mouseRank = info.tags.indexOfFirst { it in MOUSE_TAGS },
            actionRank = info.tags.indexOfFirst { it in ACTION_TAGS },
            gameExe = gameExe,
        )
    }

    /**
     * The decision for any store: [padSupport] if the store says the game takes a pad,
     * [wasd] if it says keyboard and mouse, and the positions of the first mouse-style and
     * action-style genre in the game's tags (-1 when absent; earlier = more relevant).
     */
    fun decide(padSupport: Boolean, wasd: Boolean, mouseRank: Int, actionRank: Int, gameExe: File): Mode {
        val mouseFirst = mouseRank >= 0 && (actionRank < 0 || mouseRank < actionRank)
        // DOS games (run by DOSBox) take the keyboard, whatever the store says about pads.
        if (DosBox.isDosBox(gameExe)) return if (mouseFirst) Mode.MOUSE_TOUCH else Mode.DOS_KEYBOARD
        if (padSupport) return Mode.GAMEPAD
        if (wasd) return Mode.WASD
        if (mouseFirst) return Mode.MOUSE_TOUCH
        if (actionRank >= 0) return if (usesXInput(gameExe)) Mode.GAMEPAD else Mode.WASD
        // Older games often support XInput pads without saying so on the store.
        return if (usesXInput(gameExe)) Mode.GAMEPAD else Mode.MOUSE_TOUCH
    }

    /**
     * Whether the executable imports or loads XInput (its DLL name appears in the file).
     * Only the game's own exe: engine DLLs such as SDL2 or UnityPlayer always mention it.
     */
    fun usesXInput(exe: File): Boolean {
        if (!exe.isFile || !exe.name.endsWith(".exe", true)) return false
        val needle = "xinput".toByteArray()
        val limit = 160L * 1024 * 1024
        return runCatching {
            exe.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                var matched = 0
                var total = 0L
                while (total < limit) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    for (i in 0 until count) {
                        val b = buffer[i].toInt()
                        val lower = if (b in 'A'.code..'Z'.code) b + 32 else b
                        matched = when {
                            lower == needle[matched].toInt() -> matched + 1
                            lower == needle[0].toInt() -> 1
                            else -> 0
                        }
                        if (matched == needle.size) return@use true
                    }
                }
                false
            }
        }.getOrDefault(false)
    }
}
