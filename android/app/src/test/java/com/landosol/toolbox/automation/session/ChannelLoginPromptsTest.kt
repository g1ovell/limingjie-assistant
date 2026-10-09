package com.landosol.toolbox.automation.session

import com.landosol.toolbox.protocol.bilibili.GameServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLoginPromptsTest {
    private val m4399 = GameServer.CN_4399.packageName
    private val aligames = GameServer.CN_ALIGAMES.packageName

    private fun node(
        text: String? = null,
        bounds: ChannelPromptBounds = ChannelPromptBounds(0, 0, 10, 10),
        clickable: Boolean = false,
        children: List<ChannelPromptNode> = emptyList(),
    ) = ChannelPromptNode(text = text, clickable = clickable, bounds = bounds, children = children)

    /** 4399 SDK 原生对话框（2026-10 用户录屏）：标题、正文、拒绝 / 已阅读并同意。 */
    private fun agreementDialog(pkg: String = m4399) = ChannelPromptWindow(
        pkg,
        node(
            bounds = ChannelPromptBounds(475, 90, 1445, 772),
            children = listOf(
                node("4399用户协议及隐私政策", ChannelPromptBounds(535, 165, 910, 205)),
                node("感谢你使用本游戏。在开始使用我们的服务前……", ChannelPromptBounds(535, 240, 1380, 540)),
                node("拒绝", ChannelPromptBounds(475, 670, 960, 772), clickable = true),
                node("已阅读并同意", ChannelPromptBounds(960, 670, 1445, 772), clickable = true),
            ),
        ),
    )

    /** 4399 游戏盒竖屏「账号授权」页。 */
    private fun authorizationPage(pkg: String = ChannelLoginPromptRules.PACKAGE_4399_GAME_CENTER) = ChannelPromptWindow(
        pkg,
        node(
            bounds = ChannelPromptBounds(0, 0, 1080, 1920),
            children = listOf(
                node("账号授权", ChannelPromptBounds(160, 150, 360, 210)),
                node("寒傲", ChannelPromptBounds(240, 520, 330, 580)),
                node("切换其它账号", ChannelPromptBounds(440, 740, 660, 800), clickable = true),
                node("授权并登录", ChannelPromptBounds(45, 920, 1035, 1040), clickable = true),
            ),
        ),
    )

    /** 九游「登录历史」（2026-10-05 调试包 frame/latest.jpg，1920×1080）。 */
    private fun loginHistory(recentBadges: Int = 1) = ChannelPromptWindow(
        aligames,
        node(
            bounds = ChannelPromptBounds(528, 283, 1392, 797),
            children = listOf(
                node("登录历史", ChannelPromptBounds(576, 320, 744, 375)),
                node("登录其他手机号", ChannelPromptBounds(1056, 320, 1344, 375), clickable = true),
                node(
                    bounds = ChannelPromptBounds(576, 446, 1344, 770),
                    clickable = true,
                    children = List(recentBadges) { index ->
                        node("最近登录", ChannelPromptBounds(1164, 690 + index, 1308, 745 + index))
                    } + node("九游玩家2314041147", ChannelPromptBounds(612, 485, 1020, 540)),
                ),
            ),
        ),
    )

    @Test
    fun `4399 agreement taps only the accept button`() {
        val match = ChannelLoginPromptMatcher.find(
            listOf(agreementDialog()),
            ChannelLoginPromptRules.forServer(GameServer.CN_4399),
        )

        assertEquals("4399-agreement", match?.rule?.id)
        assertEquals("已阅读并同意", match?.target?.text)
        assertTrue(match!!.target.bounds.centerX > 960f)
    }

    @Test
    fun `4399 account authorization is accepted from the 4399 game center`() {
        val match = ChannelLoginPromptMatcher.find(
            listOf(authorizationPage()),
            ChannelLoginPromptRules.forServer(GameServer.CN_4399),
        )

        assertEquals("4399-account-authorization", match?.rule?.id)
        assertEquals("授权并登录", match?.target?.text)
    }

    @Test
    fun `aligames login history taps the most recent account entry`() {
        val match = ChannelLoginPromptMatcher.find(
            listOf(loginHistory()),
            ChannelLoginPromptRules.forServer(GameServer.CN_ALIGAMES),
        )

        assertEquals("aligames-login-history", match?.rule?.id)
        assertEquals(ChannelPromptBounds(1164, 690, 1308, 745), match?.target?.bounds)
    }

    @Test
    fun `an ambiguous target is never tapped`() {
        assertNull(
            ChannelLoginPromptMatcher.find(
                listOf(loginHistory(recentBadges = 2)),
                ChannelLoginPromptRules.forServer(GameServer.CN_ALIGAMES),
            ),
        )
    }

    @Test
    fun `a button without its dialog title is not enough evidence`() {
        val buttonOnly = ChannelPromptWindow(m4399, node(children = listOf(node("已阅读并同意", clickable = true))))

        assertNull(ChannelLoginPromptMatcher.find(listOf(buttonOnly), ChannelLoginPromptRules.forServer(GameServer.CN_4399)))
    }

    @Test
    fun `windows of other packages are ignored`() {
        assertNull(
            ChannelLoginPromptMatcher.find(
                listOf(agreementDialog(pkg = "com.example.other")),
                ChannelLoginPromptRules.forServer(GameServer.CN_4399),
            ),
        )
        // The 4399 agreement must come from the 4399 client itself, not from the game center.
        assertNull(
            ChannelLoginPromptMatcher.find(
                listOf(agreementDialog(pkg = ChannelLoginPromptRules.PACKAGE_4399_GAME_CENTER)),
                ChannelLoginPromptRules.forServer(GameServer.CN_4399),
            ),
        )
    }

    @Test
    fun `channels without known prompts have no rules`() {
        assertTrue(ChannelLoginPromptRules.forServer(GameServer.CN_XIAOMI).isEmpty())
        assertTrue(ChannelLoginPromptRules.forServer(GameServer.CN_VIVO).isEmpty())
        assertTrue(ChannelLoginPromptRules.forServer(GameServer.CN_BILIBILI).isEmpty())
        assertTrue(ChannelLoginPromptRules.forServer(null).isEmpty())
    }

    @Test
    fun `whitespace inside SDK labels does not defeat matching`() {
        val spaced = ChannelPromptWindow(
            m4399,
            node(
                children = listOf(
                    node("4399 用户协议及隐私政策"),
                    node(" 已阅读并同意 ", ChannelPromptBounds(960, 670, 1445, 772), clickable = true),
                ),
            ),
        )

        assertEquals(
            "4399-agreement",
            ChannelLoginPromptMatcher.find(listOf(spaced), ChannelLoginPromptRules.forServer(GameServer.CN_4399))?.rule?.id,
        )
    }

    private class FakeScreen(var windows: List<ChannelPromptWindow>, var accept: Boolean = true) : ChannelPromptScreen {
        val activated = mutableListOf<ChannelPromptMatch>()

        override fun windows(): List<ChannelPromptWindow> = windows

        override suspend fun activate(match: ChannelPromptMatch): Boolean {
            activated += match
            return accept
        }
    }

    private fun assistant(screen: FakeScreen, server: GameServer = GameServer.CN_4399) = ChannelLoginPromptAssistant(
        screen = screen,
        rules = { ChannelLoginPromptRules.forServer(server) },
        requiredSightings = 2,
        cooldownMillis = 4_000L,
        maxActivationsPerRule = 3,
    )

    @Test
    fun `the assistant waits for a second sighting before tapping`() = runTest {
        val screen = FakeScreen(listOf(agreementDialog()))
        val assistant = assistant(screen)

        assertEquals(ChannelPromptStep.Seen("4399 用户协议及隐私政策"), assistant.step(0L))
        assertTrue(screen.activated.isEmpty())
        assertEquals(ChannelPromptStep.Activated("4399 用户协议及隐私政策"), assistant.step(500L))
        assertEquals(1, screen.activated.size)
    }

    @Test
    fun `a dialog that disappears between polls is not tapped`() = runTest {
        val screen = FakeScreen(listOf(agreementDialog()))
        val assistant = assistant(screen)

        assistant.step(0L)
        screen.windows = emptyList()
        assertEquals(ChannelPromptStep.None, assistant.step(500L))
        screen.windows = listOf(agreementDialog())
        assertTrue(assistant.step(1_000L) is ChannelPromptStep.Seen)
        assertTrue(screen.activated.isEmpty())
    }

    @Test
    fun `agreement then authorization advance one after the other`() = runTest {
        val screen = FakeScreen(listOf(agreementDialog()))
        val assistant = assistant(screen)

        assistant.step(0L)
        assistant.step(500L)
        screen.windows = listOf(authorizationPage())
        assistant.step(1_000L)
        assertEquals(ChannelPromptStep.Activated("4399 账号授权"), assistant.step(1_500L))
        assertEquals(listOf("已阅读并同意", "授权并登录"), screen.activated.map { it.target.text })
    }

    @Test
    fun `a dialog that stays after the budget stops the reset`() = runTest {
        val screen = FakeScreen(listOf(agreementDialog()))
        val assistant = assistant(screen)
        val activatedAt = mutableListOf<Long>()
        var exhaustedAt: Long? = null

        // Poll every 500 ms as the workflow does, with the dialog never going away.
        for (now in 0L..20_000L step 500L) {
            when (assistant.step(now)) {
                is ChannelPromptStep.Activated -> activatedAt += now
                is ChannelPromptStep.Exhausted -> { exhaustedAt = now; break }
                else -> Unit
            }
        }

        // Second sighting at 500 ms, then one tap per 4 s cooldown, then a stop.
        assertEquals(listOf(500L, 4_500L, 8_500L), activatedAt)
        assertEquals(12_500L, exhaustedAt)
        assertEquals(3, screen.activated.size)
    }

    @Test
    fun `a rejected tap stops instead of retrying blindly`() = runTest {
        val screen = FakeScreen(listOf(agreementDialog()), accept = false)
        val assistant = assistant(screen)

        assistant.step(0L)

        assertTrue(assistant.step(500L) is ChannelPromptStep.Exhausted)
    }

    @Test
    fun `rules follow the selected server`() = runTest {
        val screen = FakeScreen(listOf(agreementDialog()))
        val assistant = assistant(screen, server = GameServer.CN_XIAOMI)

        assertEquals(ChannelPromptStep.None, assistant.step(0L))
        assertEquals(ChannelPromptStep.None, assistant.step(500L))
        assertTrue(screen.activated.isEmpty())
    }
}
