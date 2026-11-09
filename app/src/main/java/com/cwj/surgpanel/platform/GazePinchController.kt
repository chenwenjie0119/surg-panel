package com.cwj.surgpanel.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cwj.surgpanel.core.PanelPose
import com.cwj.surgpanel.core.PerfGovernor
import com.cwj.surgpanel.core.SurgicalSession
import com.cwj.surgpanel.core.WindowPoseModel
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.eye.EyeTrackingData
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import com.pico.spatial.tracking.hand.HandTrackingData
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingData
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import kotlin.math.abs

/**
 * 眼动注视 + 捏合确认控制器。
 *
 * 链路：EyeTrackingProvider / HMDTrackingProvider 提供视线射线，
 * HandTrackingProvider 提供捏合强度；射线与五窗矩形求交得出注视目标，
 * 驻留超过阈值即点亮高亮，此时捏合上升沿派发一次确认。
 *
 * 与核心业务的关系：本控制器只产生交互事件，不参与体征推演与告警判级；
 * 采样频率受 [PerfGovernor] 影响的下限为 20Hz，仅影响交互手感，不影响 100ms 时钟。
 */
object GazePinchController {

    /** 判定用的默认位姿回退：术者正前方平视。 */
    private const val DWELL_MS = 750f
    private const val HOT_AT = 0.34f
    private const val PINCH_THRESHOLD = 0.82f
    private const val CONFIRM_COOLDOWN_MS = 700L

    /** 官方 Spatial SDK 眼动权限：运行时权限，必须声明后动态申请。 */
    private const val EYE_PERMISSION = "com.picovr.permission.EYE_TRACKING"
    private const val REQ_EYE = 0x5E01

    var eyeAvailable by mutableStateOf(false)
        private set
    var handAvailable by mutableStateOf(false)
        private set

    /** 当前注视命中的窗口 id。 */
    var gazePanelId by mutableStateOf<String?>(null)
        private set

    /** 驻留达标后点亮高亮的窗口 id（聚焦窗据此描边）。 */
    var hotPanelId by mutableStateOf<String?>(null)
        private set

    /** 驻留进度 0..1。 */
    var dwell by mutableFloatStateOf(0f)
        private set

    /** 是否处于“有目标 + 手指已捏合”的可确认态。 */
    var pinchArmed by mutableStateOf(false)
        private set

    /** 捏合强度原始值，供聚焦窗显示。 */
    var pinchValue by mutableFloatStateOf(0f)
        private set

    /** 累计确认次数。 */
    var confirmCount by mutableIntStateOf(0)
        private set

    var supportNote by mutableStateOf("等待眼动数据")
        private set

    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var confirmTarget: String? = null
    private var confirmHandler: (() -> Unit)? = null

    private var appContext: Context? = null
    private var hostContext: Context? = null
    private var eyeProvider: EyeTrackingProvider? = null
    private var handProvider: HandTrackingProvider? = null
    private var eyeStarted = false
    private var handStarted = false
    private var hmdStarted = false
    private var eyePermissionGranted = false
    private var permissionRequested = false
    private var permCheckedAt = 0L

    /** 至少一路跟踪在线，供界面判断是否已具备眼动/手势能力。 */
    val trackingReady: Boolean
        get() = eyeAvailable || handAvailable

    // 传感原始量：SDK 回调线程写，主线程 tick 读。
    @Volatile private var eyeX = 0f
    @Volatile private var eyeY = 0f
    @Volatile private var eyeZ = 0f
    @Volatile private var eyeQx = 0f
    @Volatile private var eyeQy = 0f
    @Volatile private var eyeQz = 0f
    @Volatile private var eyeQw = 1f
    @Volatile private var eyeValid = false

    @Volatile private var hmdX = 0f
    @Volatile private var hmdY = 0f
    @Volatile private var hmdZ = 0f
    @Volatile private var hmdQx = 0f
    @Volatile private var hmdQy = 0f
    @Volatile private var hmdQz = 0f
    @Volatile private var hmdQw = 1f
    @Volatile private var hmdValid = false

    @Volatile private var handValid = false
    @Volatile private var pinchRaw = 0f

    private var lastHitId: String? = null
    private var hitSince = 0L
    private var prevPinchDown = false
    private var lastConfirmAt = 0L
    private var tickCounter = 0

    /**
     * 注册某个窗口的确认回调。
     *
     * 只有当注视命中的窗口与 [panelId] 一致、且驻留已达阈值时才会回调，
     * 传 null 取消（仅在当前注册者就是该窗口时生效）。
     */
    fun setConfirmHandler(panelId: String, handler: (() -> Unit)?) {
        if (handler == null) {
            if (confirmTarget == panelId) {
                confirmTarget = null
                confirmHandler = null
            }
        } else {
            confirmTarget = panelId
            confirmHandler = handler
        }
    }

