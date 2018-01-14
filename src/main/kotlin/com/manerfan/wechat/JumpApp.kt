/*
 * ManerFan(http://manerfan.com). All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.manerfan.wechat

import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.ExecuteException
import org.apache.commons.exec.PumpStreamHandler
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.Ansi.ansi
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.ansi.AnsiColor
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationFailedEvent
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ResourceLoaderAware
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import org.springframework.util.FileCopyUtils
import org.yaml.snakeyaml.Yaml
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.Console
import java.io.File
import java.lang.reflect.UndeclaredThrowableException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * @author manerfan
 * @date 2018/1/10
 */

fun BufferedImage.rgb(x: Int, y: Int): Map<String, Int> {
    val rgb = this.getRGB(x, y)
    return mapOf(
            "r" to rgb.and(0xFF_00_00).ushr(16),
            "g" to rgb.and(0xFF_00).ushr(8),
            "b" to rgb.and(0xFF)
    )
}

class Config(
        var underGameScoreY: Int = 300,
        var pressCoefficient: Float = 1.392F, // 长按的时间系数
        var pieceBaseHeight: Int = 40, // 棋子底座高度
        var pieceBodyWidth: Int = 70, // 棋子底座宽度
        var swipe: MutableMap<String, Int> = mutableMapOf("x" to 500, "y" to 1600)
)

@SpringBootApplication
class JumpApp : CommandLineRunner, ResourceLoaderAware {
    @Value("\${debug.enable:false}")
    var debug: Boolean = false

    @Value("\${command.adb:adb}")
    lateinit var adb: String

    @Value("\${debug.resource:/data/wechat-jump}")
    lateinit var debugDir: String

    @Value("\${config.resource:classpath:/config}")
    lateinit var configResource: String
    lateinit var config: Config

    private val random = Random()
    private val sf = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")
    private var prevCoordinate: Map<String, Int>? = null

    private val console: Console? = System.console()
    private val exec = DefaultExecutor()
    private val sizePattern = Pattern.compile("(\\d+)x(\\d+)")

    private lateinit var resourceLoader: ResourceLoader
    override fun setResourceLoader(resourceLoader: ResourceLoader) {
        this.resourceLoader = resourceLoader
    }

    /**
     * 执行adb命令
     */
    private fun execAdbString(vararg args: String) = String(execAdb(*args))

    private fun execAdb(vararg args: String): ByteArray {
        val cmd = CommandLine.parse(adb)
        cmd.addArguments(args)

        return ByteArrayOutputStream().use {
            exec.streamHandler = PumpStreamHandler(it)
            exec.execute(cmd)
            it.toByteArray()
        }
    }

    /**
     * 显示设备信息
     */
    private fun showDeviceInfo() {
        print(ansi().fgBrightBlue().a("\n厂商：\t${execAdbString("shell", "getprop", "ro.fota.oem")}"))
        print(ansi().fgBrightBlue().a("版本：\t${execAdbString("shell", "getprop", "ro.build.software.version")}"))
        print(ansi().fgBrightBlue().a("分辨率：\t${execAdbString("shell", "wm", "size")}"))
        println(ansi().fgBrightBlue().a("系统：\t${System.getProperty("os.name")}"))
        println(ansi().fgBrightBlue().a("环境：\tjre_${System.getProperty("java.version")}\n").reset())
    }

    /**
     * 获取屏幕大小
     */
    private fun screenSize(): String {
        val size = execAdbString("shell", "wm", "size")
        val matcher = sizePattern.matcher(size)
        return if (matcher.find()) matcher.group() else "default"
    }

    /**
     * 加载配置文件
     */
    private fun loadConfig() {
        val location = "$configResource/${screenSize()}.yml"
        println(ansi().fgBrightBlue().a("加载配置：$location\n").reset())
        config = Yaml().loadAs(resourceLoader.getResource(location).inputStream, Config::class.java)
    }

    /**
     * 截屏
     */
    private fun screenShot() = ImageIO.read(execAdb("shell", "screencap", "-p").inputStream())

