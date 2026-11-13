package com.pocketcoder.remote

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginUiTest {
    @Test fun koreanKeyboardAndVisibleEraseControlEditTheSendBuffer() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun descendants(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val views = descendants(controller.get().window.decorView)
            fun button(label: String) = views.filterIsInstance<TextView>().single { it.text.toString() == label }
            button("한/영 · 영문").performClick()
            button("ㅎ").performClick(); button("ㅏ").performClick(); button("ㄴ").performClick()
            val typed = views.filterIsInstance<EditText>().single { it.hint.toString().contains("Claude") }
            assertEquals("한", typed.text.toString())
            button("지우기 ⌫").performClick()
            assertEquals("하", typed.text.toString())
            button("입력창 비우기").performClick()
            assertEquals("", typed.text.toString())
            assertTrue(button("Delete").isShown)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun defaultConnectionDoesNotAskForAnAccessToken() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun descendants(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val views = descendants(controller.get().window.decorView)
            val token = views.filterIsInstance<EditText>().single { it.hint.toString().contains("인증 토큰") }
            assertEquals(View.GONE, token.visibility)
            assertFalse(token.isSaveEnabled)
            val legacy = views.filterIsInstance<CheckBox>().single()
            assertFalse(legacy.isChecked)
            legacy.isChecked = true
            token.setText("temporary-legacy-secret")
            legacy.isChecked = false
            assertEquals(View.GONE, token.visibility)
            assertEquals("", token.text.toString())
        } finally { controller.pause().stop().destroy() }
    }
}
