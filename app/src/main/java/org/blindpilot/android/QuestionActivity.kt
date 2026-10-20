package org.blindpilot.android

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import org.json.JSONArray

class QuestionActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val controller=ChatController.get(this)
        val question=controller.pending ?: run { finish();return }
        val builder=AlertDialog.Builder(this).setTitle(if(question.secret) "Private input needed" else "BlindPilot needs your answer").setMessage(question.text)
        fun answer(value:String?) {
            finish()
            // Return to the target window before an approved action revalidates it.
            Handler(Looper.getMainLooper()).postDelayed({ question.answer(value) },700)
        }
        if(question.choices.isNotEmpty() && !question.multiSelect) {
            builder.setItems(question.choices.toTypedArray()) { _,which -> answer(question.choices[which]) }
        } else if(question.multiSelect) {
            val selected=BooleanArray(question.choices.size)
            builder.setMultiChoiceItems(question.choices.toTypedArray(),selected) { _,which,checked -> selected[which]=checked }
                .setPositiveButton("Submit") { _,_ -> answer(JSONArray(question.choices.filterIndexed { index,_ -> selected[index] }).toString()) }
        } else {
            val input=EditText(this).apply { contentDescription="Your answer";inputType=InputType.TYPE_CLASS_TEXT or if(question.secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            builder.setView(input).setPositiveButton("Submit") { _,_ -> answer(input.text.toString()) }
        }
        builder.setNegativeButton("Cancel") { _,_ -> answer(null) }.setOnCancelListener { answer(null) }
        builder.show()
    }
}
