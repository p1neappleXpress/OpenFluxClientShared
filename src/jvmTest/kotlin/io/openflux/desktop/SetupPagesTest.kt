package io.openflux.desktop

import io.openflux.desktop.core.IpcCookiesRequest
import io.openflux.desktop.model.SetupPages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupPagesTest {
    private fun tag(s: String) = "<script>$s</script>"

    @Test
    fun theBridgeGoesBeforeThePagesOwnScriptsAndKeepsStandardsMode() {
        val s = "S"
        assertEquals("<!doctype html><html><head>${tag(s)}<title>x</title></head>", SetupPages.inject("<!doctype html><html><head><title>x</title></head>", s))
        assertEquals("<HEAD lang=ru>${tag(s)}<b>", SetupPages.inject("<HEAD lang=ru><b>", s))
        // <header> is not <head>
        assertEquals("${tag(s)}<header>h</header>", SetupPages.inject("<header>h</header>", s))
        assertEquals("<html lang=ru>${tag(s)}<body>x", SetupPages.inject("<html lang=ru><body>x", s))
        // a script in front of the doctype would be quirks mode
        assertEquals("<!DOCTYPE html>${tag(s)}<p>x", SetupPages.inject("<!DOCTYPE html><p>x", s))
        assertEquals("${tag(s)}<p>x</p>", SetupPages.inject("<p>x</p>", s))
        assertEquals(tag(s), SetupPages.inject("", s))
        // lowercasing must not move offsets (İ lowercases to two chars)
        assertEquals("<!-- İİİ --><head>${tag(s)}<b>", SetupPages.inject("<!-- İİİ --><head><b>", s))
    }

    @Test
    fun theBridgeDefinesSubmitOnceAndAnnouncesIt() {
        val js = SetupPages.bridgeScript("native(j)")
        assertTrue(js.startsWith("(function(){if(window.openfluxSubmit)return;"))
        assertTrue("window.openfluxSubmit=function(p)" in js && "native(j)" in js)
        assertTrue(SetupPages.READY_EVENT in js)
    }

    @Test
    fun onlyLoopbackHttpIsAnOwnServerAddress() {
        assertEquals("http://127.0.0.1:41234/", SetupPages.loopbackOrigin("http://127.0.0.1:41234/setup?a=1"))
        assertEquals("http://localhost:8080/", SetupPages.loopbackOrigin("HTTP://localhost:8080"))
        assertEquals("http://[::1]:9/", SetupPages.loopbackOrigin("http://[::1]:9/x"))
        assertNull(SetupPages.loopbackOrigin("https://127.0.0.1:41234/"))
        assertNull(SetupPages.loopbackOrigin("http://example.com:80/"))
        assertNull(SetupPages.loopbackOrigin("http://127.0.0.1/"))
        assertNull(SetupPages.loopbackOrigin("http://127.0.0.1:0/"))
        assertNull(SetupPages.loopbackOrigin("http://127.0.0.1:70000/"))
        assertNull(SetupPages.loopbackOrigin("http://127.0.0.1:80@evil.com:80/"))
        assertNull(SetupPages.loopbackOrigin("http://192.168.1.1:80/"))
        assertNull(SetupPages.loopbackOrigin("javascript:alert(1)"))
        assertNull(SetupPages.loopbackOrigin("file:///etc/passwd"))
    }

    @Test
    fun aCoreBeforeTheOwnFieldStillMeansAnOwnPageWhenItSendsHtml() {
        assertTrue(IpcCookiesRequest("t", "", html = "<p>x</p>").isOwn)
        assertTrue(IpcCookiesRequest("t", "http://127.0.0.1:5/", own = true).isOwn)
        assertFalse(IpcCookiesRequest("t", "https://docs.yandex.ru/d").isOwn)
    }

    @Test
    fun aSubmissionIsReadLikeTheCoreReadsIt() {
        assertEquals(
            mapOf("token" to "abc", "port" to "8080", "on" to "true"),
            SetupPages.flatten("""{"token":"abc","port":8080,"on":true}"""),
        )
        assertEquals(mapOf("a" to "1"), SetupPages.flatten("""{"client":{"a":"1"},"node":{"b":"2"}}"""))
        assertEquals(mapOf("a" to "1"), SetupPages.flatten("""{"a":"1","o":{"x":1},"l":[1],"n":null}"""))
        assertEquals(mapOf("v" to "1.50"), SetupPages.flatten("""{"v":1.50}"""))
        assertEquals(mapOf("id" to "12345678901234567890"), SetupPages.flatten("""{"id":12345678901234567890}"""))
        assertEquals(mapOf("a" to "1"), SetupPages.flatten("""{"":"x","a":"1"}"""))
        for (bad in listOf("{}", """{"o":{}}""", """{"client":{},"node":{"b":"2"}}""", "nope", "[1]")) {
            assertFailsWith<IllegalArgumentException>(bad) { SetupPages.flatten(bad) }
        }
    }
}
