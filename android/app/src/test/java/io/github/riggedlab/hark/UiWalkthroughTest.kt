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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowWindowManagerGlobal
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiWalkthroughTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

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
    fun walkthroughAllScreensAndVerifyZeroLayoutJump() {
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        // 1. 待机态模式 B「主客气泡流」：验证无分贝数字、例外标签「愉悦 · 笑声 / 轻声」、快筛胶囊、远场静默胶囊，以及底部毛玻璃胶囊栏基线
        rule.onNodeWithText("Hark").assertIsDisplayed()
        rule.onNodeWithText("只看我说").assertIsDisplayed()
        rule.onNodeWithText("愉悦 · 笑声").assertIsDisplayed()
        rule.onNodeWithText("轻声").assertIsDisplayed()
        rule.onNodeWithText("已静默 1 段邻桌远场声 · 展开").assertIsDisplayed()
        rule.onAllNodesWithText("-19.6dB", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("中性", substring = true).assertCountEquals(0)
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
        rule.mainClock.advanceTimeBy(120)
        rule.waitForIdle()
        rule.onAllNodesWithText("张工").onFirst().assertIsDisplayed()
        captureAllWindows("09-search-filtered")
    }
}
