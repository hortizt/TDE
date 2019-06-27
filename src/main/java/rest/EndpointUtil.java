package rest;

import javax.annotation.Nonnull;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.handler.Handler;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;

/**
 * Created by akuranov on 14/07/2015.
 */
public class EndpointUtil {
    static {
        // Java7 has no TLSv1.2 enabled by default but TOA requires it
        System.setProperty("https.protocols", "TLSv1,TLSv1.1,TLSv1.2");
        //detectIndraProxy();
    }

    private static ProxyConfig detectIndraProxy() {
        try {
            // Annoying Indra proxy location
            InetSocketAddress addr = new InetSocketAddress("proxy.indra.es", 8080);
            try (Socket socket = new Socket()) {
                socket.connect(addr, 200);
            }
            System.setProperty("http.proxyHost", "proxy.indra.es");
            System.setProperty("http.proxyPort", "8080");
            System.setProperty("https.proxyHost", "proxy.indra.es");
            System.setProperty("https.proxyPort", "8080");
            return new ProxyConfig("proxy.indra.es", 8080, null, null);
        } catch (Exception e) {
            // not in Indra
        }
        return null;
    }


    public static ProxyConfig configureProxy(String host, Integer port, String user, String password) {
        if (host != null) {
            System.setProperty("http.proxyHost", host);
            System.setProperty("https.proxyHost", host);
        }
        if (port != null) {
            System.setProperty("http.proxyPort", String.valueOf(port));
            System.setProperty("https.proxyPort", String.valueOf(port));
        }
        if (user != null) {
            System.setProperty("http.proxyUser", user);
            System.setProperty("https.proxyUser", user);
        }
        if (password != null) {
            System.setProperty("http.proxyPassword", password);
            System.setProperty("https.proxyPassword", password);
        }

        if (host != null && port != null)
            return new ProxyConfig(host, port, user, password);

        return null;
    }

/*
    @SuppressWarnings("unchecked")
    public static <T> T adjust(T port, URL url, int connectTimeout, int responseTimeout, int retryCount) {
        BindingProvider endpoint = (BindingProvider) port;

        if (url != null) {
            // overriding endpoint address in the WSDL
            endpoint.getRequestContext().put(
                    BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
                    url.toString());
        }

        // AK: setting connection and call timeouts
        if (connectTimeout != 0) {
            endpoint.getRequestContext().put("javax.xml.ws.client.connectionTimeout", connectTimeout);
            endpoint.getRequestContext().put("com.sun.xml.internal.ws.connect.timeout", connectTimeout);
            // only for reference: Weblogic JAX-WS
            endpoint.getRequestContext().put("com.sun.xml.ws.connect.timeout", connectTimeout);
        }

        if (responseTimeout != 0) {
            endpoint.getRequestContext().put("javax.xml.ws.client.receiveTimeout", responseTimeout);
            endpoint.getRequestContext().put("com.sun.xml.internal.ws.request.timeout", responseTimeout);
            // only for reference: Weblogic JAX-WS
            endpoint.getRequestContext().put("com.sun.xml.ws.request.timeout", responseTimeout);
        }

        endpoint.getBinding().setHandlerChain(Collections.<Handler>singletonList(
                new SOAPLoggingHandler()));

        if (retryCount == 0)
            return port;
        else
            return (T) java.lang.reflect.Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    port.getClass().getInterfaces(), new SoapPortRetryProxyHandler(port,
                            retryCount));
    }

*/
    public static String encodeMd5(@Nonnull String string) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(string.getBytes());
            byte[] hash = md5.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String sh = Integer.toHexString(b & 0xFF);
                if (sh.length() < 2)
                    sb.append('0');
                sb.append(sh);
            }
            /*BigInteger hashNum = new BigInteger(1, hash);
            String hashFromContent = hashNum.toString(16);
            if (!sb.toString().equals(hashFromContent))
                throw new IllegalStateException("");*/
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
