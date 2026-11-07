package com.almus.ytdownloader.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressResolverTest {
    private fun navigates(input: String?, expected: String) =
        assertEquals(AddressResult.Navigate(expected), AddressResolver.resolve(input))

    private fun invalid(input: String?): AddressResult.Invalid {
        val result = AddressResolver.resolve(input)
        assertTrue("expected Invalid for <$input> but was $result", result is AddressResult.Invalid)
        return result as AddressResult.Invalid
    }

    @Test fun addressWithoutSchemeGetsHttps() {
        navigates("example.com", "https://example.com")
        navigates("  example.com/path?q=1  ", "https://example.com/path?q=1")
        navigates("192.168.1.1", "https://192.168.1.1")
    }

    @Test fun explicitHttpAndHttpsAreKept() {
        navigates("http://example.com", "http://example.com")
        navigates("https://example.com/a/b.html", "https://example.com/a/b.html")
        navigates("http://example.com:8080/a", "http://example.com:8080/a")
    }

    @Test fun schemeIsNormalisedToLowerCase() {
        navigates("HTTP://Example.com", "http://Example.com")
        navigates("HtTpS://example.com", "https://example.com")
    }

    @Test fun localhostAndIpv6AreAddresses() {
        navigates("localhost:8080", "https://localhost:8080")
        navigates("[::1]:8080", "https://[::1]:8080")
    }

    @Test fun otherSchemesAreRejectedNotGuessed() {
        for (input in listOf(
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "data:text/html,hi",
            "file:///etc/passwd",
            "content://media/external/file/1",
            "intent://scan/#Intent;scheme=zxing;end",
            "mailto:someone@example.com",
            "tel:+123456",
            "ftp://example.com/file",
            "view-source:https://example.com",
            "blob:https://example.com/uuid",
        )) {
            invalid(input)
        }
        assertEquals(AddressResolver.ONLY_HTTP, invalid("javascript:alert(1)").message)
    }

    @Test fun plainTextIsNotSearchedFor() {
        assertEquals(AddressResolver.NOT_AN_ADDRESS, invalid("hello world").message)
        assertEquals(AddressResolver.NOT_AN_ADDRESS, invalid("hello").message)
        assertEquals(AddressResolver.NOT_AN_ADDRESS, invalid("exa mple.com").message)
    }

    @Test fun emptyInputIsRejected() {
        assertEquals("Enter a web address.", invalid("").message)
        assertEquals("Enter a web address.", invalid("   ").message)
        assertEquals("Enter a web address.", invalid(null).message)
    }

    @Test fun credentialsInTheAddressAreRejected() {
        invalid("https://user:pw@example.com")
        invalid("https://example.com@evil.com")
    }

    @Test fun missingHostAndTooLongAreRejected() {
        invalid("https://")
        invalid("https://example.com/" + "a".repeat(3000))
    }
}
