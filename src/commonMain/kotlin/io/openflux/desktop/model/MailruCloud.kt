package io.openflux.desktop.model

import kotlinx.serialization.json.JsonPrimitive

/**
 * Creating the channel's document as the signed-in Mail.ru user, from the
 * Cloud page itself, with the calls its web client makes: a .docx in
 * /openflux, published, and the link switched to editing. The core's
 * mailru transport then opens it anonymously (r7/edit), which is checked
 * before the link is handed back.
 */
object MailruCloud {
    const val HOME = "https://cloud.mail.ru/home"
    private val PUBLIC = Regex("""^https://cloud\.mail\.ru/public/[A-Za-z0-9]{2,40}/[A-Za-z0-9]{2,40}$""")

    /** A public Cloud link without query or trailing slash, null if it is not one. */
    fun clean(url: String): String? = url.trim().replace(Regex("[?#].*$"), "").trimEnd('/').takeIf(PUBLIC::matches)

    /** Returns JSON: {"state": "waiting" | "done" | "fail", "url", "error"}. */
    fun script(name: String): String = """
(async () => {
  const out = (o) => JSON.stringify(o);
  try {
    const t = await fetch('/api/v2/tokens/csrf', {method: 'POST', credentials: 'include'});
    if (t.status === 403) return out({state: 'waiting'});
    const token = ((await t.json()).body || {}).token;
    if (!token) return out({state: 'waiting'});
    const form = async (m, p) => {
      const r = await fetch('/api/v2/' + m, {method: 'POST', credentials: 'include',
        headers: {'X-CSRF-Token': token}, body: new URLSearchParams(Object.assign({token: token}, p))});
      let j = null; try { j = await r.json(); } catch (e) {}
      return {status: r.status, body: j && j.body};
    };
    const dir = await form('folder/add', {home: '/openflux', conflict: 'strict'});
    const exists = dir.body && dir.body.home && dir.body.home.error === 'exists';
    if (dir.status !== 200 && !exists) throw new Error('папка /openflux: ' + dir.status);
    const path = '/openflux/' + ${JsonPrimitive(name)} + '.docx';
    const c = await fetch('/api/v4/document/create', {method: 'POST', credentials: 'include',
      headers: {'Content-Type': 'application/json', 'X-CSRF-Token': token}, body: JSON.stringify({path: path})});
    if (!c.ok) throw new Error('создание документа: ' + c.status);
    const pub = await form('file/publish', {home: path});
    const weblink = pub.body;
    if (pub.status !== 200 || typeof weblink !== 'string') throw new Error('публикация: ' + pub.status);
    const w = await form('weblinks/writable', {weblink: weblink});
    if (w.status !== 200) throw new Error('редактирование по ссылке: ' + w.status);
    // What the tunnel will see: the document opened by an anonymous visitor.
    const e = await fetch('/api/v4/r7/edit', {method: 'POST', credentials: 'omit',
      headers: {'Content-Type': 'application/json', 'X-Api-Version': '4'},
      body: JSON.stringify({'x-email': 'anonym', public: '/' + weblink, platform: 'desktop_web'})});
    const doc = e.ok ? (await e.json()).document : null;
    if (!doc || !doc.permissions || !doc.permissions.edit) throw new Error('документ не открылся на редактирование по ссылке');
    return out({state: 'done', url: 'https://cloud.mail.ru/public/' + weblink});
  } catch (e) { return out({state: 'fail', error: String((e && e.message) || e)}); }
})()
    """.trimIndent()
}
