package com.chatly

import com.chatly.blocks.ChatlyBlock
import com.chatly.blocks.ChatlyBlocks
import com.chatly.models.MessageRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The reader's contract: render what you can, never throw.
 *
 * Every case here has a counterpart in
 * `packages/sdk-react-native/src/blocks/read-envelope.parity.spec.ts`, which
 * is the reference. The ones that matter most are the REFUSALS — a card is
 * authored by whoever holds an API key, and this reader is the only guard
 * between that and an `ImageView`.
 */
class BlocksReaderTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun read(raw: String): List<ChatlyBlock> =
        ChatlyBlocks.read(json.parseToJsonElement(raw))

    @Test
    fun `reads a card with price, badge, variants and actions`() {
        val blocks = read(
            """
            {"v":1,"blocks":[{"type":"card","id":"c1","title":"Trail Runner",
              "subtitle":"Mens 9","image":"https://cdn.example.com/a.png",
              "badge":"Sale","price":{"amount":4820,"currency":"USD","compareAt":6000},
              "stock":"low","variants":[{"label":"9","value":"sz9","inStock":false}],
              "fields":[{"label":"Weight","value":"240g"}],
              "actions":[{"kind":"url","label":"Open","href":"https://example.com/p"}],
              "reactions":["like","dislike"],"reaction":"like"}]}
            """,
        )
        val card = (blocks.single() as ChatlyBlock.Card).card
        assertEquals("Trail Runner", card.title)
        assertEquals("https://cdn.example.com/a.png", card.image)
        assertEquals(4820L, card.price?.amount)
        assertEquals(6000L, card.price?.compareAt)
        assertEquals("low", card.stock)
        assertEquals(listOf("Sale"), card.badges.map { it.text })
        // A bare string badge becomes the neutral tone, never dropped.
        assertEquals(listOf("neutral"), card.badges.map { it.tone })
        assertEquals(false, card.variants.single().inStock)
        assertEquals("Weight", card.fields.single().label)
        assertEquals("url", card.actions.single().kind)
        assertEquals("like", card.reaction)
    }

    @Test
    fun `an unknown block type is dropped and its neighbours still render`() {
        val blocks = read(
            """
            {"v":1,"blocks":[{"type":"text","markdown":"before"},
              {"type":"hologram","spin":true},
              {"type":"text","markdown":"after"}]}
            """,
        )
        assertEquals(listOf("before", "after"), blocks.map { (it as ChatlyBlock.Text).markdown })
    }

    @Test
    fun `a malformed envelope yields nothing and never throws`() {
        for (raw in listOf(
            "\"not an envelope\"",
            "42",
            "null",
            "[]",
            """{"v":2,"blocks":[{"type":"text","markdown":"hi"}]}""",
            """{"v":"1","blocks":[{"type":"text","markdown":"hi"}]}""",
            """{"v":1}""",
            """{"v":1,"blocks":"nope"}""",
            """{"v":1,"blocks":[null,7,"x",[],{"type":"card"}]}""",
        )) {
            assertEquals(emptyList(), read(raw), "should be empty for $raw")
        }
    }

    @Test
    fun `a message decodes with a garbage blocks value and keeps its body`() {
        // The whole point of a JsonElement field: a server newer than the app
        // cannot cost the visitor the message it rides on.
        for (value in listOf("\"junk\"", "7", "null", "[1,2]", """{"v":9}""")) {
            val row = json.decodeFromString(
                MessageRow.serializer(),
                """{"id":"m1","authorType":"bot","kind":"rich","body":"Here you go",
                    "createdAt":"2026-10-20T10:00:00.000Z","blocks":$value}""",
            )
            assertEquals("Here you go", row.body)
            assertEquals(emptyList(), ChatlyBlocks.read(row.blocks))
        }
    }

    @Test
    fun `a message with no blocks key decodes to null`() {
        val row = json.decodeFromString(
            MessageRow.serializer(),
            """{"id":"m1","authorType":"agent","kind":"text","body":"hi",
                "createdAt":"2026-10-20T10:00:00.000Z"}""",
        )
        assertNull(row.blocks)
        assertEquals(emptyList(), ChatlyBlocks.read(row.blocks))
    }

    @Test
    fun `a rich message's blocks survive the decode`() {
        val row = json.decodeFromString(
            MessageRow.serializer(),
            """{"id":"m1","authorType":"bot","kind":"rich","body":"Order update",
                "createdAt":"2026-10-20T10:00:00.000Z",
                "blocks":{"v":1,"blocks":[{"type":"divider"}]}}""",
        )
        assertTrue(ChatlyBlocks.read(row.blocks).single() is ChatlyBlock.Divider)
    }

    @Test
    fun `media is https only and the block goes with its source`() {
        for (src in listOf(
            "http://cdn.example.com/a.png",
            "data:image/png;base64,AAAA",
            "javascript:alert(1)",
            "file:///etc/passwd",
            " https://cdn.example.com/a.png",
            "https://cdn.example.com/a.png\n",
            "https:///a.png",
        )) {
            assertTrue(!ChatlyBlocks.isSafeMediaSrc(src), "must refuse $src")
        }
        assertTrue(ChatlyBlocks.isSafeMediaSrc("https://cdn.example.com/a.png"))
        assertEquals(
            emptyList(),
            read("""{"v":1,"blocks":[{"type":"media","kind":"image","url":"http://x/a.png","alt":"a"}]}"""),
        )
        // The same source on a CARD drops the image and keeps the card.
        val card = read(
            """{"v":1,"blocks":[{"type":"card","title":"T","image":"http://x/a.png"}]}""",
        ).single() as ChatlyBlock.Card
        assertNull(card.card.image)
        assertEquals("T", card.card.title)
    }

    @Test
    fun `an action href is https, mailto or tel and nothing else`() {
        assertTrue(ChatlyBlocks.isSafeActionHref("https://example.com"))
        assertTrue(ChatlyBlocks.isSafeActionHref("mailto:help@example.com"))
        assertTrue(ChatlyBlocks.isSafeActionHref("tel:+15551234567"))
        for (href in listOf(
            "http://example.com",
            "intent://scan#Intent;scheme=zxing;end",
            "sms:+15551234567",
            "market://details?id=com.example",
            "javascript:alert(1)",
        )) {
            assertTrue(!ChatlyBlocks.isSafeActionHref(href), "must refuse $href")
        }
        // A refused href loses the ACTION; a block with no action left goes.
        assertEquals(
            emptyList(),
            read("""{"v":1,"blocks":[{"type":"actions","actions":[{"kind":"url","label":"L","href":"http://x"}]}]}"""),
        )
    }

    @Test
    fun `every action kind reads its own payload field`() {
        val actions = (
            read(
                """
                {"v":1,"blocks":[{"type":"actions","actions":[
                  {"kind":"url","label":"A","href":"https://example.com"},
                  {"kind":"postback","label":"B","value":"v1","silent":true,"payload":{"k":1}},
                  {"kind":"host_event","label":"C","event":"checkout","id":"e1"},
                  {"kind":"step","label":"D","canvasId":"cv1"},
                  {"kind":"copy","label":"E","text":"CODE10"},
                  {"kind":"navigate","label":"F","path":"/cart"},
                  {"kind":"teleport","label":"G","to":"moon"},
                  {"kind":"postback","label":"H"}
                ]}]}
                """,
            ).single() as ChatlyBlock.Actions
            ).actions
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), actions.map { it.label })
        assertEquals("https://example.com", actions[0].value)
        assertEquals("v1", actions[1].value)
        assertTrue(actions[1].silent)
        assertEquals("checkout", actions[2].value)
        assertEquals("cv1", actions[3].value)
        assertEquals("CODE10", actions[4].value)
        assertEquals("/cart", actions[5].value)
    }

    @Test
    fun `badges collapse all three spellings and coerce an unknown tone`() {
        val card = read(
            """
            {"v":1,"blocks":[{"type":"card","title":"T","badge":[
              "Sale",{"text":"Great price","tone":"success"},
              {"text":"Odd","tone":"chartreuse"},{"tone":"danger"},17]}]}
            """,
        ).single() as ChatlyBlock.Card
        assertEquals(
            listOf("Sale" to "neutral", "Great price" to "success", "Odd" to "neutral"),
            card.card.badges.map { it.text to it.tone },
        )
    }

    @Test
    fun `a reaction the card does not offer is dropped`() {
        val offered = read(
            """{"v":1,"blocks":[{"type":"card","title":"T","reactions":["like","like","shrug"],"reaction":"dislike"}]}""",
        ).single() as ChatlyBlock.Card
        // Deduped, narrowed to the two members, and the chosen one goes with
        // the member it named.
        assertEquals(listOf("like"), offered.card.reactions)
        assertNull(offered.card.reaction)
    }

    @Test
    fun `a card tap target is narrowed to the four kinds that can carry one`() {
        fun tap(action: String): String? {
            val card = read("""{"v":1,"blocks":[{"type":"card","title":"T","action":$action}]}""")
                .single() as ChatlyBlock.Card
            return card.card.action?.kind
        }
        assertEquals("url", tap("""{"kind":"url","label":"L","href":"https://example.com"}"""))
        assertEquals("navigate", tap("""{"kind":"navigate","label":"L","path":"/p"}"""))
        // A silent message sent by tapping a photo is indistinguishable from
        // a mis-tap, so `postback` is excluded on purpose.
        assertNull(tap("""{"kind":"postback","label":"L","value":"v"}"""))
        assertNull(tap("""{"kind":"url","label":"L"}"""))
    }

    @Test
    fun `a variant needs both halves and inStock must be a real boolean`() {
        val card = read(
            """
            {"v":1,"blocks":[{"type":"card","title":"T","variants":[
              {"label":"9","value":"sz9","inStock":"false"},
              {"label":"10","value":"sz10"},
              {"label":"11"},{"value":"sz12"}]}]}
            """,
        ).single() as ChatlyBlock.Card
        assertEquals(listOf("9", "10"), card.card.variants.map { it.label })
        // The string "false" is truthy to one renderer and not equal to false
        // in another, so it is normalised away rather than passed through.
        assertNull(card.card.variants[0].inStock)
        assertNull(card.card.variants[1].inStock)
    }

    @Test
    fun `an unrecognised stock state is dropped because it reaches a label`() {
        val card = read("""{"v":1,"blocks":[{"type":"card","title":"T","stock":"backordered"}]}""")
            .single() as ChatlyBlock.Card
        assertNull(card.card.stock)
    }

    @Test
    fun `a grid drops an unreadable card and keeps the rest`() {
        val grid = read(
            """
            {"v":1,"blocks":[{"type":"card_grid","layout":"carousel","cards":[
              {"title":"A"},{"subtitle":"no title"},{"title":"B"}],
              "more":{"label":"Show all 60","action":{"kind":"postback","label":"More","value":"next"}}}]}
            """,
        ).single() as ChatlyBlock.CardGrid
        assertEquals(listOf("A", "B"), grid.cards.map { it.title })
        assertTrue(grid.carousel)
        assertEquals("Show all 60", grid.more?.label)
        assertEquals("next", grid.more?.action?.value)
    }

    @Test
    fun `a more tile that cannot page is dropped and the grid kept`() {
        val grid = read(
            """
            {"v":1,"blocks":[{"type":"card_grid","cards":[{"title":"A"}],
              "more":{"label":"Copy","action":{"kind":"copy","label":"C","text":"x"}}}]}
            """,
        ).single() as ChatlyBlock.CardGrid
        assertNull(grid.more)
        assertTrue(!grid.carousel)
        assertEquals(1, grid.cards.size)
    }

    @Test
    fun `a grid with no readable card is dropped entirely`() {
        assertEquals(
            emptyList(),
            read("""{"v":1,"blocks":[{"type":"card_grid","cards":[{"subtitle":"x"}]}]}"""),
        )
    }

    @Test
    fun `a timeline coerces an unknown state and drops an unreadable date`() {
        val steps = (
            read(
                """
                {"v":1,"blocks":[{"type":"order_status","steps":[
                  {"label":"Packed","state":"done","at":"2026-10-20T10:00:00.000Z"},
                  {"label":"Payment failed","state":"failed","at":"yesterday"},
                  {"state":"done"}]}]}
                """,
            ).single() as ChatlyBlock.OrderStatus
            ).steps
        // The row survives with its words: a timeline missing a row reads as
        // an order that skipped a stage.
        assertEquals(listOf("Packed", "Payment failed"), steps.map { it.label })
        assertEquals(listOf("done", "pending"), steps.map { it.state })
        assertEquals("2026-10-20T10:00:00.000Z", steps[0].at)
        assertNull(steps[1].at)
    }

    @Test
    fun `a timeline with no readable step is dropped`() {
        assertEquals(
            emptyList(),
            read("""{"v":1,"blocks":[{"type":"order_status","steps":[{"state":"done"}]}]}"""),
        )
    }

    @Test
    fun `a table coerces a bad heading, truncates a long row and pads a short one`() {
        val table = read(
            """
            {"v":1,"blocks":[{"type":"table","caption":"Compare",
              "columns":[{"label":"Alpha"},{"image":"https://cdn.example.com/b.png"},{"label":"Gamma"}],
              "rows":[{"label":"Price","cells":["1","2","3","4"]},
                      {"label":"Weight","cells":["240g"]},
                      {"cells":["orphan"]}]}]}
            """,
        ).single() as ChatlyBlock.Table
        // The blank heading keeps its POSITION: dropping it would draw every
        // row's second value under "Gamma".
        assertEquals(listOf("Alpha", "", "Gamma"), table.columns.map { it.label })
        assertEquals("https://cdn.example.com/b.png", table.columns[1].image)
        assertEquals(listOf("Price", "Weight"), table.rows.map { it.label })
        assertEquals(listOf("1", "2", "3"), table.rows[0].cells)
        assertEquals(listOf("240g"), table.rows[1].cells)
        assertEquals("Compare", table.caption)
    }

    @Test
    fun `a table with no heading or no row is dropped`() {
        assertEquals(
            emptyList(),
            read("""{"v":1,"blocks":[{"type":"table","columns":[{"image":"https://x/a.png"}],"rows":[{"label":"P","cells":["1"]}]}]}"""),
        )
        assertEquals(
            emptyList(),
            read("""{"v":1,"blocks":[{"type":"table","columns":[{"label":"A"}],"rows":[{"cells":["1"]}]}]}"""),
        )
    }

    @Test
    fun `fields, quick replies, place, status and divider`() {
        val blocks = read(
            """
            {"v":1,"blocks":[
              {"type":"fields","items":[{"label":"Order","value":"#42"},{"label":"only"}]},
              {"type":"quick_replies","options":[{"label":"Yes","value":"yes"},{"label":"No"}]},
              {"type":"place","label":"Aisle 7","detail":"back wall","ref":7},
              {"type":"status","id":"s1","text":"Looking through the shop","state":"running"},
              {"type":"divider"},
              {"type":"status","text":"no id"},
              {"type":"place","detail":"no label"}]}
            """,
        )
        assertEquals(5, blocks.size)
        assertEquals(listOf("Order"), (blocks[0] as ChatlyBlock.Fields).items.map { it.label })
        assertEquals(listOf("Yes"), (blocks[1] as ChatlyBlock.QuickReplies).options.map { it.label })
        val place = blocks[2] as ChatlyBlock.Place
        assertEquals("back wall", place.detail)
        // A non-string `ref` is dropped rather than stringified.
        assertNull(place.ref)
        assertEquals("s1", (blocks[3] as ChatlyBlock.Status).id)
        assertTrue(blocks[4] is ChatlyBlock.Divider)
    }

    @Test
    fun `a price that cannot be drawn is dropped and the card survives`() {
        for (price in listOf(
            """{"amount":"4820","currency":"USD"}""",
            """{"amount":4820,"currency":"usd"}""",
            """{"amount":4820,"currency":"DOLLARS"}""",
            """{"amount":-5,"currency":"USD"}""",
            """{"amount":48.2,"currency":"USD"}""",
            """{"currency":"USD"}""",
            "\"4820\"",
        )) {
            val card = read("""{"v":1,"blocks":[{"type":"card","title":"T","price":$price}]}""")
                .single() as ChatlyBlock.Card
            assertNull(card.card.price, "should refuse $price")
            assertEquals("T", card.card.title)
        }
        val ok = read("""{"v":1,"blocks":[{"type":"card","title":"T","price":{"amount":0,"currency":"JPY"}}]}""")
            .single() as ChatlyBlock.Card
        assertEquals(0L, ok.card.price?.amount)
    }
}
