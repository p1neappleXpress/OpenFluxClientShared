package io.openflux.desktop.model

import kotlinx.serialization.json.JsonPrimitive

/**
 * Creating the channel's board as the signed-in Yandex user, from the
 * Boards page itself, with the calls its web client makes: a new board
 * (make-whiteboard), then guests allowed in as editors (guest_mode,
 * guest_role 1), which is how the core's boards transport joins it.
 */
object YandexBoards {
    const val CABINET = "https://boards.yandex.ru/cabinet/"
    const val SITE = "https://boards.yandex.ru/"
    private val HASH = Regex("""[?&]hash=([0-9a-f]{32})(?:&|#|$)""")

    /** The board link with only its hash, null if [url] is not a Yandex board. */
    fun clean(url: String): String? {
        val text = url.trim()
        if (!text.startsWith(SITE)) return null
        val hash = HASH.find(text)?.groupValues?.get(1) ?: return null
        return "https://boards.yandex.ru/whiteboard/?hash=$hash"
    }

    /** Returns JSON: {"state": "waiting" | "done" | "fail", "url", "error"}. */
    fun script(name: String): String = """
(async () => {
  const out = (o) => JSON.stringify(o);
  try {
    if (!window.whiteboard_csrf_token) return out({state: 'waiting'});
    const enc = (o) => btoa(unescape(encodeURIComponent(JSON.stringify(o))));
    const call = async (action, body) => {
      if (window.whiteboard_client_token) body.token = window.whiteboard_client_token;
      body.csrf = window.whiteboard_csrf_token;
      const r = await fetch('/api', {method: 'POST', credentials: 'include',
        headers: {'Content-Type': 'application/json'}, body: JSON.stringify({action: action, content: enc(body)})});
      let j = null; try { j = await r.json(); } catch (e) {}
      if (j && j.csrf) window.whiteboard_csrf_token = j.csrf;
      if (!r.ok || !j || j.error) throw new Error(action + ': ' + ((j && j.error) || r.status));
      return j;
    };
    const board = await call('make-whiteboard', {name: ${JsonPrimitive(name)}, access_type: 10});
    if (!board.hash) throw new Error('доска не создалась');
    const props = Object.assign({}, board.properties || {}, {guest_mode: true, guest_role: 1});
    const open = await call('update-dashboard-presentation', {media: board.hash, properties: props});
    if (open.result !== true) throw new Error('не удалось открыть доску гостям на редактирование');
    return out({state: 'done', url: 'https://boards.yandex.ru/whiteboard/?hash=' + board.hash});
  } catch (e) { return out({state: 'fail', error: String((e && e.message) || e)}); }
})()
    """.trimIndent()
}
