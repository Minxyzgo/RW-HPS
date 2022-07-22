/*
 * Copyright 2020-2022 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package cn.rwhps.server.net.netconnectprotocol.realize

import cn.rwhps.server.data.global.Cache
import cn.rwhps.server.data.global.Data
import cn.rwhps.server.data.global.NetStaticData
import cn.rwhps.server.data.global.Relay
import cn.rwhps.server.data.player.PlayerRelay
import cn.rwhps.server.game.GameMaps
import cn.rwhps.server.io.GameInputStream
import cn.rwhps.server.io.GameOutputStream
import cn.rwhps.server.io.output.CompressOutputStream
import cn.rwhps.server.io.packet.Packet
import cn.rwhps.server.net.core.ConnectionAgreement
import cn.rwhps.server.net.core.DataPermissionStatus.RelayStatus
import cn.rwhps.server.net.core.NetConnectProofOfWork
import cn.rwhps.server.net.core.server.AbstractNetConnect
import cn.rwhps.server.net.core.server.AbstractNetConnectRelay
import cn.rwhps.server.net.netconnectprotocol.UniversalAnalysisOfGamePackages
import cn.rwhps.server.net.netconnectprotocol.internal.relay.fromRelayJumpsToAnotherServer
import cn.rwhps.server.net.netconnectprotocol.internal.relay.relayServerInitInfo
import cn.rwhps.server.net.netconnectprotocol.internal.relay.relayServerTypeInternal
import cn.rwhps.server.net.netconnectprotocol.internal.relay.relayServerTypeReplyInternal
import cn.rwhps.server.struct.ObjectMap
import cn.rwhps.server.struct.OrderedMap
import cn.rwhps.server.util.IsUtil
import cn.rwhps.server.util.PacketType
import cn.rwhps.server.util.Time
import cn.rwhps.server.util.alone.annotations.MainProtocolImplementation
import cn.rwhps.server.util.game.CommandHandler
import cn.rwhps.server.util.log.Log.debug
import cn.rwhps.server.util.log.Log.error
import java.io.IOException
import java.util.*
import java.util.stream.IntStream

/**
 * Many thanks to them for providing cloud computing for the project
 * This is essential to complete the RW-HPS Relay test
 * @Thanks : [SimpFun Cloud](https://vps.tiexiu.xyz)
 * @Thanks : [Github 1dNDN](https://github.com/1dNDN)
 *
 * This test was done on :
 * Relay-CN (V. 6.1.0)
 * 2022.7.22 10:00
 */

/**
 * Relay protocol implementation
 * Direct forwarding consumes more bandwidth and the same effect as using VPN forwarding
 *
 * @property permissionStatus       Connection authentication status
 * @property netConnectAuthenticate Connection validity verification
 * @property relay                  Relay instance
 * @property site                   Connect the forwarding location within the RELAY
 * @property connectUUID            UUID of this connection
 * @property cachePacket            Cached Package
 * @property relaySelect            Function1<String, Unit>?
 * @property name                   player's name
 * @property registerPlayerId       UUID-Hash code after player registration
 * @property betaGameVersion        Is it a beta version
 * @property clientVersion          clientVersion
 * @property playerRelay            PlayerRelay?
 * @property version                Protocol version
 * @constructor
 *
 * @author RW-HPS/Dr
 */
@MainProtocolImplementation
open class GameVersionRelay(connectionAgreement: ConnectionAgreement) : AbstractNetConnect(connectionAgreement), AbstractNetConnectRelay {
    override var permissionStatus: RelayStatus = RelayStatus.InitialConnection
        internal set

    /** Client computing proves non-Bot */
    private var netConnectAuthenticate: NetConnectProofOfWork? = null

    override var relay: Relay? = null
        protected set

    protected var site = 0

    private val connectUUID = UUID.randomUUID().toString()

    private var cachePacket: Packet? = null

    private var relaySelect: ((String) -> Unit)? = null

    var name = "NOT NAME"
        protected set
    private var registerPlayerId: String? = null
    private var betaGameVersion = false
    internal var clientVersion = 151
    var playerRelay: PlayerRelay? = null
        internal set

