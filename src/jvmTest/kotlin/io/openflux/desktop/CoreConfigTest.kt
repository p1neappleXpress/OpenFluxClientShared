package io.openflux.desktop

import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.CoreConfig
import io.openflux.desktop.model.CorePaths
import io.openflux.desktop.model.ExitBackend
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.NetworkKind
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreConfigTest {
    private val paths = CorePaths("C:/rt/key", "C:/rt/p.conf", "C:/cfg/cookies/p.json", "C:/rt/ipc.sock")
    private val secret = "f".repeat(64)
    private val session = Profile(
        id = "p",
        name = "Nodes",
        transport = TransportType.VYANDEX,
        value = "https://disk.yandex.ru/i/one",
        secret = secret,
        session = true,
        priority = 100,
        extras = listOf(
            ExtraTransport(TransportType.VYANDEX, "https://disk.yandex.ru/i/two", priority = 90),
            ExtraTransport(TransportType.DIRECT, "203.0.113.10:8445", priority = 50),
        ),
    )

    @Test
    fun clientConfNamesNetworks() {
        val bound = session.copy(
            network = NetworkKind.Cellular,
            extras = listOf(ExtraTransport(TransportType.MAILRU, "https://cloud.mail.ru/public/a/b", priority = 90, network = NetworkKind.Wifi)),
        )
        val conf = CoreConfig.build(bound, AppSettings(), paths).conf!!
        val lines = conf.lines().map { it.trim() }
        assertEquals("Network = cellular", lines[lines.indexOf("[Transport vyandex]") + 3], conf)
        assertTrue("Network = wifi" in conf)
        assertFalse("Bonding" in conf)
        // Without a network nothing changes.
        val plain = CoreConfig.build(session, AppSettings(), paths).conf!!
        assertFalse("Network =" in plain)
    }

    @Test
    fun sessionClientConf() {
        val launch = CoreConfig.build(session, AppSettings(socksPort = 1090), paths)
        val conf = launch.conf!!
        assertTrue("Role = client" in conf)
        assertTrue("Socks5 = 127.0.0.1:1090" in conf)
        assertTrue("[Transport vyandex]" in conf && "[Transport vyandex-2]" in conf && "[Transport direct]" in conf)
        assertTrue("Dial = 203.0.113.10:8445" in conf)
        assertTrue("EncryptionKeyFile = C:/rt/key" in conf)
        assertFalse(secret in conf, "the key goes in its own file, not in the .conf")
        assertEquals(
            listOf(
                "--config", "C:/rt/p.conf", "--ipc-socket=C:/rt/ipc.sock",
                "--http-proxy=127.0.0.1:1091",
            ),
            launch.arguments,
        )
        assertEquals("127.0.0.1:1090", launch.socksAddress)
        assertEquals("127.0.0.1:1091", launch.httpProxyAddress)
        assertTrue(launch.usesIpc)
    }

    /** The core refuses Telemost under its TUN: a Session goes without it, Telemost alone does not start. */
    @Test
    fun telemostIsLeftOutOfTheFullTunnel() {
        val withTelemost = session.copy(extras = session.extras + ExtraTransport(TransportType.TELEMOST, "https://telemost.yandex.ru/j/88936358186517", priority = 85))
        assertTrue("Type = telemost" in CoreConfig.build(withTelemost, AppSettings(), paths).conf!!, "the proxies keep it")
        val tun = CoreConfig.build(withTelemost, AppSettings(fullTunnel = true), paths).conf!!
        assertFalse("telemost" in tun)
        assertTrue("Type = vyandex" in tun && "Type = direct" in tun, "the rest still connects")

        val alone = Profile(id = "t", name = "T", transport = TransportType.TELEMOST, value = "https://telemost.yandex.ru/j/88936358186517", secret = secret)
        assertTrue("--transport=telemost" in CoreConfig.build(alone, AppSettings(), paths).arguments)
        assertEquals(CoreConfig.TELEMOST_TUN, assertFailsWith<IllegalArgumentException> { CoreConfig.build(alone, AppSettings(fullTunnel = true), paths) }.message)
        val onlyTelemost = alone.copy(session = true)
        assertEquals(CoreConfig.TELEMOST_TUN, assertFailsWith<IllegalArgumentException> { CoreConfig.build(onlyTelemost, AppSettings(fullTunnel = true), paths) }.message)
    }

    @Test
    fun fullTunnelUsesTheTunInboundAndNoProxies() {
        val launch = CoreConfig.build(session, AppSettings(fullTunnel = true), paths)
        val conf = launch.conf!!
        assertTrue("Inbound = tun" in conf)
        assertFalse("Socks5" in conf)
        assertEquals(
            listOf("--config", "C:/rt/p.conf", "--ipc-socket=C:/rt/ipc.sock"),
            launch.arguments,
        )
        assertNull(launch.socksAddress)
        assertNull(launch.httpProxyAddress)

        val classic = CoreConfig.build(
            Profile(id = "c", name = "C", transport = TransportType.VYANDEX, value = "https://disk.yandex.ru/i/one"),
            AppSettings(fullTunnel = true),
            paths.copy(keyFile = null, ipcSocket = null),
        )
        assertTrue("--inbound=tun" in classic.arguments)
        assertFalse(classic.arguments.any { it.startsWith("--socks5") || it.startsWith("--http-proxy") })
        assertNull(classic.httpProxyAddress)
    }

    @Test
    fun sessionExitListensForDirect() {
        val settings = AppSettings(mode = ConnectionMode.Exit, exitDirectPort = 9000, exitShareHost = "my.host", debugLevel = 2)
        val launch = CoreConfig.build(session, settings, paths)
        val conf = launch.conf!!
        assertTrue("Role = exit" in conf && "Mode = l4" in conf)
        assertFalse("Socks5" in conf)
        assertFalse("Dial =" in conf)
        assertTrue("Listen = 0.0.0.0:9000" in conf)
        assertTrue(launch.arguments.containsAll(listOf("--share", "--share-host=my.host", "--debug=2")))
        assertFalse(launch.arguments.any { it.startsWith("--http-proxy") })
        assertNull(launch.socksAddress)
    }

    @Test
    fun aNodesProfileDoesNotRunAsAnExitHere() {
        val node = session.copy(source = ProfileSource.Node)
        val exit = AppSettings(mode = ConnectionMode.Exit)
        val e = assertFailsWith<IllegalArgumentException> { CoreConfig.build(node, exit, paths) }
        assertTrue("режим «Клиент»" in e.message.orEmpty())
        // As a client it connects to the node, as before.
        assertTrue("Role = client" in CoreConfig.build(node, AppSettings(), paths).conf!!)
        assertNull(session.exitProblem())
    }

    @Test
    fun sessionExitWithoutDirectDoesNotListenForIt() {
        val noDirect = session.copy(extras = session.extras.filter { it.type != TransportType.DIRECT })
        val launch = CoreConfig.build(noDirect, AppSettings(mode = ConnectionMode.Exit, exitDirectPort = 9000), paths)
        val conf = launch.conf!!
        assertFalse("[Transport direct]" in conf, "direct was not chosen in the profile")
        assertFalse("Listen =" in conf)
        assertTrue("[Transport vyandex]" in conf && "[Transport vyandex-2]" in conf)
    }

    @Test
    fun carrierLabels() {
        assertEquals("Board", TransportType.carrierLabel("boards"))
        assertEquals("Board 2", TransportType.carrierLabel("boards-2"))
        assertEquals("Direct", TransportType.carrierLabel("direct"))
        assertEquals("custom", TransportType.carrierLabel("custom"))
    }

    @Test
    fun classicUsesFlags() {
        val classic = Profile(id = "c", name = "Old", transport = TransportType.YANDEX, value = "https://disk.yandex.ru/i/x")
        val launch = CoreConfig.build(classic, AppSettings(), paths.copy(keyFile = null))
        assertNull(launch.conf)
        assertEquals(
            listOf(
                "--role=client", "--inbound=socks5", "--socks5=127.0.0.1:1080", "--http-proxy=127.0.0.1:1081",
                "--transport=yandex", "--codec=batched", "--url=https://disk.yandex.ru/i/x",
                "--cookie-store=C:/cfg/cookies/p.json", "--ipc-socket=C:/rt/ipc.sock",
            ),
            launch.arguments,
        )
        // IPC brings the traffic totals; the state still comes from the log.
        assertFalse(launch.usesIpc)
    }

    @Test
    fun classicRunsAsExit() {
        val classic = Profile(id = "c", name = "Old", transport = TransportType.YANDEX, value = "https://disk.yandex.ru/i/x", secret = secret)
        val launch = CoreConfig.build(classic, AppSettings(mode = ConnectionMode.Exit, exitShareHost = "my.host", debugLevel = 1), paths)
        assertNull(launch.conf)
        assertEquals(
            listOf(
                "--role=exit", "--mode=l4",
                "--transport=yandex", "--codec=batched", "--url=https://disk.yandex.ru/i/x",
                "--encryption-key-file=C:/rt/key", "--cookie-store=C:/cfg/cookies/p.json", "--ipc-socket=C:/rt/ipc.sock",
                "--share", "--share-host=my.host", "--debug=1",
            ),
            launch.arguments,
        )
        assertNull(launch.socksAddress)
        assertNull(launch.httpProxyAddress)
        assertFalse(launch.usesIpc)
    }

    @Test
    fun exitBackendIsWhatTheUserChoseOnClassicAndSessionProfiles() {
        val classic = Profile(id = "c", name = "Old", transport = TransportType.YANDEX, value = "https://disk.yandex.ru/i/x", secret = secret)
        fun mode(profile: Profile, backend: ExitBackend) =
            CoreConfig.build(profile, AppSettings(mode = ConnectionMode.Exit, exitBackend = backend), paths)
        assertTrue("--mode=l3" in mode(classic, ExitBackend.L3).arguments)
        assertTrue("--mode=l4" in mode(classic, ExitBackend.L4).arguments)
        assertTrue("Mode = l3" in mode(session, ExitBackend.L3).conf!!)
        assertTrue("Mode = l4" in mode(session, ExitBackend.L4).conf!!)
        // L4 stays what a profile gets when nothing was chosen.
        assertEquals(ExitBackend.L4, AppSettings().exitBackend)
    }

    @Test
    fun aClientIgnoresTheExitBackend() {
        val client = CoreConfig.build(session, AppSettings(mode = ConnectionMode.Client, exitBackend = ExitBackend.L3), paths)
        assertFalse("Mode =" in client.conf!!)
        val classic = Profile(id = "c", name = "Old", transport = TransportType.YANDEX, value = "https://disk.yandex.ru/i/x", secret = secret)
        val args = CoreConfig.build(classic, AppSettings(mode = ConnectionMode.Client, exitBackend = ExitBackend.L3), paths).arguments
        assertFalse(args.any { it.startsWith("--mode") })
    }

    @Test
    fun refusesWhatTheCoreWouldMisread() {
        val cut = session.copy(value = "https://disk.yandex.ru/i/one#frag")
        assertFailsWith<IllegalArgumentException> { CoreConfig.build(cut, AppSettings(), paths) }
        assertFailsWith<IllegalArgumentException> { CoreConfig.build(session.copy(secret = "short"), AppSettings(), paths) }
    }

    /**
     * The core derives the KDF context by its rule, the one the exit uses:
     * the app passes one only when the profile carries it (from a link).
     */
    @Test
    fun contextOnlyWhenTheProfileHasOne() {
        assertFalse(CoreConfig.build(session, AppSettings(), paths).arguments.any { it.startsWith("--session-context") })
        val imported = session.copy(context = "https://disk.yandex.ru/i/node")
        assertTrue("--session-context=https://disk.yandex.ru/i/node" in CoreConfig.build(imported, AppSettings(), paths).arguments)
    }
}