    /** 启动三路跟踪。重复调用安全。 */
    fun attach(context: Context) {
        if (started) return
        started = true
        hostContext = context
        appContext = context.applicationContext
        eyePermissionGranted = hasEyePermission()
        startEye()
        startHand()
        startHmd()
        supportNote = describeSupport()
        android.util.Log.i("SurgeryGaze", "attach $supportNote")
        main.post(tick)
    }

    private fun hasEyePermission(): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            ContextCompat.checkSelfPermission(ctx, EYE_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /** 在主线程向宿主 Activity 申请眼动运行时权限；解析不到 Activity 时静默跳过。 */
    private fun requestEyePermission(): Boolean {
        val activity = resolveActivity(hostContext) ?: return false
        return runCatching {
            ActivityCompat.requestPermissions(activity, arrayOf(EYE_PERMISSION), REQ_EYE)
            android.util.Log.i("SurgeryGaze", "request eye permission issued by $activity")
            true
        }.getOrElse {
            android.util.Log.i("SurgeryGaze", "request failed ${it.message}")
            false
        }
    }

    /**
     * 由界面侧回填宿主 Activity 上下文。
     *
     * Application 上下文无法弹运行时权限框，眼动权限必须经 Activity 申请；
     * 界面进入后再回填，随后 tick 会每秒复查一次并在授权后拉起 provider。
     */
    fun bindHostContext(context: Context) {
        if (resolveActivity(context) != null) hostContext = context
    }

    private fun resolveActivity(context: Context?): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun startEye() {
        if (eyeStarted) return
        val provider = runCatching { EyeTrackingProvider() }.getOrNull() ?: return
        eyeProvider = provider
        eyeStarted = true
        runCatching {
            provider.addListener(object : DataProvider.DataListener<EyeTrackingData> {
                override fun onProvideData(data: EyeTrackingData) {
                    val p = data.eyePose.position
                    val q = data.eyePose.rotation
                    eyeX = p.x
                    eyeY = p.y
                    eyeZ = p.z
                    eyeQx = q.x
                    eyeQy = q.y
                    eyeQz = q.z
                    eyeQw = q.w
                    eyeValid = true
                }
            })
            // 无权限时 start 会失败或被系统静默拒绝，等权限到位后由 tick 重新拉起。
            if (eyePermissionGranted) provider.start()
        }
    }

    private fun startHand() {
        if (handStarted) return
        val provider = runCatching { HandTrackingProvider() }.getOrNull() ?: return
        handProvider = provider
        handStarted = true
        runCatching {
            provider.addListener(object : DataProvider.DataListener<HandTrackingData> {
                override fun onProvideData(data: HandTrackingData) {
                    // 右手优先，缺失时退回左手；两路都缺失则本轮不产生捏合事件。
                    val right = data.rightPinch
                    val left = data.leftPinch
                    val pinch = if (right?.isReady == true) right else left
                    if (pinch != null && pinch.isReady) {
                        handValid = true
                        pinchRaw = pinch.pinchValue.coerceIn(0f, 1f)
                    } else {
                        handValid = false
                    }
                }
            })
            provider.start()
        }
    }

    private fun startHmd() {
        if (hmdStarted) return
        val provider = runCatching { HMDTrackingProvider() }.getOrNull() ?: return
        hmdStarted = true
        runCatching {
            provider.addListener(object : DataProvider.DataListener<HMDTrackingData> {
                override fun onProvideData(data: HMDTrackingData) {
                    val p = data.hmdPose.position
                    val q = data.hmdPose.rotation
                    hmdX = p.x
                    hmdY = p.y
                    hmdZ = p.z
                    hmdQx = q.x
                    hmdQy = q.y
                    hmdQz = q.z
                    hmdQw = q.w
                    hmdValid = true
                }
            })
            provider.start()
        }
    }

    private fun describeSupport(): String {
        val eye = eyeProvider?.supportState?.name ?: "不可用"
        val hand = handProvider?.supportState?.name ?: "不可用"
        val perm = if (eyePermissionGranted) "已授权" else "待授权"
        return "眼动 $eye（$perm） · 手势 $hand"
    }

    private val tick = object : Runnable {
        override fun run() {
            compute()
            val interval = if (PerfGovernor.level == PerfGovernor.Level.LOW) 50L else 33L
            main.postDelayed(this, interval)
        }
    }

    private fun compute() {
        // 权限轮询每秒一次：授予后立刻拉起原先被拒的 provider，不重启任何线程。
        val wallNow = SystemClock.uptimeMillis()
        if (!eyePermissionGranted && wallNow - permCheckedAt > 1000L) {
            permCheckedAt = wallNow
            if (hasEyePermission()) {
                eyePermissionGranted = true
                eyeValid = false
                runCatching { eyeProvider?.start() }
                SurgicalSession.log("眼动权限已授予 · 视线高亮启用")
                supportNote = describeSupport()
                android.util.Log.i("SurgeryGaze", "权限已授予 · 重新拉起眼动 provider · ${describeSupport()}")
            } else if (!permissionRequested) {
                permissionRequested = requestEyePermission()
            }
        }

        eyeAvailable = eyeValid
        handAvailable = handValid
        if (++tickCounter % 30 == 0 && eyeValid) {
            supportNote = "眼动在线 · 手势" + if (handValid) "在线" else "待捕捉"
        }

        val now = SystemClock.uptimeMillis()
        val dir = forwardVector(
            if (eyeValid) eyeQx else hmdQx,
            if (eyeValid) eyeQy else hmdQy,
            if (eyeValid) eyeQz else hmdQz,
            if (eyeValid) eyeQw else hmdQw,
            hasPose = eyeValid || hmdValid
        )
        val ox = when {
            eyeValid -> eyeX
            hmdValid -> hmdX
            else -> WindowPoseModel.observerX
        }
        val oy = when {
            eyeValid -> eyeY
            hmdValid -> hmdY
            else -> WindowPoseModel.observerY
        }
        val oz = when {
            eyeValid -> eyeZ
            hmdValid -> hmdZ
            else -> WindowPoseModel.observerZ
        }

        var best: PanelPose? = null
        var bestDepth = Float.MAX_VALUE
        for (pose in WindowPoseModel.all()) {
            val depth = intersect(ox, oy, oz, dir, pose) ?: continue
            if (depth < bestDepth) {
                bestDepth = depth
                best = pose
            }
        }

        if (best != null) {
            if (best.id == lastHitId) {
                dwell = ((now - hitSince) / DWELL_MS).coerceIn(0f, 1f)
            } else {
                lastHitId = best.id
                hitSince = now
                dwell = 0f
            }
        } else {
            lastHitId = null
            dwell = 0f
        }
        gazePanelId = best?.id
        hotPanelId = if (dwell >= HOT_AT) best?.id else null

        pinchValue = if (handValid) pinchRaw else 0f
        val down = handValid && pinchRaw >= PINCH_THRESHOLD
        pinchArmed = down && best != null
        if (down && !prevPinchDown) {
            val target = best
            if (target != null && dwell >= HOT_AT && now - lastConfirmAt > CONFIRM_COOLDOWN_MS) {
                lastConfirmAt = now
                confirmCount++
                SurgicalSession.log("捏合确认 · ${target.name}（${target.code}）", critical = true)
                if (target.id == confirmTarget) confirmHandler?.invoke()
            }
        }
        prevPinchDown = down
    }

    /** 视线射线与窗口平面求交，命中则返回距离。 */
    private fun intersect(
        ox: Float,
        oy: Float,
        oz: Float,
        dir: FloatArray,
        pose: PanelPose
    ): Float? {
        val denom = dir[2]
        if (abs(denom) < 1e-4f) return null
        val depth = (pose.z - oz) / denom
        if (depth < 0.12f) return null
        val hx = ox + dir[0] * depth
        val hy = oy + dir[1] * depth
        val u = hx - pose.x
        val v = hy - pose.y
        if (abs(u) > pose.halfW + 0.03f) return null
        if (abs(v) > pose.halfH + 0.03f) return null
        return depth
    }

    /** 四元数作用于前向向量 (0,0,-1)，返回归一化方向。 */
    private fun forwardVector(x: Float, y: Float, z: Float, w: Float, hasPose: Boolean): FloatArray {
        if (!hasPose) return floatArrayOf(0f, 0f, -1f)
        val vx = 0f
        val vy = 0f
        val vz = -1f
        // t = 2 * (q.xyz × v)
        val tx = 2f * (y * vz - z * vy)
        val ty = 2f * (z * vx - x * vz)
        val tz = 2f * (x * vy - y * vx)
        // v' = v + w * t + q.xyz × t
        val rx = vx + w * tx + (y * tz - z * ty)
        val ry = vy + w * ty + (z * tx - x * tz)
        val rz = vz + w * tz + (x * ty - y * tx)
        val len = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
        if (len < 1e-5f) return floatArrayOf(0f, 0f, -1f)
        return floatArrayOf(rx / len, ry / len, rz / len)
    }

    /** 诊断文本，控制台与聚焦窗共用。 */
    fun statusText(): String {
        val gaze = gazePanelId?.let { WindowPoseModel.byId(it) }
        val target = gaze?.let { "${it.code} ${(dwell * 100).toInt()}%" } ?: "无目标"
        val pinch = if (handAvailable) {
            if (pinchArmed) "捏合就绪" else "手已追踪"
        } else {
            "手未捕捉"
        }
        return "$target · $pinch"
    }
}
