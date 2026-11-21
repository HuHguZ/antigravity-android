package com.google.antigravity

import com.google.antigravity.model.processPhoneActionBlocks
import org.junit.Assert.*
import org.junit.Test

class PhoneActionProtocolTest {
    @org.junit.Test fun keepsMarkdownFencesInProjectFileContents() {
        val body = org.json.JSONObject().put("action", "write_project_file")
            .put("content", "README\n```js\nconsole.log(1)\n```\n").toString()
        val response = "```phone_action\n$body\n```\nГотово"
        org.junit.Assert.assertEquals("Готово", processPhoneActionBlocks(response) {
            org.junit.Assert.assertEquals(body, it)
            true
        })
    }

    @Test fun jsonCodeExamplesNeverInvokeDeviceTools() {
        val response = "Example:\n```json\n{\"action\":\"delete_file\",\"path\":\"notes.txt\"}\n```"
        var called = false
        assertEquals(response, processPhoneActionBlocks(response) { called = true; true })
        assertFalse(called)
    }
    @Test fun onlyExplicitPhoneActionBlocksAreDispatched() {
        var action = ""
        val result = processPhoneActionBlocks("Before\n```phone_action\n{\"action\":\"read_file\"}\n```\nAfter") {
            action = it; true
        }
        assertEquals("{\"action\":\"read_file\"}", action)
        assertEquals("Before\n\nAfter", result)
    }
}
