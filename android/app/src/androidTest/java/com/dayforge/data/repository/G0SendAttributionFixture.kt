package com.dayforge.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.dayforge.data.local.TokenCipher
import org.junit.Before

/** Adds observation to the existing real socket, Keystore and sealed count fixture. */
abstract class G0SendAttributionFixture : G0ShortCountFixture() {
    internal val sendTrace = G0SendWindowTrace()
    override fun isolatedDataStore(data: DataStore<Preferences>) = sendTrace.wrap(super.isolatedDataStore(data))
    override fun tokenCipher(): TokenCipher = sendTrace.wrap(super.tokenCipher())
    @Before fun observeHttpCalls() {
        client = client.newBuilder().eventListenerFactory(sendTrace.listeners()).build()
    }
}
