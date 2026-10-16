package io.openflux.desktop.model

/**
 * Making the node's Telemost conference as the signed-in Yandex user, on the Telemost page itself: the person
 * presses «Создать видеовстречу» there (a conference needs a Yandex sign-in to be made), the page goes to the
 * conference's own address (/j/…), and that address is the link the node joins.
 */
object YandexTelemost {
    const val SITE = "https://telemost.yandex.ru/"
    const val HINT = "Нажмите «Создать видеовстречу» в окне Телемоста"

    /** The conference link without its query, null if [url] is not one. */
    fun clean(url: String): String? = NodeTransports.cleanTelemost(url)

    /** Returns JSON: {"state": "waiting" | "done", "url"}: done once the page is on a conference. */
    @Suppress("UNUSED_PARAMETER")
    fun script(name: String): String = """
(() => {
  const m = location.href.match(/^https:\/\/telemost\.yandex\.ru\/j\/[0-9]{6,32}/);
  return JSON.stringify(m ? {state: 'done', url: m[0]} : {state: 'waiting'});
})()
    """.trimIndent()
}
