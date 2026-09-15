package com.yuyan

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/** 仅调试包提供的本地输入框，用于验证真实输入法服务与宿主之间的交互。 */
class ImeTestActivity : Activity() {
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gap = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap, gap * 4, gap, gap)
        }
        editor = EditText(this).apply {
            id = android.R.id.edit
            hint = "输入验证"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "清空文本"
            setOnClickListener { editor.text.clear(); editor.requestFocus() }
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        editor.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) editor.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
