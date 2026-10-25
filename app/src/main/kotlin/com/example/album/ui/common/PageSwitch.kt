package com.example.album.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 换页动画 = 9.9 `AlbumAnimator` reason 10/11/12/13 的**逐项 z 动画**，两段串行、只在尾巴上重叠：
 *
 * ```
 * 0 ──────────── 240ms ──────────── 300ms ───────────── 540ms
 *   旧页逐项出（easeIn, α=(1−prog)³, prog=(t/300)³）
 *                 └ 新页逐项进（easeOut, α=prog³，晚 240ms 起跑）
 * ```
 *
 * 关键点（同机原版录屏 48.59fps 逐帧实测，见 `docs/REF9.9_MECHANICS.md` §5）：
 * - 两页**从不同时可见**（重叠的只是两边都 <5% 的尾巴）⇒ 没有"双影"。
 * - 每一项只有 **z 平移**，缩放是透视投影出来的 `1/(1−z)`，**而且投影中心是屏幕中心**
 *   ⇒ 离屏心越远的项位移越大（"从画面中心穿过去"），不是原地放大。
 * - z 每项随机（0…1）；`2×2 大格` 固定 −1 / +0.7，`banner` 在"返回"方向固定 0.25。
 */
const val PAGE_SWITCH_MILLIS = 300

/**
 * 9.9 的 `AccelerateInterpolator(2)` 口径：`easeIn = t³`。
 *
 * ⚠️ 用户早就反馈过"进出页面动画曲线总是感觉慢"（2026-10-18 又说一次）：我们之前用的是 Compose 的
 * `EaseIn/EaseOut`（cubic-bezier(0.42,0,1,1) / (0,0,0.58,1)），**起步更黏、前 1/3 几乎不动**，
 * 所以同样的 300ms 看起来慢半拍。改成 9.9 的幂曲线：出 `t³`、进 `1−(1−t)³` —— 起步就有速度。
 */
val AlbumEaseIn: Easing = Easing { t -> t * t * t }

/**
 * 新页"进"这 300ms 从哪一刻起跑（相对换页起点）。
 *
 * 2026-10-24 r34 —— 用户原话：「**9.9 切换时的确会有一瞬间全屏空白，但是衔接的好，很柔和。你看看**」。
 * 原版录屏 `transition.mp4` 逐帧实测（4.0–5.05s）：出 4.14→4.39（≈250ms）、**空 4.39→4.55（≈160ms，
 * 只剩顶栏 + 状态栏，ink 6.5%、dark 1.6%）**、进 4.55→4.78（≈230ms）。所以"空白"本身是允许的，
 * 关键是**衔接要连续**：出页收干净、新页立刻接管，中间不出现硬切。
 *
 * 我们无顶栏（用户令），所以不能靠 chrome 撑墨量；做法是把两段的"肉眼不可见尾巴"叠在一起：
 * - 出页 α = `(1−prog)³`、prog=`(t/300)³` ⇒ 240ms 只剩 11.6%、260ms 4.3%、280ms 0.7%、300ms 0；
 * - 进页 240ms 起跑 ⇒ 260ms 0.7%、280ms 4.3%、300ms 11.6%、540ms 1.0；
 * - **逐毫秒扫描：没有任何一帧两边都 ≥5%**（双影彻底没有），也**没有一帧两边都 <1%**（不出现"整屏白"），
 *   总不透明度最低 ≈4%、只持续 52ms（≈3 帧）——比原版那 160ms 空档更短，这就是"柔和"的来源。
 * 总时长 540ms（原版 ≈640ms）。
 *
 * 调大 ⇒ 更接近原版的空档（300 = 严格串行、空 47ms 且两边都 <1%）；调小 ⇒ 双影会回来
 * （200 时已有 15 帧两边同时 ≥5%）。
 */
const val PAGE_SWITCH_ENTER_DELAY = 240L

/** 参与换页动画的页面（原版同一个 GL 场景里的三种 `StateType` + 设置页）。 */
enum class PageId { TIMELINE, FOLDER_LIST, FOLDER_GRID, SETTINGS }

/** 换页方向：INTO = 下钻（进子页）；OUT = 返回上级。 */
enum class PageSwitchDirection { INTO, OUT }

/** 一项属于哪类（决定 z 的固定值）：普通项 / 2×2 大格（road sign）/ banner。 */
enum class ItemDepthKind { NORMAL, ROAD_SIGN, BANNER }