    // Lazy loading reduces memory usage
    /** The server kicks the player's time data */
    lateinit var relayKickData: ObjectMap<String,Int>
        protected set
    /** Server exits player data */
    lateinit var relayPlayersData: ObjectMap<String,PlayerRelay>
        protected set

    override fun setCachePacket(packet: Packet) {
        cachePacket = packet
    }

    override fun setlastSentPacket(packet: Packet) {
    }


    override val version: String
        get() = "1.14 RELAY"

    override fun sendRelayServerInfo() {
        val cPacket: Packet? = Cache.packetCache["sendRelayServerInfo"]
        if (IsUtil.notIsBlank(cPacket)) {
            sendPacket(cPacket!!)
            return
        }
        try {
            val packetCache = relayServerInitInfo()
            Cache.packetCache.put("sendRelayServerInfo",packetCache)
            sendPacket(packetCache)
        } catch (e: Exception) {
            error(e)
        }
    }

    @Throws(IOException::class)
    override fun relayDirectInspection(relay: Relay?) {
        GameInputStream(cachePacket!!).use { inStream ->
            inStream.readString()
            val packetVersion = inStream.readInt()
            clientVersion = inStream.readInt()
            if (clientVersion > 151) {
                betaGameVersion = true
            }
            if (packetVersion >= 1) {
                inStream.skip(4)
            }
            var queryString = ""
            if (packetVersion >= 2) {
                queryString = inStream.readIsString()
            }
            if (packetVersion >= 3) {
                name = inStream.readString()
            }
            if (relay == null) {
                if (IsUtil.isBlank(queryString) || "RELAYCN".equals(queryString, ignoreCase = true)) {
                    sendRelayServerType(Data.i18NBundle.getinput("relay.hi", Data.SERVER_CORE_VERSION))
                } else {
                    idCustom(queryString)
                }
            } else {
                this.relay = relay
                addRelayConnect()
                this.relay!!.setAddSize()
            }
        }
    }

    override fun sendVerifyClientValidity() {
        netConnectAuthenticate = Relay.randPow
        val netConnectAuthenticate: NetConnectProofOfWork = netConnectAuthenticate!!
        val authenticateType = netConnectAuthenticate.authenticateType.toInt()
        debug(authenticateType)
        try {
            val o = GameOutputStream()
            // 返回相同
            o.writeInt(netConnectAuthenticate.resultInt)
            o.writeInt(authenticateType)
            if (authenticateType == 0 || netConnectAuthenticate.authenticateType in 2..4 || authenticateType == 6) {
                o.writeBoolean(true)
                o.writeInt(netConnectAuthenticate.initInt_1)
            } else {
                o.writeBoolean(false)
            }
            if (authenticateType == 1 || netConnectAuthenticate.authenticateType in 2..4) {
                o.writeBoolean(true)
                o.writeInt(netConnectAuthenticate.initInt_2)
            } else {
                o.writeBoolean(false)
            }
            if (netConnectAuthenticate.authenticateType in 5..6) {
                o.writeString(netConnectAuthenticate.outcome)
                o.writeString(netConnectAuthenticate.fixedInitial)
                o.writeInt(netConnectAuthenticate.maximumNumberOfCalculations)
            }

            o.writeBoolean(false)

            sendPacket(o.createPacket(PacketType.RELAY_POW))
        } catch (e: Exception) {
            error(e)
        }
    }

