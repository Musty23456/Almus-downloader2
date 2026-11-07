package com.almus.ytdownloader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPolicyTest {
    private fun net(kind: NetworkKind, metered: Boolean = false, kbps: Int = 0, online: Boolean = true) = NetworkStatus(online, metered, kind, kbps)

    @Test fun ethernetAndWifiGetMoreThanMobileData() {
        val eth = ConnectionPolicy.ceiling(net(NetworkKind.Ethernet), 0)
        val wifi = ConnectionPolicy.ceiling(net(NetworkKind.Wifi), 0)
        val cell = ConnectionPolicy.ceiling(net(NetworkKind.Cellular, metered = true), 0)
        assertTrue(eth > wifi && wifi > cell)
        assertEquals(3, cell)
    }

    @Test fun meteredWifiIsTreatedLikeMobileData() {
        assertEquals(3, ConnectionPolicy.ceiling(net(NetworkKind.Wifi, metered = true), 0))
    }

    @Test fun slowLinksGetFewConnections() {
        assertEquals(2, ConnectionPolicy.ceiling(net(NetworkKind.Wifi, kbps = 1_500), 0))
        assertEquals(3, ConnectionPolicy.ceiling(net(NetworkKind.Wifi, kbps = 8_000), 0))
        assertEquals(6, ConnectionPolicy.ceiling(net(NetworkKind.Wifi, kbps = 200_000), 0))
    }

    @Test fun offlineMeansOne() = assertEquals(1, ConnectionPolicy.ceiling(net(NetworkKind.Wifi, online = false), 0))

    @Test fun theUsersLimitWinsButIsCapped() {
        assertEquals(5, ConnectionPolicy.ceiling(net(NetworkKind.Cellular, metered = true), 5))
        assertEquals(ConnectionPolicy.ABSOLUTE_MAX, ConnectionPolicy.ceiling(net(NetworkKind.Wifi), 500))
        assertEquals(1, ConnectionPolicy.ceiling(net(NetworkKind.Wifi), 1))
    }

    @Test fun parallelDownloadsShareTheLink() {
        val one = ConnectionPolicy.ceiling(net(NetworkKind.Wifi), 0, activeDownloads = 1)
        val three = ConnectionPolicy.ceiling(net(NetworkKind.Wifi), 0, activeDownloads = 3)
        assertTrue(three < one)
        assertTrue(three >= 2)
    }

    @Test fun theOldTwoFieldStatusStillWorks() {
        val s = NetworkStatus(online = true, metered = false)
        assertEquals(NetworkKind.Unknown, s.kind)
        assertEquals(4, ConnectionPolicy.ceiling(s, 0))
    }

    @Test fun startsModest() {
        assertEquals(2, ConnectionPolicy.initial(8))
        assertEquals(1, ConnectionPolicy.initial(1))
    }
}
