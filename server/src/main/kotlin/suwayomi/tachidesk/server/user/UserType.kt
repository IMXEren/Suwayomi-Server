package suwayomi.tachidesk.server.user

import io.javalin.http.Context
import io.javalin.http.Header
import io.javalin.websocket.WsConnectContext
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.serverConfig

/**
 * Name of the cookie a web client sets so that its own sub-resource requests carry its token.
 *
 * A page opened with the token in its query string authenticates the navigation itself, but not
 * the files it then loads, and a browser cannot put an authorization header on those requests.
 */
const val SERVER_TOKEN_COOKIE: String = "suwayomi-server-token"

sealed class UserType {
    class Admin(
        val id: Int,
    ) : UserType()

    data object Visitor : UserType()
}

fun UserType.requireUser(): Int =
    when (this) {
        is UserType.Admin -> id
        UserType.Visitor -> throw UnauthorizedException()
    }

fun UserType.requireUserWithBasicFallback(ctx: Context): Int =
    when (this) {
        is UserType.Admin -> {
            id
        }

        UserType.Visitor if ctx.getAttribute(Attribute.TachideskBasic) -> {
            1
        }

        UserType.Visitor -> {
            ctx.header("WWW-Authenticate", "Basic")
            throw UnauthorizedException()
        }
    }

fun getUserFromToken(token: String?): UserType {
    if (serverConfig.authMode.value != AuthMode.UI_LOGIN) {
        return UserType.Admin(1)
    }

    if (token.isNullOrBlank()) {
        return UserType.Visitor
    }

    return Jwt.verifyJwt(token)
}

/**
 * The token a request presents, in the order the login modes look for it.
 */
fun getTokenFromContext(ctx: Context): String? {
    val authentication = ctx.header(Header.AUTHORIZATION) ?: ctx.cookie(SERVER_TOKEN_COOKIE)
    return authentication?.substringAfter("Bearer ") ?: ctx.queryParam("token")
}

fun getUserFromContext(ctx: Context): UserType {
    fun cookieValid(): Boolean {
        val username = ctx.sessionAttribute<String>("logged-in") ?: return false
        return username == serverConfig.authUsername.value
    }

    return when (serverConfig.authMode.value) {
        // NOTE: Basic Auth is expected to have been validated by JavalinSetup
        AuthMode.NONE, AuthMode.BASIC_AUTH -> {
            UserType.Admin(1)
        }

        AuthMode.SIMPLE_LOGIN -> {
            if (cookieValid()) UserType.Admin(1) else UserType.Visitor
        }

        AuthMode.UI_LOGIN -> {
            getUserFromToken(getTokenFromContext(ctx))
        }
    }
}

/**
 * The user a websocket handshake presents.
 *
 * [acceptCookie] is false where the cookie must not be enough on its own, because a browser
 * attaches cookies to a handshake made from any origin.
 */
fun getUserFromWsContext(
    ctx: WsConnectContext,
    acceptCookie: Boolean = true,
): UserType {
    fun cookieValid(): Boolean {
        val username = ctx.sessionAttribute<String>("logged-in") ?: return false
        return username == serverConfig.authUsername.value
    }

    return when (serverConfig.authMode.value) {
        // NOTE: Basic Auth is expected to have been validated by JavalinSetup
        AuthMode.NONE, AuthMode.BASIC_AUTH -> {
            UserType.Admin(1)
        }

        AuthMode.SIMPLE_LOGIN -> {
            if (cookieValid()) UserType.Admin(1) else UserType.Visitor
        }

        AuthMode.UI_LOGIN -> {
            val authentication =
                ctx.header(Header.AUTHORIZATION)
                    ?: ctx.header("Sec-WebSocket-Protocol")
                    ?: if (acceptCookie) ctx.cookie(SERVER_TOKEN_COOKIE) else null
            val token = authentication?.substringAfter("Bearer ") ?: ctx.queryParam("token")

            getUserFromToken(token)
        }
    }
}

class UnauthorizedException : IllegalStateException("Unauthorized")

class ForbiddenException : IllegalStateException("Forbidden")
