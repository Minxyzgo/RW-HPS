/*
 * Copyright 2020-2023 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.dependent.redirections.game

import net.rwhps.asm.data.MethodTypeInfoValue
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.plugin.PluginManage
import net.rwhps.server.dependent.redirections.MainRedirections
import net.rwhps.server.game.HessModuleManage
import net.rwhps.server.game.event.global.ServerHessLoadEvent
import net.rwhps.server.plugin.internal.hess.service.data.HessClassPathProperties
import net.rwhps.server.util.ReflectionUtils
import net.rwhps.server.util.annotations.mark.AsmMark
import net.rwhps.server.util.annotations.mark.GameSimulationLayer
import net.rwhps.server.util.inline.findMethod
import net.rwhps.server.util.inline.toClassAutoLoader
import net.rwhps.server.util.log.Log

//关闭傻逼格式化
//@formatter:off

/**
 * Network blocking and proxy
 *
 * @author Dr (dr@der.kim)
 */
@AsmMark.ClassLoaderCompatible
class GameMainRedirections: MainRedirections {
    override fun register() {
        // 直接空实现 因为意义不大
        addAllReplace("org/newdawn/slick/util/DefaultLogSystem")
        addAllReplace("com/LibRocket")
        addAllReplace("com/corrodinggames/librocket/scripts/ScriptEngine")
        // 这两个 因为 [LibRocket] 如果是空的话, 会被……游戏调用导致 NPE, 所以我们要覆盖掉方法
        //需要空实现
        redirectR(MethodTypeInfoValue("com/corrodinggames/librocket/b", "closeDocument", "(Lcom/ElementDocument;)V"))
        redirectR(MethodTypeInfoValue("com/corrodinggames/librocket/b", "closeActiveDocument", "()V"))

        // 关闭MusicController
        addAllReplace("com/corrodinggames/rts/gameFramework/am")

        /* 恢复-Root */
        redirectR(
                MethodTypeInfoValue("com/corrodinggames/librocket/scripts/Root", "resume", "()V")
        ) { obj: Any?, _: String, _: Class<*>, _: Array<out Any?> ->
            val rootClass = "com.corrodinggames.librocket.scripts.Root".toClassAutoLoader(obj!!)
            ReflectionUtils.findField(rootClass, "guiEngine")!!.also {
                ReflectionUtils.makeAccessible(it)
                it[obj]?.let { correspondingObject ->
                    "com.corrodinggames.librocket.a".toClassAutoLoader(obj)!!.findMethod("f")!!.invoke(correspondingObject)
                }
            }
            return@redirectR null
        }
        /* 恢复-Root */
        redirectR(
                MethodTypeInfoValue("com/corrodinggames/librocket/scripts/Root", "resumeNonMenu", "()V")
        ) { obj: Any?, _: String, _: Class<*>, _: Array<out Any?> ->
            val rootClass = "com.corrodinggames.librocket.scripts.Root".toClassAutoLoader(obj!!)
            ReflectionUtils.findField(rootClass, "guiEngine")!!.also {
                ReflectionUtils.makeAccessible(it)
                it[obj]?.let { correspondingObject ->
                    "com.corrodinggames.librocket.a".toClassAutoLoader(obj)!!.findMethod("a", java.lang.Boolean::class.java)!!
                        .invoke(correspondingObject, false)
                }
            }
            return@redirectR null
        }


        /* 取代游戏自己打印的 */
        redirectR(
                MethodTypeInfoValue("android/util/Log", "a", "(ILjava/lang/String;Ljava/lang/String;)I")
        ) { obj: Any?, _: String, _: Class<*>, args: Array<out Any?> ->
            args[2]?.let {
                val classIn = obj as Class<*>
                val msg = it.toString()
                if (Data.config.log == "ALL") {
                    println("[${classIn.classLoader}]  :  " + msg)
                }
            }
            0
        }

        @GameSimulationLayer.GameSimulationLayer_KeyWords("Game init finished in")
        redirectL(MethodTypeInfoValue("com/corrodinggames/rts/java/Main", "h", "()V", false)) { obj: Any?, _: String, args: Array<out Any?> ->
            // Enable the interface
            "${HessClassPathProperties.CorePath}.GameEngine".toClassAutoLoader(obj!!)!!.findMethod("init")!!.invoke(null)

            val loadID = obj::class.java.classLoader.toString()
            PluginManage.runGlobalEventManage(ServerHessLoadEvent(loadID, HessModuleManage.hessLoaderMap[loadID]!!))
        }

        @GameSimulationLayer.GameSimulationLayer_KeyWords("Recording replay to:")
        redirectL(MethodTypeInfoValue("com/corrodinggames/rts/gameFramework/ba", "d", "(Ljava/lang/String;)V", true)) { obj: Any?, _: String, args: Array<out Any?> ->
            Log.clog("Save Replay to: {0}", args[0].also { replayFileName ->
                HessModuleManage.hessLoaderMap[obj!!::class.java.classLoader.toString()]!!.room.replayFileName = replayFileName.toString()
            })
        }
    }
}