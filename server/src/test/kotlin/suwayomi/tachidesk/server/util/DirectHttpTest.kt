package suwayomi.tachidesk.server.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.Proxy
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DirectHttpTest {
    @Test
    fun `making a client direct clears the proxy and replaces the socket factory`() {
        val base = OkHttpClient()

        // The baseline keeps the check below from passing for the wrong reason: if OkHttp ever
        // stopped using the default factory, "not the default" would be true without any change.
        assertSame(SocketFactory.getDefault(), base.socketFactory)

        val direct = base.asDirectClient()

        // Both halves are required. OkHttp asks the system ProxySelector before it uses the socket
        // factory, so clearing only one of them leaves the request going to the configured proxy.
        assertSame(Proxy.NO_PROXY, direct.proxy)
        assertNotSame(SocketFactory.getDefault(), direct.socketFactory)
    }

    @Test
    fun `making a client direct keeps everything else the base client carries`() {
        val cookieJar = CookieJar.NO_COOKIES
        val interceptor = Interceptor { chain -> chain.proceed(chain.request()) }
        val base =
            OkHttpClient
                .Builder()
                .cookieJar(cookieJar)
                .addInterceptor(interceptor)
                .build()

        val direct = base.asDirectClient()

        assertSame(cookieJar, direct.cookieJar)
        assertEquals(base.interceptors, direct.interceptors)
        assertEquals(base.connectTimeoutMillis, direct.connectTimeoutMillis)
        assertEquals(base.readTimeoutMillis, direct.readTimeoutMillis)
    }
}
