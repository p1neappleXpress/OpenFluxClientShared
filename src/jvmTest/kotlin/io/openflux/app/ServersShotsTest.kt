package io.openflux.app

import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.servers.ServerLink
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.KnownServer
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.service.AppContainer
import io.openflux.app.platform.NoBrowserViews
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** The Servers section's screens and states, for review by eye (SHOTS=<dir>). */
class ServersShotsTest {
    private val doc = "https://docs.yandex.ru/edit/d/" + "a".repeat(20)

    private class World(val c: AppContainer, val link: ServerLink, val server: FakeServer)

    private fun world(connected: Boolean = true, nodes: Boolean = true): World {
        val server = FakeServer("203.0.113.7")
        if (nodes) {
            server.channels["of-k2m9"] = InstalledChannel("of-k2m9", "active", 8445, "c1", listOf(NodeTransport("vyandex", doc), NodeTransport("cupsonline", "r")), "l4", "a".repeat(64))
            server.channels["of-p7q1"] = InstalledChannel("of-p7q1", "failed", 8446, "c2", emptyList(), "l3", "b".repeat(64))
        }
        val c = testContainer(node = FakeNodeService(server, FakeServer("198.51.100.20")))
        c.settings.update { it.copy(knownServers = listOf(KnownServer("203.0.113.7", 22, "root"), KnownServer("198.51.100.20", 2222, "admin"))) }
        val link = ServerLink(c)
        if (connected) runBlocking { link.connect(SshTarget("203.0.113.7", 22, "root", password = "pw", hostKey = "SHA256:203.0.113.7")) }
        return World(c, link, server)
    }

    private fun shot(name: String, w: World, vararg stack: Dest, frame: Shots.Frame = Shots.PHONE) {
        val n = AppNavigator().apply { stack.forEach { open(it) } }
        w.c.settings.update { it.copy(navState = n.snapshot().encode()) }
        Shots.render(name, frame) { OpenFluxApp(w.c, NoScrollbars, Shortcuts(), NoBrowserViews, w.link) }
    }

    private val id = "203.0.113.7:22:root"

    @Test fun serversEmpty() {
        val c = testContainer()
        val n = AppNavigator().apply { open(Dest.Servers) }
        c.settings.update { it.copy(navState = n.snapshot().encode()) }
        Shots.render("servers-empty") { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
    }

    @Test fun serversList() = shot("servers", world(), Dest.Servers)
    @Test fun serversWide() = shot("servers", world(), Dest.Servers, Dest.Server(id), frame = Shots.WIDE)
    @Test fun addServer() = shot("server-add", world(connected = false), Dest.AddServer)
    @Test fun serverNodes() = shot("server-nodes", world(), Dest.Server(id))
    @Test fun serverNodesSmall() = shot("server-nodes", world(), Dest.Server(id), frame = Shots.SMALL)
    @Test fun serverNoAccess() = shot("server-login", world(connected = false), Dest.Server(id))
    @Test fun serverEmpty() = shot("server-empty", world(nodes = false), Dest.Server(id))
    @Test fun node() = shot("node", world(), Dest.Node(id, "of-k2m9"))
    @Test fun nodeSmall() = shot("node", world(), Dest.Node(id, "of-k2m9"), frame = Shots.SMALL)
    @Test fun nodeFailed() = shot("node-failed", world(), Dest.Node(id, "of-p7q1"))
    @Test fun newNode() = shot("node-new", world(), Dest.NewNode(id))

    private fun plain(name: String, c: AppContainer, vararg stack: Dest) {
        c.settings.update { it.copy(navState = AppNavigator().apply { stack.forEach { open(it) } }.snapshot().encode()) }
        Shots.render(name) { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
    }

    @Test fun newHosting() = plain("hosting-new", testContainer(), Dest.NewHosting)

    @Test fun hostingNode() {
        val h = FakeHosting().apply { running = true }
        val c = testContainer(hosting = h)
        c.profiles.upsert(
            io.openflux.desktop.model.Profile(
                id = "h1", name = "Хостинг · me.example", transport = io.openflux.desktop.model.TransportType.CUPSONLINE,
                value = "https://cups.online/r1", stream = true, source = io.openflux.desktop.model.ProfileSource.Node,
                phpNode = io.openflux.desktop.model.PhpNodeRef("https://me.example", "tok12345abcd"),
            ),
        )
        plain("hosting-node", c, Dest.Hosting("h1"))
    }

    @Test fun thisDevice() {
        val c = testContainer()
        c.profiles.upsert(io.openflux.desktop.model.Profile(id = "a", name = "Нода 203.0.113.7", secret = "s".repeat(64)))
        plain("this-device", c, Dest.ThisDevice)
    }
}
