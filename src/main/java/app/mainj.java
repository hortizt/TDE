package app;

import com.google.gson.JsonObject;
import rest.Credentials;
import rest.RestService;
import users.UsersService;

import java.io.IOException;
import java.util.List;

public class mainj {
    public static void main (String [ ] args) throws IOException{
        Credentials cr=new Credentials("app_sc2sap","74179367bf1a8cfeb3b37fa50647d76b38c2fc02533ad6c287eb0f5de901b155","telefonica-ar1.test");
        UsersService rs= new UsersService("https://api.etadirect.com/rest/ofscCore/v1/users",cr);

        List<JsonObject> resultUsers = rs.users();
        resultUsers=resultUsers;
    }

}
