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
    @Test fun nativePhoneInputReplacesCustomKeypadAndCanClearTheDraft() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun descendants(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val root = controller.get().window.decorView
            val views = descendants(root)
            val typed = root.findViewWithTag<EditText>("phoneInput")
            assertTrue(typed.showSoftInputOnFocus)
            assertFalse(typed.hasFocus())
            assertFalse(views.filterIsInstance<TextView>().any { it.text.toString() == "Q" })
            typed.requestFocus()
            typed.performClick()
            assertEquals(View.GONE, root.findViewWithTag<View>("controlScroll").visibility)
            typed.setText("한글 English\n두 번째 줄")
            assertEquals("한글 English\n두 번째 줄", typed.text.toString())
            views.single { it.contentDescription == "입력창 비우기" }.performClick()
            assertEquals("", typed.text.toString())
            root.findViewWithTag<View>("mouseToggle").performClick()
            assertEquals(View.VISIBLE, root.findViewWithTag<View>("controlScroll").visibility)
            assertFalse(typed.hasFocus())
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