/** 本页在这次换页里扮演的角色。 */
enum class PageRole { IDLE, EXITING, ENTERING }

/** 某一项此刻的 z（沿镜头轴的位移，0 = 到位）与 alpha。 */
class PageItemState(
    val z: Float,
    val alpha: Float,
)

/**
 * 全 App 一份：谁在出、谁在进、两段进度。
 *
 * 进度用 [Animatable] 而不是 nav 的 transition：两段是**串行**的，Compose 的 enter/exit 是并行的，
 * 自己拿一个时钟最直接（也方便 nav 那边只用"延迟 300ms 的淡入/淡出"把旧页多留 300ms）。
 */
@Stable
class PageSwitchState {

    /** 每次换页 +1：页面用 `remember(generation)` 决定自己这一轮是"出"还是"进"。 */
    var generation by mutableIntStateOf(0)
        private set

    var from by mutableStateOf<PageId?>(null)
        private set

    var to by mutableStateOf<PageId?>(null)
        private set

    var direction by mutableStateOf(PageSwitchDirection.INTO)
        private set

    /** 旧页"出"的进度 0→1（easeIn）。每次换页换一个新实例（初始 0），空闲时是 1。 */
    var exit by mutableStateOf(Animatable(1f))
        private set

    /** 新页"进"的进度 0→1（easeOut，晚 [PAGE_SWITCH_ENTER_DELAY] 起跑）。每次换页换一个新实例（初始 0），空闲时是 1。 */
    var enter by mutableStateOf(Animatable(1f))
        private set

    /**
     * 新页"进"这一段的 α 指数（默认 3 = 9.9 的 `f³`）。
     *
     * 只有"从设置页返回"这一种情况用 1（线性）：那一页是**我们自己的合成页**、返回时原版只是 200ms 窗口淡出
     * （父页静止不动），而我们的父页要"飞入"。若父页仍用 `f³`，它与设置页的 200ms 淡出叠在一起，
     * 头 100ms 两边加起来只有 ≈0.6 的不透明度 ⇒ 中间会闪过一丝白（用户 2026-10-18 报 D3「不行」）。
     * α 线性之后父页 α + 设置页 α ≈ 1，等于干净的交叉淡入。
     */
    var enterAlphaPower by mutableFloatStateOf(3f)
        private set

    private var job: Job? = null

    /**
     * 是否正在换页（540ms 内）。
     *
     * 9.9 的过渡态会**吞掉点击与返回**（`MainView.java:1259-1262,406-425`）；审计 E7 实测我们这里没有门禁
     * ⇒ 快速双击会连压两层，且 `start()` 会把前一段动画 cancel 掉（看起来"动画断了"）。
     *
     * ⚠️⚠️ 必须是**快照状态**，不许写成 `job?.isActive`（2026-10-24 致命 bug 的真因）：
     * `Job.isActive` 不是 snapshot 状态，在组合里读它**不会建立依赖** ⇒ 换页那一帧读到 `true`
     * （`AlbumNav` 里那层"吞输入"的全屏透明遮罩就是在此时挂上的），540ms 后 job 结束也不会重组，
     * 遮罩永久留在最上层，**全 App 触摸失效**（用户报「进文件夹页触摸无反应，回首页也没反应」：
     * 返回键不是指针事件所以还能退，退的时候又启动一次换页 ⇒ 重组又读到 true ⇒ 再挂一次）。
     */
    var isSwitching by mutableStateOf(false)
        private set

    /**
     * @param enterDelayMillis 新页"进"这一段的启动延迟，默认 [PAGE_SWITCH_ENTER_DELAY] = 240ms
     *   （= 出页只剩 11.6% 时起跑；见该常量的逐毫秒扫描数据：既不双影、也不出现整屏白）。
     *   传入 300 就是 9.9 的严格串行（多 47ms 全空档）；传入 200 会重新出现双影。
     */
    /**
     * 立刻回到"没有换页进行中"：各页 role = IDLE、α = 1、z = 0。
     * 用于"不需要页面级动画"的返回（从设置页返回：只让设置页淡出、父页完全静止）。
     */
    fun settle() {
        job?.cancel()
        job = null
        isSwitching = false
        from = null
        to = null
        exit = Animatable(1f)
        enter = Animatable(1f)
        generation++
    }

