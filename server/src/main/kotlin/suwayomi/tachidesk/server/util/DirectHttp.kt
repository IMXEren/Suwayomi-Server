package suwayomi.tachidesk.server.util

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import javax.net.SocketFactory

/**
 * Socket factory that ignores the JVM-wide SOCKS proxy.
 *
 * Enabling the server's SOCKS proxy sets `socksProxyHost`/`socksProxyPort` as system properties,
 * which Java applies to every socket the process opens. A request to a service on the internal
 * network, such as the challenge solver or the browser VNC endpoint, is therefore sent to that
 * upstream proxy, which cannot resolve an internal name and closes the connection instead.
 *
 * Java offers no per-host bypass and OkHttp's default factory calls `Socket()`, which honours the
 * property, so `Socket(Proxy.NO_PROXY)` is the only way to opt out.
 */
private object DirectSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = Socket(Proxy.NO_PROXY).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket =
        Socket(Proxy.NO_PROXY).apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = Socket(Proxy.NO_PROXY).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket =
        Socket(Proxy.NO_PROXY).apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

/**
 * Return a copy of this client that always connects directly.
 *
 * The proxy has to be cleared as well as the socket factory replaced: OkHttp asks the system
 * proxy selector first, and that selector derives a SOCKS proxy from the same system property, so
 * it wins over the factory unless the proxy is set to [Proxy.NO_PROXY] here.
 */
internal fun OkHttpClient.asDirectClient(): OkHttpClient =
    newBuilder()
        .proxy(Proxy.NO_PROXY)
        .socketFactory(DirectSocketFactory)
        .build()

/**
 * A selector that never returns a proxy, for the JDK HTTP client.
 *
 * The JDK client takes a [ProxySelector] rather than a socket factory, so it cannot use
 * [asDirectClient]. This is the same bypass expressed for that API, kept here so that every
 * internal-service connection that has to ignore the JVM-wide SOCKS proxy has one home.
 */
internal object DirectProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)

    override fun connectFailed(
        uri: URI?,
        sa: SocketAddress?,
        ioe: IOException?,
    ) = Unit
}
