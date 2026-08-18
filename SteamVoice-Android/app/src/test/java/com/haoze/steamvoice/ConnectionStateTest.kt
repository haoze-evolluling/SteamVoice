package com.haoze.steamvoice

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStateTest {
    @Test fun heartbeatTimeoutEntersReconnect() = assertEquals(ConnectionState.RECONNECTING, nextConnectionState(ConnectionState.CONNECTED, ConnectionEvent.HEARTBEAT_TIMEOUT))
    @Test fun reconnectAuthorizationConnects() = assertEquals(ConnectionState.CONNECTED, nextConnectionState(ConnectionState.RECONNECTING, ConnectionEvent.AUTHORIZED))
}
