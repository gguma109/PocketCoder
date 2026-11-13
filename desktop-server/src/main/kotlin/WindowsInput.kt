import java.awt.event.KeyEvent as K

/** Explicit Windows VK mapping; Windows and AWT numeric values are not interchangeable. */
internal fun windowsKey(vk: Int): Int? = when (vk) {
    in 0x30..0x39, in 0x41..0x5a, in 0x60..0x69 -> vk
    in 0x70..0x7b -> K.VK_F1 + vk - 0x70
    in 0x7c..0x87 -> K.VK_F13 + vk - 0x7c
    8 -> K.VK_BACK_SPACE; 9 -> K.VK_TAB; 13 -> K.VK_ENTER
    16, 160, 161 -> K.VK_SHIFT; 17, 162, 163 -> K.VK_CONTROL
    18, 164, 165 -> K.VK_ALT; 19 -> K.VK_PAUSE; 20 -> K.VK_CAPS_LOCK
    21 -> K.VK_KANA; 25 -> K.VK_KANJI; 27 -> K.VK_ESCAPE; 32 -> K.VK_SPACE
    33 -> K.VK_PAGE_UP; 34 -> K.VK_PAGE_DOWN; 35 -> K.VK_END; 36 -> K.VK_HOME
    37 -> K.VK_LEFT; 38 -> K.VK_UP; 39 -> K.VK_RIGHT; 40 -> K.VK_DOWN
    44 -> K.VK_PRINTSCREEN; 45 -> K.VK_INSERT; 46 -> K.VK_DELETE
    91, 92 -> K.VK_WINDOWS; 93 -> K.VK_CONTEXT_MENU
    106 -> K.VK_MULTIPLY; 107 -> K.VK_ADD; 108 -> K.VK_SEPARATOR
    109 -> K.VK_SUBTRACT; 110 -> K.VK_DECIMAL; 111 -> K.VK_DIVIDE
    144 -> K.VK_NUM_LOCK; 145 -> K.VK_SCROLL_LOCK
    186 -> K.VK_SEMICOLON; 187 -> K.VK_EQUALS; 188 -> K.VK_COMMA
    189 -> K.VK_MINUS; 190 -> K.VK_PERIOD; 191 -> K.VK_SLASH
    192 -> K.VK_BACK_QUOTE; 219 -> K.VK_OPEN_BRACKET; 220, 226 -> K.VK_BACK_SLASH
    221 -> K.VK_CLOSE_BRACKET; 222 -> K.VK_QUOTE
    else -> null
}

internal class HeldKeys(private val press: (Int) -> Unit, private val release: (Int) -> Unit) {
    private val held = mutableMapOf<Int, Int>()
    fun down(vk: Int) { val code = windowsKey(vk) ?: return; press(code); held[vk] = code }
    fun up(vk: Int) { val code = held.remove(vk) ?: return; if (code !in held.values) release(code) }
    fun clear() { val codes = held.values.toSet(); held.clear(); codes.forEach { try { release(it) } catch (_: Exception) {} } }
}

internal fun windowsInputSelfTest() {
    check(windowsKey(13) == K.VK_ENTER && windowsKey(46) == K.VK_DELETE)
    check(windowsKey(0x7c) == K.VK_F13 && windowsKey(0x87) == K.VK_F24)
    check(windowsKey(91) == K.VK_WINDOWS && windowsKey(186) == K.VK_SEMICOLON)
    check(windowsKey(21) == K.VK_KANA && windowsKey(25) == K.VK_KANJI)
    check(windowsKey(255) == null)
    val events = mutableListOf<Int>(); val keys = HeldKeys({ events += it }, { events += -it })
    keys.up(65); keys.down(65); keys.down(65); keys.clear(); keys.clear()
    check(events == listOf(65,65,-65))
    events.clear(); keys.down(160); keys.down(161); keys.up(160)
    check(events == listOf(K.VK_SHIFT,K.VK_SHIFT)); keys.up(161); check(events.last() == -K.VK_SHIFT)
}
