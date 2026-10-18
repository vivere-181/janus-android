/*
 * SPDX-FileCopyrightText: 2014 Saikrishna Arcot <saiarcot895@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.plugins.mousepad

import android.content.Context
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import java.util.Locale

class KeyListenerView(context: Context, set: AttributeSet?) : View(context, set) {

    var deviceId: String? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return KeyInputConnection(this, true)
    }

    override fun onCheckIsTextEditor(): Boolean {
        return true
    }

    fun sendChars(chars: CharSequence) {
        val plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin::class.java) ?: return
        plugin.sendText(chars.toString())
    }

    private fun sendKeyPressPacket(np: NetworkPacket) {
        val plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin::class.java) ?: return
        plugin.sendPacket(np)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // consume events that otherwise would move the focus away from us
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            //We don't want to swallow the back button press
            return false
        }

        // NOTE: Most keyboards, and specifically the Android default keyboard when
        // entering non-ascii characters, will not trigger KeyEvent events as documented
        // here: http://developer.android.com/reference/android/view/KeyEvent.html

        //Log.e("KeyDown", "------------");
        //Log.e("KeyDown", "keyChar:" + (int) event.getDisplayLabel());
        //Log.e("KeyDown", "utfChar:" + (char)event.getUnicodeChar());
        //Log.e("KeyDown", "intUtfChar:" + event.getUnicodeChar());

        val np = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST)

        var modifier = false
        if (event.isAltPressed) {
            np["alt"] = true
            modifier = true
        }

        if (event.isCtrlPressed) {
            np["ctrl"] = true
            modifier = true
        }

        if (event.isShiftPressed) {
            np["shift"] = true
        }

        if (event.isMetaPressed) {
            np["super"] = true
            modifier = true
        }

        val specialKey = SpecialKeysMap.get(keyCode, -1)

        if (specialKey != -1) {
            np["specialKey"] = specialKey
        } else if (event.displayLabel.code != 0 && modifier) {
            //Alt will change the utf symbol to non-ascii characters, we want the plain original letter
            //Since getDisplayLabel will always have a value, we have to check for special keys before
            val keyCharacter = event.displayLabel
            np["key"] = keyCharacter.toString().lowercase(Locale.getDefault())
        } else {
            //A normal key, but still not handled by the KeyInputConnection (happens with numbers)
            np["key"] = event.unicodeChar.toChar().toString()
        }

        sendKeyPressPacket(np)
        return true

    }

    companion object {
        @JvmField
        val SpecialKeysMap = SparseIntArray()

        init {
            var i = 0
            SpecialKeysMap.put(KeyEvent.KEYCODE_DEL, ++i)              // 1
            SpecialKeysMap.put(KeyEvent.KEYCODE_TAB, ++i)              // 2
            SpecialKeysMap.put(KeyEvent.KEYCODE_ENTER, 12)
            ++i        // 3 is not used, return is 12 instead
            SpecialKeysMap.put(KeyEvent.KEYCODE_DPAD_LEFT, ++i)        // 4
            SpecialKeysMap.put(KeyEvent.KEYCODE_DPAD_UP, ++i)          // 5
            SpecialKeysMap.put(KeyEvent.KEYCODE_DPAD_RIGHT, ++i)       // 6
            SpecialKeysMap.put(KeyEvent.KEYCODE_DPAD_DOWN, ++i)        // 7
            SpecialKeysMap.put(KeyEvent.KEYCODE_PAGE_UP, ++i)          // 8
            SpecialKeysMap.put(KeyEvent.KEYCODE_PAGE_DOWN, ++i)        // 9
            SpecialKeysMap.put(KeyEvent.KEYCODE_MOVE_HOME, ++i)    // 10
            SpecialKeysMap.put(KeyEvent.KEYCODE_MOVE_END, ++i)     // 11
            SpecialKeysMap.put(KeyEvent.KEYCODE_NUMPAD_ENTER, ++i) // 12
            SpecialKeysMap.put(KeyEvent.KEYCODE_FORWARD_DEL, ++i)  // 13
            SpecialKeysMap.put(KeyEvent.KEYCODE_ESCAPE, ++i)       // 14
            SpecialKeysMap.put(KeyEvent.KEYCODE_SYSRQ, ++i)        // 15
            SpecialKeysMap.put(KeyEvent.KEYCODE_SCROLL_LOCK, ++i)  // 16
            SpecialKeysMap.put(KeyEvent.KEYCODE_CTRL_LEFT, ++i)    // 17
            SpecialKeysMap.put(KeyEvent.KEYCODE_ALT_LEFT, ++i)     // 18
            SpecialKeysMap.put(KeyEvent.KEYCODE_SHIFT_LEFT, ++i)   // 19
            SpecialKeysMap.put(KeyEvent.KEYCODE_META_LEFT, ++i)    // 20
            SpecialKeysMap.put(KeyEvent.KEYCODE_F1, ++i)           // 21
            SpecialKeysMap.put(KeyEvent.KEYCODE_F2, ++i)           // 22
            SpecialKeysMap.put(KeyEvent.KEYCODE_F3, ++i)           // 23
            SpecialKeysMap.put(KeyEvent.KEYCODE_F4, ++i)           // 24
            SpecialKeysMap.put(KeyEvent.KEYCODE_F5, ++i)           // 25
            SpecialKeysMap.put(KeyEvent.KEYCODE_F6, ++i)           // 26
            SpecialKeysMap.put(KeyEvent.KEYCODE_F7, ++i)           // 27
            SpecialKeysMap.put(KeyEvent.KEYCODE_F8, ++i)           // 28
            SpecialKeysMap.put(KeyEvent.KEYCODE_F9, ++i)           // 29
            SpecialKeysMap.put(KeyEvent.KEYCODE_F10, ++i)          // 30
            SpecialKeysMap.put(KeyEvent.KEYCODE_F11, ++i)          // 31
            SpecialKeysMap.put(KeyEvent.KEYCODE_F12, ++i)          // 21
        }
    }
}