    fun start(
        scope: CoroutineScope,
        from: PageId?,
        to: PageId?,
        direction: PageSwitchDirection,
        enterDelayMillis: Long = PAGE_SWITCH_ENTER_DELAY,
        enterMillis: Int = PAGE_SWITCH_MILLIS,
        enterAlphaPower: Float = 3f,
    ) {
        if (from == null || to == null || from == to) return
        job?.cancel()
        this.from = from
        this.to = to
        this.direction = direction
        this.enterAlphaPower = enterAlphaPower
        // 换新实例 = 立刻回到 0（snapTo 是挂起函数，这里不能等它）
        val exitAnim = Animatable(0f)
        val enterAnim = Animatable(0f)
        exit = exitAnim
        enter = enterAnim
        generation++
        val gen = generation
        isSwitching = true
        val running = scope.launch {
            launch {
                if (enterDelayMillis > 0) delay(enterDelayMillis)
                enterAnim.animateTo(1f, tween(enterMillis, easing = AlbumEaseOut))
            }
            exitAnim.animateTo(1f, tween(PAGE_SWITCH_MILLIS, easing = AlbumEaseIn))
        }
        job = running
        // 正常播完 / 被下一次换页 cancel / 作用域已销毁（协程根本没启动）这三种情况都必须清标记，
        // 否则那层吞输入的遮罩会留在屏幕上（见 [isSwitching] 的注释）。用 generation 防止旧 job 清掉新 job 的标记。
        running.invokeOnCompletion { if (generation == gen) isSwitching = false }
    }
}

val LocalPageSwitch = staticCompositionLocalOf<PageSwitchState?> { null }

/** 本页这一轮是"出"还是"进"（按 [PageSwitchState.generation] 锁定，动画中途不会翻转）。 */
@Composable
fun rememberPageRole(page: PageId): PageRole {
    val switch = LocalPageSwitch.current ?: return PageRole.IDLE
    return remember(switch.generation) {
        when (page) {
            switch.to -> PageRole.ENTERING
            switch.from -> PageRole.EXITING
            else -> PageRole.IDLE
        }
    }
}

/**
 * 解算"某一项此刻的 z / alpha"——9.9 `AlbumAnimator.startAnimation` 各 case 的等价物。
 *
 * @param depth 该项自己的随机深度（`sRandom.nextFloat()`，每项固定一个）
 * @param kind 普通项 / 2×2 大格 / banner（后两类在原版里有固定的 z）
 */
fun pageItemState(
    role: PageRole,
    direction: PageSwitchDirection,
    enterProgress: Float,
    exitProgress: Float,
    depth: Float,
    kind: ItemDepthKind,
    alphaPower: Float = 3f,
): PageItemState = when (role) {
    // reason 10（下钻·新页）/ reason 12（返回·父页）：z 从远处/近处回到 0，α = f³
    PageRole.ENTERING -> {
        val fromZ = when {
            kind == ItemDepthKind.ROAD_SIGN && direction == PageSwitchDirection.INTO -> -1f
            kind == ItemDepthKind.BANNER -> if (direction == PageSwitchDirection.INTO) -depth else 0.25f
            direction == PageSwitchDirection.INTO -> -depth
            else -> depth
        }
        PageItemState(
            z = fromZ * (1f - enterProgress),
            alpha = enterProgress.pow(alphaPower),
        )
    }
    // reason 11（下钻·旧页）/ reason 13（返回·当前页）：z 从 0 冲向 +0.7·rand 或 −rand，α = (1−f)³
    PageRole.EXITING -> {
        val toZ = when {
            kind == ItemDepthKind.ROAD_SIGN -> if (direction == PageSwitchDirection.INTO) 0.7f else -1f
            direction == PageSwitchDirection.INTO -> 0.7f * depth
            else -> -depth
        }
        // α = (1−f)³，**与 9.9 `UiItemAnimationPath.setTransparent` 一致**（2026-10-24 修正：
        // r26b 曾为了压"整屏空"改成平方 ⇒ f=0.5 时 0.25 vs 原版 0.125，旧页后半段"赖着不走"、
        // 各可见度阈值晚 20–22ms，被用户读成"动画慢"）。
        PageItemState(z = toZ * exitProgress, alpha = cube(1f - exitProgress))
    }
    PageRole.IDLE -> PageItemState(z = 0f, alpha = 1f)
}

/** 透视相机（近平面距离 = 1）：`scale = 1/(1−z)`，z 朝镜头为正。 */
fun pageScaleFromZ(z: Float): Float = 1f / (1f - z).coerceAtLeast(0.1f)

private fun cube(x: Float): Float = x * x * x