    /**
     * 寻找棋子和棋盘中心坐标
     */
    private fun findPieceAndBoard(image: BufferedImage): Map<String, Int> {
        val graphics = image.graphics

        val w = image.width
        val h = image.height

        var pieceX = 0
        var pieceY = 0
        var boardX = 0
        var boardY = 0

        var startY = 0 // 扫描的起始y坐标
        // 以50px步长逐行扫描，找到第一个不是纯色的行（每行背景都是纯色的）
        findStartY@ for (y in config.underGameScoreY..h step 50) {
            val start = image.getRGB(0, y)
            for (x in 1..(w - 1)) {
                val node = image.getRGB(x, y)
                if (start != node) {
                    startY = y - 50
                    break@findStartY
                }
            }
        }

        // 通过颜色区间判断点是否为棋子 r in (50, 60); g in (53, 63); b in (95, 110)
        var pieceXSum = 0 // 所有棋子点x坐标之和
        var pieceCount = 0 // 所有棋子点总数
        var pieceYMax = 0 // 所有棋子点最大Y坐标
        (startY..(h - 1)).forEach { y ->
            (0..(w - 1)).forEach { x ->
                val rgb = image.rgb(x, y)
                if (rgb["r"] in 51..59 && rgb["g"] in 54..62 && rgb["b"] in 96..109) {
                    // 找到棋子
                    pieceXSum += x
                    pieceCount++
                    pieceYMax = max(y, pieceYMax)
                }
            }
        }

        if (0 == pieceCount) {
            return mapOf("pieceX" to pieceX, "pieceY" to pieceY, "boardX" to boardX, "boardY" to boardY)
        }

        pieceX = pieceXSum / pieceCount // 棋子X中心点坐标
        pieceY = pieceYMax - config.pieceBaseHeight / 2 // 棋子Y中心点坐标

        // 限制x扫描范围，避免音符BUG
        var xStart = 0
        var xEnd = pieceX
        if (pieceX < w / 2) {
            xStart = pieceX
            xEnd = w - 1
        }

        // 逐行扫描，根据棋盘与背景的色差寻找棋盘点

        var boardYMin = 0

        // 由上向下逐行扫描，只要扫到了棋盘，就已经可以计算出棋盘X方向中心点坐标，同时找到棋盘的上顶点
        for (y in startY..(h - 1)) {

            var boardXSum = 0 // 所有棋盘点x坐标之和
            var boardCount = 0 // 所有棋盘点总数

            val startRgb = image.rgb(w / 8, y)
            for (x in xStart..xEnd) {
                val nodeRgb = image.rgb(x, y)
                if (abs(x - pieceX) < config.pieceBodyWidth) {
                    // 棋子比下一个棋盘还高的情况（排除将棋子当做棋盘的情况）
                    continue
                }

                if (abs(nodeRgb["r"]!! - startRgb["r"]!!) +
                        abs(nodeRgb["g"]!! - startRgb["g"]!!) +
                        abs(nodeRgb["b"]!! - startRgb["b"]!!) > 20) {
                    // 找到棋盘
                    boardXSum += x
                    boardCount++
                }
            }

            if (boardCount > 0) {
                // 找到棋盘上顶点
                // 由上向下逐行扫描，只要扫到了棋盘，就已经可以计算出棋盘X方向中心点坐标
                boardX = boardXSum / boardCount
                boardYMin = y
                graphics.drawOval(boardX - 5, y - 5, 10, 10)
                break
            }
        }

        // 下一个棋盘与当前棋盘的角度为30°，通过此计算棋盘Y中心点坐标
        boardY = (pieceY - abs(boardX - pieceX) * sqrt(3F) / 3 /* tan(π/6) */).toInt()

        // 如果上一跳命中中心，则下个目标中心会出现 r245 g245 b245 的点
        // 利用此特性，可以更精准的找到中心点
        for (y in boardYMin..(boardYMin + 200)) {
            val nodeRgb = image.rgb(boardX, y)
            if (abs(nodeRgb["r"]!! - 245) + abs(nodeRgb["g"]!! - 245) + abs(nodeRgb["b"]!! - 245) == 0) {
                boardY = y
                break
            }
        }

        return mapOf("pieceX" to pieceX, "pieceY" to pieceY, "boardX" to boardX, "boardY" to boardY)
    }

    private fun resetButtonPosition(w: Int, h: Int) {
        config.swipe["x"] = w / 2
        config.swipe["y"] = (1584 * (h / 1920.0)).toInt()
    }

