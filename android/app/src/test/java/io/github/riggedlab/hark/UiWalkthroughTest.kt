package io.github.riggedlab.hark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiWalkthroughTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun restoreTimelineStyle() {
        // Activity 在 @Before 之前读取偏好，不能把上个用例的无框流留给下一个用例。
        Config.setFeedStyle(ApplicationProvider.getApplicationContext(), FeedStyle.DIALOGUE)
    }

    @Before
    fun seedRealisticData() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val chunkDir = RecorderService.chunkDir(ctx).apply { mkdirs() }
        val sampleOpus = File("../../internal/asr/probe.opus").takeIf { it.exists() }?.readBytes() ?: ByteArray(64)

        // 写入 2 段真实录音与多角色转写数据（含长文本、声纹人物、地点）
        val rec1 = "20261112-164000.opus"
        val rec2 = "20261112-151500.opus"
        File(chunkDir, rec1).writeBytes(sampleOpus)
        File(chunkDir, rec2).writeBytes(sampleOpus)

        // 预置两条不同包络形状的波形缓存，检验内嵌磁带声波窗真实渲染效果
        val waveDir = File(ctx.filesDir, "waves").apply { mkdirs() }
        val wave1 = ByteArray(120) { i -> ((kotlin.math.abs(kotlin.math.sin(i * 0.19)) * 0.65 + 0.15) * 255).toInt().coerceIn(20, 235).toByte() }
        val wave2 = ByteArray(90) { i -> ((kotlin.math.abs(kotlin.math.cos(i * 0.31)) * 0.48 + 0.12) * 255).toInt().coerceIn(16, 210).toByte() }
        File(waveDir, "20261112-164000.wave").writeBytes(wave1)
        File(waveDir, "20261112-151500.wave").writeBytes(wave2)

        // 声纹人物与重命名
        Speakers.receive(
            ctx,
            "speakers_mac",
            1,
            JSONObject().put(
                "items",
                JSONObject()
                    .put("spk_01", JSONObject().put("first_seen", "2026-11-10").put("sample", "所以感觉还是要先把插件接口收敛干净，大家按需扩展。"))
                    .put("spk_02", JSONObject().put("first_seen", "2026-11-11").put("sample", "好的没问题，那我们周五下午三点过一遍硬件打样结构。")),
            ),
        )
        Speakers.edit(ctx, "spk_01", name = "我")
        Speakers.edit(ctx, "spk_02", name = "张工")

        Transcripts.save(
            ctx,
            JSONObject()
                .put("name", rec1)
                .put("vp_ver", Voiceprint.SCHEMA_VERSION)
                .put("sm_audio_ver", SceneAndMood.AUDIO_SCHEMA_VERSION)
                .put("duration_s", 298.0)
                .put("text", "所以感觉还是要先把插件接口收敛干净。好的没问题，周五下午三点过一遍硬件打样结构。")
                .put(
                    "scene_tags",
                    JSONArray()
                        .put(JSONObject().put("label", "敲键盘").put("score", 0.82))
                        .put(JSONObject().put("label", "笑声").put("score", 0.64)),
                )
                .put(
                    "segments",
                    JSONArray()
                        .put(JSONObject().put("start", 1.2).put("end", 9.5).put("text", "所以感觉还是要先把手机直连和云端信箱两条路径做干净，出门在外直接手机出字，回家自动补传归档。").put("speaker", "spk_01").put("db", -19.6).put("snr_db", 28.4).put("proximity", "near").put("mood", "positive").put("mood_score", 0.62).put("vocal_tag", "笑声"))
                        .put(JSONObject().put("start", 10.4).put("end", 18.2).put("text", "好的没问题，那我们周五下午三点再过一遍超薄磁吸外壳的公差和电池仓倒角。").put("speaker", "spk_02").put("db", -29.8).put("snr_db", 19.2).put("proximity", "near").put("mood", "neutral").put("mood_score", 0.05))
                        .put(JSONObject().put("start", 21.0).put("end", 25.5).put("text", "隔壁工位下周团建去哪儿吃火锅？").put("speaker", "other_2").put("db", -34.2).put("snr_db", 8.1).put("proximity", "far").put("mood", "neutral").put("mood_score", 0.02)),
                ),
        )
        Transcripts.save(
            ctx,
            JSONObject()
                .put("name", rec2)
                .put("vp_ver", Voiceprint.SCHEMA_VERSION)
                .put("sm_audio_ver", SceneAndMood.AUDIO_SCHEMA_VERSION)
                .put("duration_s", 185.0)
                .put("text", "备忘一下今晚跑通本地 hark timeline 查询。")
                .put(
                    "scene_tags",
                    JSONArray().put(JSONObject().put("label", "室内安静").put("score", 0.91)),
                )
                .put(
                    "segments",
                    JSONArray()
                        .put(JSONObject().put("start", 0.5).put("end", 6.8).put("text", "备忘一下今晚把本地 hark timeline 查询和手机端磁带走带界面一起验收。").put("speaker", "other_1").put("db", -41.5).put("snr_db", 16.8).put("proximity", "near").put("mood", "neutral").put("mood_score", 0.04)),
                ),
        )

        AsrConfig.save(
            ctx,
            AsrConfig(
                provider = AsrProvider.VOLCENGINE,
                baseUrl = AsrProvider.VOLCENGINE.defaultUrl,
                apiKey = "demo-volc-key-placeholder",
                model = "volc.bigasr.auc_turbo",
            ),
        )
        Config.setMode(ctx, WorkMode.DIRECT_ASR)
        Config.setFeedStyle(ctx, FeedStyle.DIALOGUE)
        // 触发 UI 立即重读新写入的模拟录音与转写记录
        AppState.update { it.copy(textVersion = it.textVersion + 1) }
    }

    private fun captureAllWindows(name: String) {
        rule.waitForIdle()
        val outDir = File("build/reports/ui-walkthrough").apply { mkdirs() }
        val decor = rule.activity.window.decorView
        val w = decor.width.coerceAtLeast(1080)
        val h = decor.height.coerceAtLeast(2400)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        decor.draw(canvas)

        // 同时合成 Dialog / ModalBottomSheet 等独立 Window 图层
        runCatching {
            val wmClass = Class.forName("android.view.WindowManagerGlobal")
            val wm = wmClass.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val views = wmClass.getMethod("getRootViews", android.os.IBinder::class.java)
                .let { null } ?: run {
                val field = wmClass.getDeclaredField("mViews").apply { isAccessible = true }
                field.get(wm) as List<android.view.View>
            }
            for (v in views) {
                if (v !== decor && v.isShown) {
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    canvas.save()
                    canvas.translate(loc[0].toFloat(), loc[1].toFloat())
                    v.draw(canvas)
                    canvas.restore()
                }
            }
        }
        FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun mediaOnlyEventStaysFoldedUntilOpenedInBothFeedStyles() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sentence = "这句来自手机外放。"
        val segment = JSONObject().put("start", 0.0).put("end", 1.5).put("text", sentence)
            .put("proximity", Proximity.MEDIA).put("mood", "positive").put("mood_score", 0.9)
        Transcripts.save(
            ctx,
            JSONObject().put("name", "20261112-170000.opus").put("text", sentence)
                .put("duration_s", 2).put("segments", JSONArray().put(segment)),
        )
        AppState.update { it.copy(textVersion = it.textVersion + 1) }

        val toggle = "已静默 1 段外放背景声 · 展开"
        rule.waitUntil(5_000) { rule.onAllNodesWithText(toggle).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(sentence).assertDoesNotExist()
        rule.onNodeWithText(toggle).performClick()
        rule.onNodeWithText(sentence).assertIsDisplayed()

        rule.runOnUiThread { Config.setFeedStyle(ctx, FeedStyle.EDITORIAL) }
        val editorialToggle = "展开 1 段背景声"
        rule.waitUntil(5_000) { rule.onAllNodesWithText(editorialToggle).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(sentence).assertDoesNotExist()
        rule.onNodeWithText(editorialToggle).performClick()
        rule.onNodeWithText(sentence).assertIsDisplayed()
    }

    @Test
    fun walkthroughAllScreensAndVerifyZeroLayoutJump() {
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onNodeWithText("只看我说").isDisplayed() }

        // 1. 待机态模式 B「主客气泡流」：验证严格从近到远排序（16:40:10 张工回复在 16:40:01 之前发言上方，且均在 15:15 备忘上方）、无分贝数字、例外标签与底部胶囊基线
        rule.onNodeWithText("Hark").assertIsDisplayed()
        rule.onNodeWithText("只看我说").assertIsDisplayed()
        rule.onNodeWithText("愉悦 · 笑声").assertIsDisplayed()
        rule.onNodeWithText("轻声").assertIsDisplayed()
        rule.onNodeWithText("已静默 1 段邻桌远场声 · 展开").assertIsDisplayed()
        rule.onAllNodesWithText("-19.6dB", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("中性", substring = true).assertCountEquals(0)

        val newerTurnTop = rule.onNodeWithText("周五下午三点再过一遍超薄磁吸外壳", substring = true).fetchSemanticsNode().boundsInRoot.top
        val olderTurnTop = rule.onNodeWithText("先把手机直连和云端信箱两条路径做干净", substring = true).fetchSemanticsNode().boundsInRoot.top
        val oldestMemoTop = rule.onNodeWithText("备忘一下今晚把本地 hark timeline", substring = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue("同段内最新发言必须排在更早发言上方 ($newerTurnTop < $olderTurnTop)", newerTurnTop < olderTurnTop)
        assertTrue("近期对话必须排在更早时段上方 ($olderTurnTop < $oldestMemoTop)", olderTurnTop < oldestMemoTop)

        val dockBoundsIdle = rule.onNodeWithText("人物").fetchSemanticsNode().boundsInRoot
        captureAllWindows("01-mode-b-dialogue-idle")

        // 2. 切换到「正在录音」状态：右上角变为呼吸红点「记录中 · 86%」，无顶栏跳动、无冗余磁带横条
        AppState.update {
            it.copy(
                link = LinkState.STREAMING,
                deviceName = "Hark DT-1",
                batteryMv = 4070,
                bytesPerSec = 2400,
                totalPackets = 1200,
                lostPackets = 1,
                silentFrames = 420,
                currentChunk = "20261113-001500.opus",
                currentChunkSeconds = 162.0,
                lastWriteNanos = System.nanoTime(),
                liveLevel = 0.68f,
                liveSpeech = true,
            )
        }
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        val dockBoundsStreaming = rule.onNodeWithText("人物").fetchSemanticsNode().boundsInRoot
        assertEquals("切换录音状态时导航栏不允许上下跳动", dockBoundsIdle.top, dockBoundsStreaming.top, 2.0f)
        captureAllWindows("02-mode-b-dialogue-streaming")

        // 3. 点开右上角胶囊 -> 顶栏锚定设备状态浮层，「停止录音」位于上半区绝不裁切
        rule.onNodeWithContentDescription("打开设备状态").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("设备状态").assertIsDisplayed()
        rule.onNodeWithText("设备电量").assertIsDisplayed()
        val stopBounds = rule.onNodeWithText("停止录音").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val screenH = rule.activity.window.decorView.height.coerceAtLeast(2000)
        assertTrue("停止录音按钮必须位于屏幕上半区 ($stopBounds vs $screenH)", stopBounds.bottom < screenH * 0.68f)
        captureAllWindows("03-telemetry-sheet")

        // 从设备状态浮层点开「原始录音归档」验证切片波形与状态展示，随后返回
        rule.onNodeWithText("查看切片与波形 ›").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("原始录音归档").assertIsDisplayed()
        rule.onNodeWithText("16:40:00 – 16:40:03").assertIsDisplayed()
        captureAllWindows("04-raw-recordings")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()

        // 4. 进入设置页：验证新增的「记忆流视图」双模式切换卡，切换为模式 C「无框左轴流」
        rule.onNodeWithContentDescription("设置").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("记忆流视图").assertIsDisplayed()
        rule.onNodeWithText("主客气泡流").assertIsDisplayed()
        rule.onNodeWithText("无框左轴流").performClick()
        rule.waitForIdle()

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(FeedStyle.EDITORIAL, Config.feedStyle(ctx))
        captureAllWindows("05-settings-feed-style")

        // 在设置页顺带验证切换到未配置的「云端信箱同步」不会破坏当前 DIRECT_ASR
        rule.onNodeWithText("云端信箱同步").performClick()
        rule.waitForIdle()
        assertEquals(WorkMode.DIRECT_ASR, Config.mode(ctx))

        // 5. 返回主工作台：验证立即生效为模式 C「今日脉络（无框左轴流）」，长对话默认摘要预览、点按原位展开逐字稿
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("今日脉络").onFirst().assertIsDisplayed()
        rule.onNodeWithText("有效开口").assertIsDisplayed()
        rule.onNodeWithText("与 张工 的对话").assertIsDisplayed()
        captureAllWindows("06-mode-c-editorial-collapsed")

        rule.onNodeWithText("展开全篇 2 段 · 已静默 1 段背景声").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("收起逐字稿").assertIsDisplayed()
        captureAllWindows("06b-mode-c-editorial-expanded")

        // 6. 底部悬浮毛玻璃胶囊切换「人物」与「足迹」
        rule.onNodeWithText("人物").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("佩戴者").assertIsDisplayed()
        captureAllWindows("07-people-roster")

        rule.onNodeWithText("足迹").performClick()
        rule.waitForIdle()
        captureAllWindows("08-places")

        // 7. 切回记忆流，点右下独立毛玻璃搜索圆钮测试实时检索
        rule.onAllNodesWithText("今日脉络").onLast().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("搜索记录").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("搜索对话文字、人物或地点…").performTextInput("外壳")
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        rule.onAllNodesWithText("张工").onFirst().assertIsDisplayed()
        captureAllWindows("09-search-filtered")
    }

    @Test
    fun cardActionTargetsSingleSentenceInBothTimelineStyles() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "20261112-164000.opus"
        val file = File(Transcripts.dir(ctx), "$name.json")
        val root = JSONObject(file.readText())
        val old = root.getJSONArray("segments")
        root.put("segments", JSONArray().put(old.getJSONObject(0))
            .put(JSONObject().put("start", 9.6).put("end", 10.2).put("text", "第二句命中。").put("speaker", "spk_01").put("db", -19.6).put("proximity", "near"))
            .put(old.getJSONObject(1)).put(old.getJSONObject(2)))
        Transcripts.save(ctx, root)
        AppState.update { it.copy(textVersion = it.textVersion + 1) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("第二句命中。", substring = true).fetchSemanticsNodes().isNotEmpty() }

        fun chooseSecondSentence() {
            val card = hasTestTag("speaker-label-card") and hasAnyDescendant(hasText("第二句命中。", substring = true))
            rule.onNode(hasContentDescription("标注说话人") and hasAnyAncestor(card)).performClick()
            rule.onNodeWithText("选择要标注的句子").assertIsDisplayed()
            rule.onNode(hasText("第二句命中。") and hasAnyAncestor(isDialog())).performClick()
            rule.onNodeWithText("这是我").assertIsDisplayed()
            rule.onNodeWithText("不是我").assertIsDisplayed()
            rule.onNode(hasText("第二句命中。") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        }
        chooseSecondSentence()
        captureAllWindows("10-voice-label-dialogue")
        rule.onNodeWithText("这是…（选已命名的人）").performClick()
        rule.waitForIdle()
        rule.onNode(hasText("张工") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        captureAllWindows("11-voice-label-person-picker")
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithText("无框左轴流").performClick()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        // 收起的长对话没有显示原句。可见的短记入口直接打开单句标注。
        rule.onNodeWithContentDescription("标注说话人").performClick()
        rule.onNodeWithText("选择要标注的句子").assertDoesNotExist()
        rule.onNodeWithText("这是我").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()

        rule.onNodeWithText("展开全篇 2 段 · 已静默 1 段背景声").performClick()
        chooseSecondSentence()
        captureAllWindows("12-voice-label-editorial")
        rule.onNodeWithText("不是我").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("标注说话人").fetchSemanticsNodes().isEmpty() }
        val labels = JSONObject(File(ctx.filesDir, "voice_labels.json").readText()).getJSONObject(name)
        assertEquals(setOf("9.6:10.2"), labels.keys().asSequence().toSet())
        assertEquals(Voiceprint.NOT_SELF, labels.getString("9.6:10.2"))
    }

    @Test
    fun holdingBubbleTextPlaysAtDoubleSpeedUntilRelease() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "20261112-164000.opus"
        val audio = File(RecorderService.chunkDir(ctx), name)
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(audio.absolutePath),
            ShadowMediaPlayer.MediaInfo(298_000, 0),
        )
        val player = ReflectionHelpers.getField<Player>(rule.activity, "player")
        val text = "先把手机直连和云端信箱两条路径做干净"
        rule.waitUntil(5_000) { rule.onNodeWithText(text, substring = true).isDisplayed() }
        val bubble = rule.onNodeWithText(text, substring = true)
        // 自动推进会让播放进度动画在断言前跑到卡片结尾。
        rule.mainClock.autoAdvance = false
        try {
            bubble.performTouchInput {
                down(center)
                advanceEventTime(650)
                move()
            }
            rule.runOnUiThread {
                assertEquals(name, player.current?.name)
                assertTrue("playback stopped at ${player.positionMs} ms", player.playing)
                assertEquals(2f, player.speed, 0f)
            }
        } finally {
            bubble.performTouchInput { up() }
            rule.mainClock.autoAdvance = true
        }
        rule.onNodeWithText("这是我").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1f, player.speed, 0f) }
    }

    @Test
    fun homeFeedLazyLoadsFirstPageWithoutParsingEntireHistory() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // 额外写入 36 条更早日期的历史转写（超过首页 INITIAL_CHUNK_BUDGET = 28）
        for (i in 1..36) {
            val hh = (i / 6).toString().padStart(2, '0')
            val mm = ((i % 6) * 10).toString().padStart(2, '0')
            val name = "20261101-${hh}${mm}00.opus"
            Transcripts.save(
                ctx,
                JSONObject()
                    .put("name", name)
                    .put("duration_s", 60.0)
                    .put("text", "十天前的远古历史记录第 $i 条")
                    .put(
                        "segments",
                        JSONArray().put(
                            JSONObject().put("start", 1.0).put("end", 5.0).put("text", "十天前的远古历史记录第 $i 条").put("proximity", "near"),
                        ),
                    ),
            )
        }
        AppState.update { it.copy(textVersion = it.textVersion + 1) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("11月1日", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()

        // 首屏仅解析最新 INITIAL_CHUNK_BUDGET(28) 条（2 条 11.12 + 最新 26 条 11.01），最早的第 1 条绝不进入首屏树！
        rule.onNodeWithText("11月12日", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("十天前的远古历史记录第 1 条", substring = true).assertCountEquals(0)
    }
}