    override fun receiveVerifyClientValidity(packet: Packet): Boolean {
        try {
            GameInputStream(packet).use { inStream ->
                if (netConnectAuthenticate != null) {
                    if (netConnectAuthenticate!!.check(inStream.readInt(),inStream.readInt(),inStream.readString())) {
                        netConnectAuthenticate = null
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            error(e)
        }
        return false
    }

    override fun sendRelayServerType(msg: String, run: ((String) -> Unit)?) {
        try {
            sendPacket(relayServerTypeInternal(msg))

            relaySelect = run

            inputPassword = true
        } catch (e: Exception) {
            error(e)
        }
    }

    @Throws(IOException::class)
    override fun receiveChat(p: Packet) {
        GameInputStream(p).use { inStream ->
            if (playerRelay != null) {
                if (playerRelay!!.mute) {
                    return
                }

                val message: String = inStream.readString()

                if (message.startsWith(".") || message.startsWith("-")) {
                    val response = Data.RELAY_COMMAND.handleMessage(message, this)
                    if (response == null || response.type == CommandHandler.ResponseType.noCommand) {
                    } else if (response.type != CommandHandler.ResponseType.valid) {
                        val text: String = when (response.type) {
                            CommandHandler.ResponseType.manyArguments -> "Too many arguments. Usage: " + response.command.text + " " + response.command.paramText
                            CommandHandler.ResponseType.fewArguments -> "Too few arguments. Usage: " + response.command.text + " " + response.command.paramText
                            else -> {
                                if (!message.contains("self_")) {
                                    sendResultPing(p)
                                }
                                return
                            }
                        }
                        sendPacket(NetStaticData.RwHps.abstractNetPacket.getSystemMessagePacket(text))
                    }
                } else {
                    // 判定这句和最后一次发言的相同程度
                    if (playerRelay!!.lastSentMessage == message || Time.getTimeSinceSecond(playerRelay!!.lastMessageTime) < 5) {
                        // 检测是否达到上限 60s 五次
                        if (playerRelay!!.messageSimilarityCount.checkStatus()) {
                            // KICK 玩家
                            relay!!.admin!!.relayKickData.put("KICK$registerPlayerId",Time.concurrentSecond()+120)
                            kick("相同的话不应该连续发送也不应该发言太快")
                            return
                        } else {
                            // 提醒玩家 并累计数
                            sendPacket(NetStaticData.RwHps.abstractNetPacket.getChatMessagePacket("您已经连续多次发言重复 您不应该这样做", "RELAY_CN-Check", 5))
                            playerRelay!!.messageSimilarityCount.count++
                            return
                        }
                    }
                    playerRelay!!.lastSentMessage = message
                }

                if (!message.contains("self_")) {
                    sendResultPing(p)
                }
            } else {
                disconnect()
            }
        }
    }

    override fun sendRelayServerTypeReply(packet: Packet) {
        try {
            inputPassword = false

            val id = relayServerTypeReplyInternal(packet)
            if (relaySelect == null) {
                idCustom(id)
            } else {
                relaySelect!!(id)
            }
        } catch (e: Exception) {
            error(e)
        }
    }

    override fun sendRelayServerId() {
        try {
            inputPassword = false
            if (relay == null) {
                //Log.debug("sendRelayServerId","Relay == null");
                relay = NetStaticData.relay
            }
            if (relay!!.admin != null) {
                // Log.debug("sendRelayServerId","Admin != null");
                relay!!.removeAbstractNetConnect(site)
                relayKickData = relay!!.admin!!.relayKickData
                relayPlayersData = relay!!.admin!!.relayPlayersData
            } else {
                relayKickData = ObjectMap()
                relayPlayersData = ObjectMap()
            }

            //Log.debug("sendRelayServerId","Set Admin");
            relay!!.admin = this
            //Log.debug(this == relay.getAdmin());
            val o = GameOutputStream()
            o.writeByte(1)
            o.writeBoolean(true)
            o.writeBoolean(true)
            o.writeBoolean(true)
            o.writeString(relay!!.serverUuid)
            o.writeBoolean(relay!!.isMod) //MOD
            // List OPEN
            o.writeBoolean(false)
            o.writeBoolean(true)
            o.writeString("{{RW-HPS }}.Room ID : " + relay!!.id)
            // 多播
            o.writeBoolean(false)
            sendPacket(o.createPacket(PacketType.FORWARD_HOST_SET)) //+108+140
            //getRelayT4(Data.localeUtil.getinput("relay.server.admin.connect",relay.getId()));
            sendPacket(NetStaticData.RwHps.abstractNetPacket.getChatMessagePacket(Data.i18NBundle.getinput("relay.server.admin.connect", relay!!.id, relay!!.internalID.toString()), "RELAY_CN-ADMIN", 5))
            sendPacket(NetStaticData.RwHps.abstractNetPacket.getChatMessagePacket(Data.i18NBundle.getinput("relay", relay!!.id), "RELAY_CN-ADMIN", 5))
            //ping();

            //debug(name)
            if (name.equals("SERVER", ignoreCase = true) || name.equals("RELAY", ignoreCase = true)) {
                relay!!.groupNet.disconnect() // Close Room
                disconnect() // Close Connect & Reset Room
            }
        } catch (e: Exception) {
            error(e)
        }
    }

    override fun getRelayT4(msg: String) {
        try {
            val o = GameOutputStream()
            // 理论上是随机数？
            //o.writeString("RW-HPS Relay TEST?");
            o.writeString(msg)
            o.writeByte(0)
            sendPacket(o.createPacket(PacketType.CHAT_RECEIVE))
        } catch (e: Exception) {
            error(e)
        }
    }

    override fun getPingData(packet: Packet) {
        try {
            GameInputStream(packet).use { inStream ->
                val out = GameOutputStream()
                //out.writeBytes(inStream.readNBytes(8))
                out.transferToFixedLength(inStream,8)
                out.writeByte(1)
                out.writeByte(60)
                sendPacket(out.createPacket(PacketType.HEART_BEAT_RESPONSE))
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun addGroup(packet: Packet) {
        relay!!.groupNet.broadcastAndUDP(packet)
    }

    override fun addGroupPing(packet: Packet) {
        try {
            relay!!.groupNet.broadcastAndUDP(packet)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun addRelayConnect() {
        permissionStatus = RelayStatus.PlayerPermission

        try {
            inputPassword = false
            if (relay == null) {
                relay = NetStaticData.relay
            }
            relay!!.setAddSite()
            relay!!.setAbstractNetConnect(this)
            site = relay!!.getSite()
            val o = GameOutputStream()
            o.writeByte(0)
            o.writeInt(site)
            //o.writeBoolean(true);
            o.writeString(connectUUID)
            o.writeBoolean(false)
            //o.writeIsString(Cache.relayAdminCache.getCache(name+ip))
            relay!!.admin!!.sendPacket(o.createPacket(PacketType.FORWARD_CLIENT_ADD))
            val o1 = GameOutputStream()
            o1.writeInt(site)
            o1.writeInt(cachePacket!!.bytes.size + 8)
            o1.writeInt(cachePacket!!.bytes.size)
            o1.writeInt(PacketType.PREREGISTER_INFO_RECEIVE.typeInt)
            o1.writeBytes(cachePacket!!.bytes)
            relay!!.admin!!.sendPacket(o1.createPacket(PacketType.PACKET_FORWARD_CLIENT_FROM))
            connectionAgreement.add(relay!!.groupNet)
            sendPacket(NetStaticData.RwHps.abstractNetPacket.getChatMessagePacket(Data.i18NBundle.getinput("relay", relay!!.id), "RELAY_CN-ADMIN", 5))
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            //cachePacket = null;
        }
    }

    override fun relayRegisterConnection(packet: Packet) {
        try {
            GameInputStream(packet).use { stream ->
                stream.readString()
                stream.skip(12)
                stream.readString()
                stream.readIsString()
                stream.readString()
                if (IsUtil.isBlank(registerPlayerId)) {
                    registerPlayerId = stream.readString()
                }

                // Relay-EX
                if (playerRelay == null) {
                    playerRelay = relay!!.admin!!.relayPlayersData[registerPlayerId] ?: PlayerRelay(this, registerPlayerId!!, name).also {
                        relay!!.admin!!.relayPlayersData.put(registerPlayerId,it)
                    }

                    playerRelay!!.nowName = name
                    playerRelay!!.disconnect = false


                    if (relay!!.admin!!.relayKickData.containsKey("BAN$ip")) {
                        kick("您被这个房间BAN了 请稍等一段时间 或者换一个房间")
                        return
                    }
                    if (relay!!.admin!!.relayKickData.containsKey("KICK$registerPlayerId")) {
                        if (relay!!.admin!!.relayKickData["KICK$registerPlayerId"] > Time.concurrentSecond()) {
                            kick("您被这个房间踢出了 请稍等一段时间 或者换一个房间")
                            return
                        } else {
                            relay!!.admin!!.relayKickData.remove("KICK$registerPlayerId")
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        sendResultPing(packet)
    }

    override fun addReRelayConnect() {
        try {
            val o = GameOutputStream()
            o.writeByte(0)
            o.writeInt(site)
            //o.writeBoolean(true);
            o.writeString(connectUUID)
            o.writeBoolean(true)
            o.writeString(registerPlayerId!!)
            relay!!.admin!!.sendPacket(o.createPacket(PacketType.FORWARD_CLIENT_ADD))
            val o1 = GameOutputStream()
            o1.writeInt(site)
            o1.writeInt(cachePacket!!.bytes.size + 8)
            o1.writeInt(cachePacket!!.bytes.size)
            o1.writeInt(160)
            o1.writeBytes(cachePacket!!.bytes)
            relay!!.admin!!.sendPacket(o1.createPacket(PacketType.PACKET_FORWARD_CLIENT_FROM))
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            //cachePacket = null;
        }
    }

    override fun addRelaySend(packet: Packet) {
        try {
            GameInputStream(packet).use { inStream ->
                val target = inStream.readInt()
                val type = inStream.readInt()
                if (IntStream.of(
                        PacketType.DISCONNECT.typeInt,
                        PacketType.HEART_BEAT.typeInt
                ).anyMatch { i: Int -> i == type }) {
                    return
                }
                inStream.skip(4)
                val bytes = inStream.readAllBytes()
                val abstractNetConnect = relay!!.getAbstractNetConnect(target)
                if (PacketType.KICK.typeInt == type) {
                    val gameOutputStream = GameOutputStream()
                    gameOutputStream.writeString(GameInputStream(bytes).readString().replace("\\d".toRegex(), ""))
                    abstractNetConnect?.sendPacket(gameOutputStream.createPacket(type))
                    relayPlayerDisconnect()
                    return
                }
                abstractNetConnect?.sendPacket(Packet(type, bytes))

                if (!relay!!.isStartGame) {
                    if (type == PacketType.TEAM_LIST.typeInt) {
                        abstractNetConnect?.let { UniversalAnalysisOfGamePackages.getPacketTeamData(GameInputStream(bytes,it.clientVersion),it.playerRelay!!) }
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (_: NullPointerException) {
        }
    }

    override fun sendResultPing(packet: Packet) {
        try {
            val o = GameOutputStream()
            o.writeInt(site)
            o.writeInt(packet.bytes.size + 8)
            o.writeInt(packet.bytes.size)
            o.writeInt(packet.type.typeInt)
            o.writeBytes(packet.bytes)
            relay!!.admin!!.sendPacket(o.createPacket(PacketType.PACKET_FORWARD_CLIENT_FROM))
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun sendCustomPacket(packet: Packet) {
        try {
            sendPacket(packet)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun relayPlayerDisconnect() {
        try {
            val out = GameOutputStream()
            out.writeByte(0)
            out.writeInt(site)
            relay!!.admin!!.sendPacket(out.createPacket(PacketType.FORWARD_CLIENT_REMOVE))
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun multicastAnalysis(packet: Packet) {
        // Protocol not supported
    }

    internal fun kick(msg: String) {
        val o = GameOutputStream()
        o.writeString(msg)
        sendPacket(o.createPacket(PacketType.KICK))
        disconnect()
    }

    override fun disconnect() {
        if (super.isDis) {
            return
        }
        super.isDis = true
        if (relay != null) {
            relay!!.setRemoveSize()
            if (this !== relay!!.admin) {
                relay!!.removeAbstractNetConnect(site)
                try {
                    relay!!.updateMinSize()
                    sendResultPing(NetStaticData.RwHps.abstractNetPacket.getExitPacket())
                } catch (e: IOException) {
                    error("[Relay disconnect] Send Exited", e)
                }

                playerRelay!!.disconnect = true
                if (!relay!!.isStartGame) {
                    relay!!.admin!!.relayPlayersData.remove(registerPlayerId)
                } else {
                    val teamData = OrderedMap<Int,Int>()
                    val nowTime = Time.concurrentSecond()
                    val countSet = HashSet<Int>()
                    relay!!.admin!!.relayPlayersData.values().forEach {
                        var count = teamData[it.team] ?:0
                        teamData.put(it.team,if (it.disconnect && nowTime-it.disconnectTime > 120) count else ++count)
                        countSet.add(it.team)
                    }

                    if (countSet.size != relay!!.admin!!.relayPlayersData.size) {
                        teamData.each { t, n ->
                            if (n == 0) {
                                relay!!.admin!!.relayPlayersData.values().forEach {
                                    if (it.team == t) {
                                        it.con.sendResultPing(Cache.packetCache["sendSurrenderPacket"])
                                    }
                                }
                                relay!!.sendMsg("队伍: [ ${t+1}] 全员断开, 因此 此队伍投降")
                            }
                        }
                    }
                }
            } else {
                Relay.serverRelayIpData.remove(ip)
                // 房间开始游戏 或者 在列表
                if (relay!!.isStartGame) {
                    if (relay!!.getSize() > 0) {
                        // Move Room Admin
                        adminMoveNew()
                    }
                } else {
                    // Close Room
                    relay!!.groupNet.disconnect()
                }
            }
            if (relay!!.getSize() <= 0 && !relay!!.closeRoom) {
                debug("[Relay] Gameover")
                relay!!.re()
            }
            super.close(relay!!.groupNet)
        } else {
            super.close(null)
        }
    }

    private fun idCustom(inId: String) {
        // 过滤 制表符 空格 换行符
        var id = inId.replace("\\s".toRegex(), "")

        if (id.isEmpty()) {
            sendRelayServerType(Data.i18NBundle.getinput("relay.server.no", "NULL / 空的"))
            return
        }

        if (id.startsWith("RA") || id.startsWith("RB")) {
            id = id.substring(1)
            sendPacket(fromRelayJumpsToAnotherServer("${id[0]}.relay.der.kim/${id.substring(0)}"))
            return
        } else if ("R".equals(id[0].toString(), ignoreCase = true)) {
            id = id.substring(1)
        }

        if (id.isEmpty()) {
            sendRelayServerType(Data.i18NBundle.getinput("relay.server.no", "R"))
            return
        }

        if ("C".equals(id[0].toString(), ignoreCase = true)) {
            id = id.substring(1)
            if ("M".equals(id[0].toString(), ignoreCase = true)) {
                id = id.substring(1)
                if (checkLength(id)) {
                    if (Relay.getCheckRelay(id)) {
                        sendRelayServerType(Data.i18NBundle.getinput("relay.id.re"))
                    } else {
                        newRelayId(id, true)
                    }
                }
            } else {
                if (checkLength(id)) {
                    if (Relay.getCheckRelay(id)) {
                        sendRelayServerType(Data.i18NBundle.getinput("relay.id.re"))
                    } else {
                        newRelayId(id, false)
                    }
                }
            }
            return
        }

        var mods = false
        var newRoom = true

        if (id.startsWith("new", ignoreCase = true)) {
            id = id.substring(3)
        } else if (id.startsWith("mod", ignoreCase = true)) {
            mods = true
            id = id.substring(3)
        } else if (id.startsWith("mods", ignoreCase = true)) {
            mods = true
            id = id.substring(4)
        } else {
            newRoom = false
        }

        var maxPlayer = -1
        var maxUnit = 200
        if (id.startsWith("P", ignoreCase = true)) {
            id = id.substring(1)
            val arry = if (id.contains("，")) id.split("，") else id.split(",")
            maxPlayer = arry[0].toInt()
            if  (maxPlayer !in 0..100) {
                sendRelayServerType(Data.i18NBundle.getinput("relay.id.maxPlayer.re"))
                return
            }
            if (arry.size > 1) {
                maxUnit = arry[0].toInt()
                if  (maxUnit !in 0..Int.MAX_VALUE) {
                    sendRelayServerType(Data.i18NBundle.getinput("relay.id.maxUnit.re"))
                    return
                }
            }
        }

        if (newRoom) {
            newRelayId(mods,maxPlayer,maxUnit)
        } else {
            try {
                relay = Relay.getRelay(id)
                if (relay != null) {
                    addRelayConnect()
                    relay!!.setAddSize()
                } else {
                    sendRelayServerType(Data.i18NBundle.getinput("relay.server.no", id))
                }
            } catch (e: Exception) {
                debug(e)
                sendRelayServerType(Data.i18NBundle.getinput("relay.server.no", id))
            }
        }
    }

    private fun checkLength(str: String): Boolean {
        if (str.length > 7 || str.length < 3) {
            sendRelayServerType(Data.i18NBundle.getinput("relay.id.re"))
            return false
        }
        return true
    }

    private fun adminMoveNew() {
        relay!!.updateMinSize()
        relay!!.getAbstractNetConnect(relay!!.minSize)!!.sendRelayServerId()
        relay!!.abstractNetConnectIntMap.values().forEach { obj: GameVersionRelay -> obj.addReRelayConnect() }
    }

    private fun newRelayId(mod: Boolean, maxPlayer: Int, unitMax: Int) {
        newRelayId(null, mod, maxPlayer,unitMax)
    }

    private fun newRelayId(id: String?, mod: Boolean, maxPlayerIn: Int = 10, unitMax: Int = 200) {
        val maxPlayer = if (maxPlayerIn == -1) 10 else maxPlayerIn

        relay = if (IsUtil.isBlank(id)) {
            Relay.getRelay( playerName = name, isMod = mod, betaGameVersion = betaGameVersion, version = clientVersion, maxPlayer = maxPlayer)
        } else {
            Relay.getRelay( id!!, name, mod, betaGameVersion, clientVersion, maxPlayer)
        }

        relay!!.isMod = mod

        if (maxPlayerIn != -1) {
            customModePlayerSize(maxPlayer,unitMax)
        }

        sendRelayServerId()
        relay!!.setAddSize()
    }

    private fun customModePlayerSize(maxPlayer: Int, unitMax: Int) {
        val registerServer = GameOutputStream()
        registerServer.writeString(Data.SERVER_ID)
        registerServer.writeInt(1)
        registerServer.writeInt(clientVersion)
        registerServer.writeInt(clientVersion)
        registerServer.writeString("com.corrodinggames.rts.server")
        registerServer.writeString(relay!!.serverUuid)
        registerServer.writeInt("Dr @ 2022".hashCode())
        sendPacket(registerServer.createPacket(PacketType.PREREGISTER_INFO))

        val o2 = GameOutputStream()
        o2.writeString(Data.SERVER_ID_RELAY)
        o2.writeInt(clientVersion)
        o2.writeInt(GameMaps.MapType.customMap.ordinal)
        o2.writeString("RW-HPS RELAY Custom Mode")
        o2.writeInt(Data.game.credits)
        o2.writeInt(Data.game.mist)
        o2.writeBoolean(true)
        o2.writeInt(1)
        o2.writeByte(0)
        o2.writeBoolean(false)
        o2.writeBoolean(false)
        sendPacket(o2.createPacket(PacketType.SERVER_INFO))

        val o = GameOutputStream()
        /* Player position */
        o.writeInt(0)
        o.writeBoolean(false)
        /* Largest player */
        o.writeInt(maxPlayer)
        o.flushEncodeData(CompressOutputStream.getGzipOutputStream("teams", true).also { for (i in 0 until maxPlayer) it.writeBoolean(false)})
        /* 迷雾 */
        o.writeInt(Data.game.mist)
        o.writeInt(Data.game.credits)
        o.writeBoolean(true)
        /* AI Difficulty ?*/
        o.writeInt(1)
        o.writeByte(5)
        o.writeInt(unitMax)
        o.writeInt(unitMax)
        o.writeInt(Data.game.initUnit)
        o.writeFloat(Data.game.income)
        o.writeBoolean(Data.game.noNukes)
        o.writeBoolean(false)
        o.writeBoolean(false)
        o.writeBoolean(Data.game.sharedControl)
        o.writeBoolean(Data.game.gamePaused)
        sendPacket(o.createPacket(PacketType.TEAM_LIST))
    }
}