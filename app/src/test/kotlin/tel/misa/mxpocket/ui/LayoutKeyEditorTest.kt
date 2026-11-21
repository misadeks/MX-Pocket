package tel.misa.mxpocket.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckActions
import tel.misa.mxpocket.deck.DeckPage
import tel.misa.mxpocket.shared.ui.BindingOption
import tel.misa.mxpocket.shared.ui.localBindings
import tel.misa.mxpocket.shared.ui.parseTileColor

class LayoutKeyEditorTest {
    @Test fun pageBindingsUseEditedProfilesPagesAndLeaveOtherParametersUntouched() {
        fun page(id: String) = ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to id))
        val workflow = BindingOption("Run my workflow", ActionCall("workflow.run", mapOf("workflowId" to "custom")))
        val options = listOf(BindingOption("Old home", page("root")), BindingOption("Other page", page("outside")), workflow)
        val actual = localBindings(options, listOf(DeckPage("root", "Studio"), DeckPage("mix", "Mix")))
        assertEquals(listOf(BindingOption("Open page: Studio", page("root")), BindingOption("Open page: Mix", page("mix")), workflow), actual)
        assertSame(workflow.call, actual.last().call)
    }

    @Test fun colorEntryPreservesTransparentColorsAndRejectsPartialOrOversizedInput() {
        assertEquals(0xff245cb5.toInt(), parseTileColor(" #245cb5 "))
        assertEquals(0x00245566, parseTileColor("00245566"))
        listOf("", "#", "1", "#AB CDEF", "-100FF", "0123456789", "0x11ef").forEach { assertNull(parseTileColor(it)) }
    }
}
