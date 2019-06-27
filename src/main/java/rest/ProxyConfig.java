package rest;

public class ProxyConfig {
    public final String host;
    public final Integer port;
    public final String user;
    public final String password;

    public ProxyConfig(String host, Integer port, String user, String password) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
    }
}
