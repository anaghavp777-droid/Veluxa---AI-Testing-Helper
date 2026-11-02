package com.veluxa;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prototype safeguard against testing private or local addresses.
 * Not a production-grade SSRF defense (DNS rebinding, redirects inside the browser, etc.).
 */
@Component
public class UrlValidator {

    @Value("${veluxa.allow-private:false}")
    private boolean allowPrivate;

    public URI validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Enter a website address.");
        }
        String s = raw.trim();
        if (!s.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            s = "https://" + s;
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That address isn't a valid URL.");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only http and https addresses are supported.");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("The address needs a host name, like example.com.");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Remove the username and password from the address.");
        }
        if (!allowPrivate) {
            try {
                for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                    if (blocked(a)) {
                        throw new IllegalArgumentException(
                            "Private, local and reserved addresses are blocked. "
                            + "Set veluxa.allow-private=true in application.properties to test a local app.");
                    }
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Couldn't find that host. Check the spelling and your connection.");
            }
        }
        return uri;
    }

    static boolean blocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 255, b1 = b[1] & 255, b2 = b[2] & 255;
            if (b0 == 0) return true;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;
            if (b0 == 192 && b1 == 0 && b2 == 0) return true;
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;
            if (b0 >= 240) return true;
        } else if (b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) return true;
        }
        return false;
    }
}
