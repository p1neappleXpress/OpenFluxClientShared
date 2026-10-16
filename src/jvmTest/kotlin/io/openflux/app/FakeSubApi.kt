package io.openflux.app

import io.openflux.desktop.model.AccountInfo
import io.openflux.desktop.model.AuthResult
import io.openflux.desktop.model.OrderInfo
import io.openflux.desktop.model.PlansResult
import io.openflux.desktop.model.ServiceEndpoint
import io.openflux.desktop.model.SubscriptionDocument
import io.openflux.desktop.service.SubscriptionApi
import io.openflux.desktop.model.SubscriptionException

/** The subscription service in memory (from SubscriptionManagerTest's fake), for the new UI's tests. */
class FakeSubApi : SubscriptionApi {
    val calls = mutableListOf<String>()
    var document = SubscriptionDocument()
    var failure: SubscriptionException? = null
    var loginFailure: SubscriptionException? = null
    var logoutFailure: SubscriptionException? = null
    var deleteFailure: SubscriptionException? = null
    var orderStatus = "pending"
    private var tokens = 0

    private fun account(login: String) = AccountInfo(id = "u1", login = login, status = document.status, expires = document.expires)
    private fun auth(login: String): AuthResult {
        tokens++
        return AuthResult("token-$tokens", "dev-$tokens", "RECOVERY-KEY", account(login))
    }

    var plans = PlansResult()
    override suspend fun plans(ep: ServiceEndpoint) = plans
    override suspend fun register(ep: ServiceEndpoint, login: String, password: String, deviceName: String): AuthResult {
        calls += "register:$login|$deviceName"
        return auth(login)
    }

    override suspend fun login(ep: ServiceEndpoint, login: String, password: String, deviceName: String, replaceDeviceId: String?): AuthResult {
        calls += "login:$login|" + (replaceDeviceId?.let { "replace=$it|" } ?: "") + deviceName
        loginFailure?.let { throw it }
        return auth(login)
    }

    override suspend fun recover(ep: ServiceEndpoint, login: String, recoveryKey: String, newPassword: String, deviceName: String): AuthResult {
        calls += "recover:$login"
        return auth(login)
    }

    override suspend fun account(ep: ServiceEndpoint, token: String): AccountInfo {
        calls += "account:$token"
        failure?.let { throw it }
        return account("ivan")
    }

    override suspend fun document(ep: ServiceEndpoint, token: String): SubscriptionDocument {
        calls += "document:$token"
        failure?.let { throw it }
        return document
    }

    override suspend fun keyDocument(ep: ServiceEndpoint, url: String): SubscriptionDocument {
        calls += "key:$url"
        failure?.let { throw it }
        return document
    }

    override suspend fun startTrial(ep: ServiceEndpoint, token: String) = account("ivan")
    override suspend fun checkout(ep: ServiceEndpoint, token: String, planId: String): OrderInfo {
        calls += "checkout:$planId"
        failure?.let { throw it }
        return OrderInfo("o1", planId, "pending")
    }

    override suspend fun order(ep: ServiceEndpoint, token: String, orderId: String): OrderInfo {
        calls += "order:$orderId"
        failure?.let { throw it }
        return OrderInfo(orderId, "month", orderStatus, document.expires)
    }

    override suspend fun changePassword(ep: ServiceEndpoint, token: String, password: String, newPassword: String) = account("ivan")
    override suspend fun logout(ep: ServiceEndpoint, token: String) {
        calls += "logout:$token"
        logoutFailure?.let { throw it }
    }

    override suspend fun removeDevice(ep: ServiceEndpoint, token: String, deviceId: String): AccountInfo {
        failure?.let { throw it }
        return account("ivan")
    }

    override suspend fun deleteAccount(ep: ServiceEndpoint, token: String, password: String) {
        calls += "delete:$token"
        deleteFailure?.let { throw it }
    }

    override suspend fun health(ep: ServiceEndpoint) {
        calls += "health:${ep.url}"
    }
}
