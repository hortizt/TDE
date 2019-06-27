package rest;


import javax.xml.datatype.DatatypeFactory;
import java.lang.reflect.Field;
import java.util.GregorianCalendar;

public class Credentials {
    public final String login;
    public final String password;
    public final String company;

    public Credentials(String login, String password, String company) {
        this.login = login;
        this.password = password;
        this.company = company;
    }

    public <T> T mapTo( T user) {
        try {
            String now = DatatypeFactory.newInstance().newXMLGregorianCalendar(
                    new GregorianCalendar()).toXMLFormat();
            String hash = EndpointUtil.encodeMd5(password);
            String authString = EndpointUtil.encodeMd5(now + hash);
            Field f = user.getClass().getDeclaredField("now");
            f.setAccessible(true);
            f.set(user, now);
            f = user.getClass().getDeclaredField("login");
            f.setAccessible(true);
            f.set(user, login);
            f = user.getClass().getDeclaredField("company");
            f.setAccessible(true);
            f.set(user, company);
            f = user.getClass().getDeclaredField("authString");
            f.setAccessible(true);
            f.set(user, authString);
        } catch (Exception e) {
            throw new RuntimeException("Setting auth data", e);
        }

        return user;
    }
}
