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
                        .put(JSONObject().put("start", 1.2).put("end", 9.5).put("text", "所以感觉还是要先把手机直连和云端信箱两条路径做干净，出门在外直接手机出字，回家自动补传归档。").put("speaker", "spk_01").put("db", -19.6).put("mood", "positive").put("mood_score", 0.62).put("vocal_tag", "笑声"))
                        .put(JSONObject().put("start", 10.4).put("end", 18.2).put("text", "好的没问题，那我们周五下午三点再过一遍超薄磁吸外壳的公差和电池仓倒角。").put("speaker", "spk_02").put("db", -29.8).put("mood", "neutral").put("mood_score", 0.05)),
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
                        .put(JSONObject().put("start", 0.5).put("end", 6.8).put("text", "备忘一下今晚把本地 hark timeline 查询和手机端磁带走带界面一起验收。").put("speaker", "other_1").put("db", -20.4).put("mood", "neutral").put("mood_score", 0.04)),
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

        // 1. 待机态「文字」页（带多说话人对话卡片）+ 验证品牌名为简洁的 Hark + 记录 Tab 纵坐标基线
        rule.onNodeWithText("Hark").assertIsDisplayed()
        val tabBoundsIdle = rule.onNodeWithText("文字").fetchSemanticsNode().boundsInRoot
        captureAllWindows("01-events-idle")

        // 2. 切换到「走带录音中」状态，推进 120Hz 帧时钟生成平滑声波条，验证下方 TabRow 纵坐标 0 像素跳动！
        AppState.update {
            it.copy(
                link = LinkState.STREAMING,
                deviceName = "Hark DT-1",
                batteryMv = 4050,
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
        // 先等待 AnimatedContent(160ms) 完成过渡使频谱组件上屏，再推进多帧渲染完整声波带
        rule.mainClock.advanceTimeBy(220)
        repeat(24) { step ->
            val v = (0.28f + 0.54f * kotlin.math.abs(kotlin.math.sin(step * 0.65f)))
            AppState.update { it.copy(lastWriteNanos = System.nanoTime(), liveLevel = v, liveSpeech = v > 0.45f) }
            rule.mainClock.advanceTimeBy(110)
        }
        rule.waitForIdle()
        val tabBoundsStreaming = rule.onNodeWithText("文字").fetchSemanticsNode().boundsInRoot
        assertEquals("切换录音状态时下方导航栏不允许上下跳动", tabBoundsIdle.top, tabBoundsStreaming.top, 2.0f)
        captureAllWindows("02-events-streaming")

        // 3. 点开顶部方角硬件状态铭牌 -> 验证顶栏锚定浮层直接完整弹出，「停止录音」位于屏幕中上方绝不被底部裁切！
        rule.onNodeWithContentDescription("打开设备状态").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("设备状态").assertIsDisplayed()
        rule.onNodeWithText("设备电量").assertIsDisplayed()
        val stopBounds = rule.onNodeWithText("停止录音").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val screenH = rule.activity.window.decorView.height.coerceAtLeast(2000)
        assertTrue("停止录音按钮必须位于屏幕上半区，杜绝任何底部裁切 ($stopBounds vs $screenH)", stopBounds.bottom < screenH * 0.68f)
        captureAllWindows("03-telemetry-sheet")

        // 从状态浮层点「设置」进入独立设置页，展开 3 步新手引导核对视觉
        rule.onNodeWithText("设置").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("转写路径").assertIsDisplayed()
        rule.onNodeWithText("直连转写服务").assertIsDisplayed()
        rule.onNodeWithText("配置指引").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("豆包语音 3 步配置").assertIsDisplayed()
        rule.onNodeWithText("测试并启用").assertIsDisplayed()
        captureAllWindows("04-settings-direct-asr")

        // 切换到 OpenAI 兼容查看一键平台预设（硅基流动 / Groq），再切回豆包语音
        rule.onNodeWithText("OpenAI 兼容").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("硅基流动").assertIsDisplayed()
        captureAllWindows("04b-settings-openai-presets")
        rule.onNodeWithText("豆包语音").performClick()
        rule.waitForIdle()

        // 在设置页切换到未配置的「云端信箱同步」：验证后台实际模式仍保持 DIRECT_ASR，按钮变为「保存并启用」
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        rule.onNodeWithText("云端信箱同步").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("云端盲信箱与端到端密钥").assertIsDisplayed()
        rule.onNodeWithText("保存并启用").assertIsDisplayed()
        assertEquals(WorkMode.DIRECT_ASR, Config.mode(ctx))
        captureAllWindows("05-settings-cloud")

        // 返回主工作台，切到「录音」页，再进一次设置并返回，验证仍停留在「录音」页（状态不丢失）
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("录音").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("16:40:00 – 16:40:03").assertIsDisplayed()
        captureAllWindows("06-recordings-tape")

        rule.onNodeWithText("人物").performClick()
        rule.waitForIdle()
        captureAllWindows("07-people-roster")

        rule.onNodeWithText("地点").performClick()
        rule.waitForIdle()
        captureAllWindows("09-places")

        // 测试全局即时搜索交互（先刷新音频写入时间戳避免走带进入等待流态，再命中高亮词条“外壳”）
        AppState.update { it.copy(lastWriteNanos = System.nanoTime(), liveLevel = 0.52f) }
        rule.onNodeWithText("文字").performClick()
        rule.onNodeWithContentDescription("搜索记录").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("搜索对话文字、人物或地点…").performTextInput("外壳")
        AppState.update { it.copy(lastWriteNanos = System.nanoTime(), liveLevel = 0.52f) }
        rule.mainClock.advanceTimeBy(120)
        rule.waitForIdle()
        rule.onNodeWithText("张工").assertIsDisplayed()
        captureAllWindows("08-search-filtered")
    }
}