    private fun jump(distance: Double): Int {
        val pressCoefficient = config.pressCoefficient - (distance - 480) * 0.00065
        println(ansi().fgBrightBlack().a("跳跃长度 ${distance}px | 按压系数 $pressCoefficient"))

        val pressTime = max(distance * pressCoefficient, 200.0).toInt()
        execAdb("shell", "input", "swipe",
                config.swipe["x"].toString(),
                config.swipe["y"].toString(),
                (config.swipe["x"]!! + random.nextInt(100) - 50).toString(),
                (config.swipe["y"]!! + random.nextInt(20) - 10).toString(),
                pressTime.toString())
        return pressTime
    }

    private fun saveDebugScreenShot(image: BufferedImage, coordinate: Map<String, Int>) {
        val w = image.width
        val h = image.height
        var graphics = image.graphics

        graphics.color = Color.RED
        graphics.drawLine(0, coordinate["pieceY"]!!, w, coordinate["pieceY"]!!)
        graphics.drawLine(coordinate["pieceX"]!!, 0, coordinate["pieceX"]!!, h)

        graphics.color = Color.YELLOW
        graphics.drawLine(0, coordinate["boardY"]!!, w, coordinate["boardY"]!!)
        graphics.drawLine(coordinate["boardX"]!!, 0, coordinate["boardX"]!!, h)

        graphics.color = Color.GREEN
        graphics.drawLine(coordinate["boardX"]!!, coordinate["boardY"]!!, coordinate["pieceX"]!!, coordinate["pieceY"]!!)

        ImageIO.write(image, "png", File(debugDir, "${sf.format(Date())}.png").outputStream())
    }

    override fun run(vararg args: String?) {
        if (debug) {
            File(debugDir).mkdirs()
        }

        console?.readLine(ansi().bold().fgBrightRed().a("请确保 1.已安装adb 2.将手机开发者选项打开 3.连接至电脑 4.打开跳一跳并开始游戏 (Enter)").reset().toString())
        showDeviceInfo()
        loadConfig()


        var playNum = 0
        var pauseNum = random.nextInt(20) + 10
        while (true) {
            playNum++

            val image = screenShot()
            val coordinate = findPieceAndBoard(image)

            resetButtonPosition(image.width, image.height)

            val distance = sqrt(Math.pow((coordinate["pieceX"]!! - coordinate["boardX"]!!).toDouble(), 2.0) +
                    Math.pow((coordinate["pieceY"]!! - coordinate["boardY"]!!).toDouble(), 2.0))
            print(ansi().fgBrightBlack().a("棋子(${coordinate["pieceX"]},${coordinate["pieceY"]}) | 棋盘(${coordinate["boardX"]},${coordinate["boardY"]}) | ").reset())
            jump(distance)

            if (debug) {
                saveDebugScreenShot(image, coordinate)
            }

            when (playNum) {
                in 0..pauseNum -> {
                    // 随机等待1~3秒
                    runBlocking { delay(random.nextInt(2000) + 1000L, TimeUnit.MILLISECONDS) }
                }
                else -> {
                    runBlocking {
                        println(ansi().fgGreen().a("\n都玩儿 $playNum 局了，休息一会~").reset())

                        ((random.nextInt(5) + 5) downTo 1).forEach {
                            println(ansi().eraseLine(Ansi.Erase.ALL).fgGreen().a("${it}s 后继续...").reset())
                            delay(1, TimeUnit.SECONDS)
                        }
                        println(ansi().eraseLine(Ansi.Erase.ALL).fgGreen().a("Come On ...\n").reset())

                        playNum = 0
                        pauseNum = random.nextInt(20) + 10
                    }
                }
            }
        }
    }
}

@Component
class OnFailed : ApplicationListener<ApplicationFailedEvent> {
    private val adbError = "呀，adb失败了！请确保 1.已安装adb 2.将手机开发者选项打开 3.连接至电脑"
    private val error = "呀，程序崩溃了？！请确保已安装adb"

    override fun onApplicationEvent(event: ApplicationFailedEvent) = println(ansi().bold().bgRed().fgBrightYellow().a(when (event.exception) {
        is ExecuteException -> adbError
        is UndeclaredThrowableException -> when (event.exception.cause) {
            is ExecuteException -> adbError
            else -> error
        }
        else -> error
    }).reset())
}

fun main(args: Array<String>) {
    println(ansi().a("正在启动，请耐心等待...").fgBrightYellow())
    SpringApplication.run(JumpApp::class.java, *args)
}