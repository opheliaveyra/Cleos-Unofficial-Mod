package com.cleo.cleos.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.cleo.cleos.CrashLog

/**
 * The picture Cleos wears on the home screen: the one it shipped with, or one of 16.
 *
 * 系统只认「哪个组件是启动器」，不认「App 里存了什么」—— 所以每个图标都是一个
 * android:activity-alias（Manifest 里的 .LauncherDefault / .Launcher01 … .Launcher16），
 * 换图标就是开关这些 alias：打开要的那个，关上别的。被点开的 Activity 一直是同一个
 * MainActivity，所以登录、聊天、通知什么都不动。
 *
 * 两件事容易踩：
 * 1. **绝不能先关后开**。中间那一瞬间一个启动器组件都没有，有些桌面（尤其国内厂商的）
 *    会把这个 App 的图标从桌上抹掉，之后便不再加回来，只能重装。
 * 2. 系统把 alias 的开关状态存在**系统**里，不是存在 App 的数据里 —— 重装、恢复备份都
 *    不带它。所以每次启动都照存着的值校一遍；[ensure] 只有真的不一致时才写，
 *    一致的时候一个写操作都不做（否则每开一次 App 就刷一次桌面，图标会闪）。
 */
object AppIcons {
    /** 出厂的那个图标（.LauncherDefault）。 */
    const val DEFAULT = "default"

    /** 能换的图标：默认，加上 01…32。存进 DataStore、界面上用的都是这里的字符串。 */
    val KEYS: List<String> = listOf(DEFAULT) + (1..32).map { it.toString().padStart(2, '0') }

    /** 不认识的键（老设置、手改过的备份）就当默认：总得有一个图标在桌面上。 */
    fun normalize(key: String?): String = if (key != null && key in KEYS) key else DEFAULT

    /** 格子下面那行字。 */
    fun label(key: String): String = if (normalize(key) == DEFAULT) "默认" else normalize(key)

    /** 这个键对应的 activity-alias 类名。 */
    fun aliasOf(key: String): String =
        if (normalize(key) == DEFAULT) DEFAULT_ALIAS else ALIAS_PREFIX + normalize(key)

    private const val DEFAULT_ALIAS = ".LauncherDefault"
    private const val ALIAS_PREFIX = ".Launcher"

    /**
     * 这个 alias 现在**实际**是不是启用的。
     *
     * 只看 getComponentEnabledSetting 不够：对 manifest 里 android:enabled="false" 的组件，
     * 从没被 setComponentEnabledSetting 过时它返回的是 COMPONENT_ENABLED_STATE_DEFAULT(0)，
     * 意思是「跟着 manifest 走」—— 而 manifest 里那 16 个恰好是关着的。把它当成"开着"的话，
     * 每次启动都会以为要切，于是把桌面刷一遍。
     *
     * 所以 DEFAULT 时去读 manifest 里的 enabled（[manifestOn] 是读不到时的退路）。
     */
    private fun isEnabled(pm: PackageManager, component: ComponentName, manifestOn: Boolean): Boolean =
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> runCatching { pm.getActivityInfo(component, 0).enabled }.getOrDefault(manifestOn)
        }

    /**
     * 让桌面用 [key] 这个图标。**已经就是它了，就什么都不写**（返回 false）——
     * 这一步每次启动都会走，写一次桌面就刷一次。
     *
     * 顺序是「先开目标、再关别的」，反过来的话中间总有一刻一个启动器都没有。
     * 换不成（系统不让、组件不认识）时只记一笔，不抛：图标还是原来那个，App 照常用。
     *
     * [Synchronized]：两次 apply 交错跑（连点两个图标）会各自"开一个、关其余"，
     * 交错的中间态可能把两个都关掉，桌面上一个图标都不剩。串行之后每次都是完整的一遍。
     */
    @Synchronized
    fun ensure(context: Context, key: String): Boolean {
        val chosen = normalize(key)
        return try {
            val pm = context.packageManager
            // 完整的类名，不用 ".Launcher01" 这种相对名：ComponentName 不会自己展开，
            // 而系统里记的是展开后的名字，相对名会一个组件都对不上（切换就整个失灵）。
            val aliases = KEYS.map { it to ComponentName(context.packageName, context.packageName + aliasOf(it)) }
            val wanted = aliases.first { it.first == chosen }
            // 已经在位：目标开着，其余（effective 意义上）都关着。
            val inPlace = isEnabled(pm, wanted.second, manifestOn = chosen == DEFAULT) &&
                aliases.none { (k, component) -> k != chosen && isEnabled(pm, component, manifestOn = k == DEFAULT) }
            if (inPlace) return false
            // 先开：后面万一关到一半失败，桌面上有两个能点的图标，而不是一个都没有。
            pm.setComponentEnabledSetting(
                wanted.second,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            for ((k, component) in aliases) {
                if (k == chosen) continue
                // 本来就关着的（manifest 里 enabled=false 且没被写过）不去碰它：
                // 少写一次少刷一次桌面，也少一个改坏系统状态的机会。
                if (isEnabled(pm, component, manifestOn = k == DEFAULT)) {
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
            true
        } catch (e: Exception) {
            // SecurityException / IllegalArgumentException，以及厂商改过的 PackageManager
            // 抛的别的东西：图标换不了不是大事，App 不能因为它起不来。
            CrashLog.note(context, e)
            false
        }
    }
}
